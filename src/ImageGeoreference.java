package org.openstreetmap.josm.plugins.buildingdetector;

import java.awt.Rectangle;
import java.awt.geom.Point2D;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.ILatLon;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Ellipsoid;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.gui.MapViewState;

/**
 * Eingefrorene Georeferenzierung eines aus der Kartenansicht gerenderten Luftbildes.
 *
 * Die Zuordnung Bild ↔ Boden wird im Moment der Bildaufnahme festgehalten ({@link MapViewState} ist
 * unveränderlich). Alle späteren Umrechnungen verwenden diesen Zustand und nicht die aktuelle
 * Kartenansicht: Verschiebt oder zoomt der Benutzer während der Bildverarbeitung, landen die
 * Gebäude trotzdem an der Stelle, an der sie im ausgewerteten Bild liegen.
 *
 * Koordinatenkonvention: Bildkoordinaten sind Pixelindex-Koordinaten, ein ganzzahliger Wert
 * bezeichnet das Zentrum eines Pixels. Pixel {@code (i, j)} bedeckt in der Kartenansicht die Fläche
 * {@code [i, i+1) x [j, j+1)}; sein Zentrum liegt daher bei {@code (i + 0.5, j + 0.5)}.
 */
public final class ImageGeoreference {

    /** Versatz zwischen Pixelindex (Pixelzentrum) und kontinuierlicher Ansichtskoordinate. */
    private static final double PIXEL_CENTER = 0.5;

    /** Halbe Basislänge in Pixeln für die numerische Ableitung der Abbildung Bild → Boden. */
    private static final double RESOLUTION_BASELINE_PX = 10.0;

    private final MapViewState state;
    private final int width;
    private final int height;

    /**
     * @param state  Zustand der Kartenansicht zum Zeitpunkt der Bildaufnahme
     * @param width  Bildbreite in Pixeln
     * @param height Bildhöhe in Pixeln
     */
    public ImageGeoreference(MapViewState state, int width, int height) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        this.state = state;
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /** Kartenprojektion zum Zeitpunkt der Bildaufnahme. */
    public Projection getProjection() {
        return state.getProjection();
    }

    /** Projizierte Koordinate (EastNorth) einer subpixelgenauen Bildposition. */
    public EastNorth pixelToEastNorth(double px, double py) {
        return state.getForView(px + PIXEL_CENTER, py + PIXEL_CENTER).getEastNorth();
    }

    /** Geographische Koordinate einer subpixelgenauen Bildposition. */
    public LatLon pixelToLatLon(double px, double py) {
        return state.getForView(px + PIXEL_CENTER, py + PIXEL_CENTER).getLatLon();
    }

    /** Subpixelgenaue Bildposition einer geographischen Koordinate (kann außerhalb des Bildes liegen). */
    public Point2D.Double latLonToPixel(ILatLon coordinate) {
        MapViewState.MapViewPoint p = state.getPointFor(coordinate);
        return new Point2D.Double(p.getInViewX() - PIXEL_CENTER, p.getInViewY() - PIXEL_CENTER);
    }

    /**
     * Kleinstes Pixelrechteck, das ein geographisches Rechteck überdeckt, beschnitten auf das Bild.
     * Der Rand wird zusätzlich an den Seitenmitten abgetastet, damit das Ergebnis auch für
     * Projektionen stimmt, in denen Meridiane und Breitenkreise gekrümmt abgebildet werden.
     *
     * @return Pixelrechteck in Bildkoordinaten; leer, wenn der Bereich außerhalb des Bildes liegt
     */
    public Rectangle boundsToPixelRectangle(Bounds bounds) {
        double minLat = bounds.getMinLat();
        double maxLat = bounds.getMaxLat();
        double minLon = bounds.getMinLon();
        double maxLon = bounds.getMaxLon();
        double midLat = 0.5 * (minLat + maxLat);
        double midLon = 0.5 * (minLon + maxLon);
        double[][] outline = {
            {minLat, minLon}, {minLat, midLon}, {minLat, maxLon}, {midLat, maxLon},
            {maxLat, maxLon}, {maxLat, midLon}, {maxLat, minLon}, {midLat, minLon}
        };

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] ll : outline) {
            Point2D.Double p = latLonToPixel(new LatLon(ll[0], ll[1]));
            minX = Math.min(minX, p.x);
            maxX = Math.max(maxX, p.x);
            minY = Math.min(minY, p.y);
            maxY = Math.max(maxY, p.y);
        }
        return clipToImage(minX, minY, maxX, maxY);
    }

    /**
     * Beschneidet ein Pixelrechteck (Zentren der äußersten Pixel) auf die Bildfläche.
     * Die Arithmetik erfolgt in double, damit weit außerhalb liegende Bereiche keinen
     * Ganzzahlüberlauf erzeugen.
     */
    private Rectangle clipToImage(double minX, double minY, double maxX, double maxY) {
        double x0 = Math.max(0.0, Math.ceil(minX));
        double y0 = Math.max(0.0, Math.ceil(minY));
        double x1 = Math.min(width - 1.0, Math.floor(maxX));
        double y1 = Math.min(height - 1.0, Math.floor(maxY));
        if (!(x1 >= x0) || !(y1 >= y0)) {
            return new Rectangle();
        }
        return new Rectangle((int) x0, (int) y0, (int) (x1 - x0) + 1, (int) (y1 - y0) + 1);
    }

    /**
     * Bestimmt die Bodenauflösung an einer Bildposition getrennt für beide Pixelachsen.
     * Die Abbildung Bild → Boden wird numerisch differenziert und im lokalen metrischen System
     * des WGS84-Ellipsoids ausgewertet (Meridian- und Querkrümmungsradius).
     */
    public GroundResolution groundResolutionAt(double px, double py) {
        double h = RESOLUTION_BASELINE_PX;
        double[] alongX = localMetricVector(pixelToLatLon(px - h, py), pixelToLatLon(px + h, py));
        double[] alongY = localMetricVector(pixelToLatLon(px, py - h), pixelToLatLon(px, py + h));
        return new GroundResolution(
            alongX[0] / (2.0 * h), alongX[1] / (2.0 * h),
            alongY[0] / (2.0 * h), alongY[1] / (2.0 * h));
    }

    /**
     * Differenzvektor zweier benachbarter Punkte im lokalen metrischen Ost-Nord-System auf dem
     * WGS84-Ellipsoid: {@code dOst = N cos(phi) dLambda}, {@code dNord = M dPhi}.
     * Gültig für kurze Strecken (Gebäudemaßstab); der Fehler gegenüber der geodätischen Linie ist
     * dort vernachlässigbar.
     *
     * @return {dOst, dNord} in Metern
     */
    public static double[] localMetricVector(ILatLon from, ILatLon to) {
        double phi = Math.toRadians(0.5 * (from.lat() + to.lat()));
        double dPhi = Math.toRadians(to.lat() - from.lat());
        double dLambda = Math.toRadians(to.lon() - from.lon());
        // Kürzester Weg über den 180°-Meridian
        if (dLambda > Math.PI) {
            dLambda -= 2.0 * Math.PI;
        } else if (dLambda < -Math.PI) {
            dLambda += 2.0 * Math.PI;
        }

        Ellipsoid wgs84 = Ellipsoid.WGS84;
        double sinPhi = Math.sin(phi);
        double w2 = 1.0 - wgs84.e2 * sinPhi * sinPhi;
        double primeVerticalRadius = wgs84.a / Math.sqrt(w2);            // N
        double meridianRadius = wgs84.a * (1.0 - wgs84.e2) / (w2 * Math.sqrt(w2)); // M

        return new double[] {primeVerticalRadius * Math.cos(phi) * dLambda, meridianRadius * dPhi};
    }

    /**
     * Lokale Bodenauflösung eines Bildes: Bodenvektoren, die einem Pixelschritt in x- bzw. y-Richtung
     * entsprechen (Jacobi-Matrix der Abbildung Bild → Boden in m/px).
     */
    public static final class GroundResolution {
        private final double xEast;
        private final double xNorth;
        private final double yEast;
        private final double yNorth;

        GroundResolution(double xEast, double xNorth, double yEast, double yNorth) {
            this.xEast = xEast;
            this.xNorth = xNorth;
            this.yEast = yEast;
            this.yNorth = yNorth;
        }

        /** Bodenauflösung entlang der Bildzeilen in m/px. */
        public double getMetersPerPixelX() {
            return Math.hypot(xEast, xNorth);
        }

        /** Bodenauflösung entlang der Bildspalten in m/px. */
        public double getMetersPerPixelY() {
            return Math.hypot(yEast, yNorth);
        }

        /** Bodenfläche eines Pixels in m². */
        public double getPixelAreaM2() {
            return Math.abs(xEast * yNorth - xNorth * yEast);
        }

        /**
         * Flächentreue mittlere Bodenauflösung in m/px: Quadratwurzel der Pixelfläche. Mit diesem
         * Wert stimmen Flächenschwellen in m² auch dann, wenn die Pixel am Boden nicht quadratisch sind.
         */
        public double getMeanMetersPerPixel() {
            return Math.sqrt(getPixelAreaM2());
        }

        /** Relativer Maßstabsunterschied der beiden Pixelachsen (0 = quadratische Pixel). */
        public double getAnisotropy() {
            double gx = getMetersPerPixelX();
            double gy = getMetersPerPixelY();
            double min = Math.min(gx, gy);
            return min > 0.0 ? Math.max(gx, gy) / min - 1.0 : Double.POSITIVE_INFINITY;
        }

        /** Abweichung des Winkels zwischen den Pixelachsen am Boden von 90° in Grad. */
        public double getSkewDegrees() {
            double gx = getMetersPerPixelX();
            double gy = getMetersPerPixelY();
            if (gx <= 0.0 || gy <= 0.0) {
                return 90.0;
            }
            double cosAngle = (xEast * yEast + xNorth * yNorth) / (gx * gy);
            return Math.abs(90.0 - Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, cosAngle)))));
        }

        /**
         * Prüft, ob das Bild den Boden lokal winkeltreu abbildet. Nur dann sind rechtwinklige Gebäude
         * auch im Bild rechtwinklig und die pixelbasierte Rechteckanpassung geometrisch zulässig.
         *
         * @param maxAnisotropy  Zulässiger relativer Maßstabsunterschied der Achsen
         * @param maxSkewDegrees Zulässige Abweichung von der Rechtwinkligkeit in Grad
         */
        public boolean isConformal(double maxAnisotropy, double maxSkewDegrees) {
            return getAnisotropy() <= maxAnisotropy && getSkewDegrees() <= maxSkewDegrees;
        }
    }
}
