package org.openstreetmap.josm.plugins.buildingdetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Point2D;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.plugins.buildingdetector.ImageGeoreference.GroundResolution;

/** Georeferenzierung Bild ↔ Boden: Pixelkonvention, Bodenauflösung, Winkeltreue, Suchfenster. */
class ImageGeoreferenceTest {

    private static final int W = 900;
    private static final int H = 700;
    private static final double LAT = JosmTestSupport.VIENNA.lat();

    private static ImageGeoreference georeference(String code, double scale) {
        Projection projection = JosmTestSupport.useProjection(code);
        return new ImageGeoreference(JosmTestSupport.viewState(projection, JosmTestSupport.VIENNA, scale, W, H), W, H);
    }

    @Test
    void pixelIndexRefersToThePixelCentre() {
        Projection projection = JosmTestSupport.useProjection("EPSG:3857");
        MapViewState state = JosmTestSupport.viewState(projection, JosmTestSupport.VIENNA, 0.45, W, H);
        ImageGeoreference geo = new ImageGeoreference(state, W, H);

        // Pixel (i, j) bedeckt in der Ansicht [i, i+1) x [j, j+1): das Zentrum liegt bei (i + 0,5, j + 0,5)
        EastNorth expected = state.getForView(10.5, 20.5).getEastNorth();
        EastNorth actual = geo.pixelToEastNorth(10, 20);
        assertEquals(expected.east(), actual.east(), 1e-9);
        assertEquals(expected.north(), actual.north(), 1e-9);

        // Ein Pixel nach rechts entspricht genau einer Maßstabseinheit nach Osten, nach unten nach Süden
        assertEquals(0.45, geo.pixelToEastNorth(11, 20).east() - actual.east(), 1e-9);
        assertEquals(-0.45, geo.pixelToEastNorth(10, 21).north() - actual.north(), 1e-9);
    }

    @Test
    void pixelToLatLonAndBackIsConsistent() {
        ImageGeoreference geo = georeference("EPSG:3857", 0.45);
        Point2D.Double back = geo.latLonToPixel(geo.pixelToLatLon(123.25, 456.75));
        assertEquals(123.25, back.x, 1e-6);
        assertEquals(456.75, back.y, 1e-6);
    }

    /**
     * Web-Mercator rechnet mit Kugelformeln auf ellipsoidischen Breiten. Am Boden gilt daher
     * gsdX = s N cos(phi) / a und gsdY = s M cos(phi) / a; die Abbildung ist nur näherungsweise winkeltreu.
     */
    @Test
    void groundResolutionInWebMercatorMatchesTheEllipsoidalFormula() {
        double scale = 0.45;
        GroundResolution r = georeference("EPSG:3857", scale).groundResolutionAt(W / 2.0, H / 2.0);
        double cosPhi = Math.cos(Math.toRadians(LAT));
        double expectedX = scale * JosmTestSupport.primeVerticalRadius(LAT) * cosPhi / JosmTestSupport.WGS84_A;
        double expectedY = scale * JosmTestSupport.meridianRadius(LAT) * cosPhi / JosmTestSupport.WGS84_A;

        assertEquals(expectedX, r.getMetersPerPixelX(), 1e-6);
        assertEquals(expectedY, r.getMetersPerPixelY(), 1e-6);
        assertEquals(Math.sqrt(expectedX * expectedY), r.getMeanMetersPerPixel(), 1e-6, "flächentreues Mittel");
        assertEquals(expectedX * expectedY, r.getPixelAreaM2(), 1e-6);
        assertTrue(r.getAnisotropy() < 0.007, "Web-Mercator weicht höchstens um e² von der Winkeltreue ab");
        assertEquals(0.0, r.getSkewDegrees(), 1e-6);
        assertTrue(r.isConformal(0.02, 1.0));
    }

    @Test
    void transverseMercatorProjectionsAreConformalWithScaleNearOne() {
        for (String code : new String[] {"EPSG:32633", "EPSG:31256"}) {
            GroundResolution r = georeference(code, 0.30).groundResolutionAt(W / 2.0, H / 2.0);
            assertTrue(r.getAnisotropy() < 1e-4, code + " Anisotropie " + r.getAnisotropy());
            assertTrue(r.getSkewDegrees() < 0.01, code + " Scherung " + r.getSkewDegrees());
            assertEquals(0.30, r.getMeanMetersPerPixel(), 0.30 * 2e-3, code + " Maßstab");
            assertTrue(r.isConformal(0.02, 1.0));
        }
    }

    @Test
    void plateCarreeIsDetectedAsNotConformal() {
        double scaleDeg = 0.3 / 111320.0;
        GroundResolution r = georeference("EPSG:4326", scaleDeg).groundResolutionAt(W / 2.0, H / 2.0);
        double expectedX = Math.toRadians(scaleDeg) * JosmTestSupport.primeVerticalRadius(LAT) * Math.cos(Math.toRadians(LAT));
        double expectedY = Math.toRadians(scaleDeg) * JosmTestSupport.meridianRadius(LAT);

        assertEquals(expectedX, r.getMetersPerPixelX(), 1e-6);
        assertEquals(expectedY, r.getMetersPerPixelY(), 1e-6);
        assertEquals(expectedY / expectedX - 1.0, r.getAnisotropy(), 1e-6);
        assertFalse(r.isConformal(0.02, 1.0), "EPSG:4326 staucht die Ost-West-Richtung um cos(phi)");
    }

    @Test
    void localMetricVectorAgreesWithSphericalDistanceAndHandlesTheAntimeridian() {
        LatLon a = new LatLon(48.2082, 16.3738);
        LatLon b = new LatLon(48.2086, 16.3745);
        double[] v = ImageGeoreference.localMetricVector(a, b);
        assertTrue(v[0] > 0 && v[1] > 0, "Ost und Nord positiv");
        // Kugelstrecke als unabhängige Kontrolle; Ellipsoid und Kugel unterscheiden sich um weniger als 0,5 %
        assertEquals(a.greatCircleDistance(b), Math.hypot(v[0], v[1]), 0.005 * a.greatCircleDistance(b));

        double[] across = ImageGeoreference.localMetricVector(new LatLon(0.0, 179.9999), new LatLon(0.0, -179.9999));
        assertEquals(0.0002 * Math.PI / 180.0 * JosmTestSupport.WGS84_A, across[0], 1e-3, "kürzester Weg über 180°");
    }

    @Test
    void boundsAreConvertedToThePixelsWhoseCentresLieInside() {
        ImageGeoreference geo = georeference("EPSG:3857", 0.45);
        // Grenzen bewusst zwischen Pixelzentren legen
        LatLon northWest = geo.pixelToLatLon(199.5, 149.5);
        LatLon southEast = geo.pixelToLatLon(600.5, 500.5);
        Bounds bounds = new Bounds(southEast.lat(), northWest.lon(), northWest.lat(), southEast.lon());

        assertEquals(new Rectangle(200, 150, 401, 351), geo.boundsToPixelRectangle(bounds));
    }

    @Test
    void boundsOutsideTheImageYieldAnEmptyRectangleAndLargeBoundsAreClipped() {
        ImageGeoreference geo = georeference("EPSG:3857", 0.45);
        assertTrue(geo.boundsToPixelRectangle(new Bounds(10.0, 10.0, 11.0, 11.0)).isEmpty());
        assertEquals(new Rectangle(0, 0, W, H), geo.boundsToPixelRectangle(new Bounds(40.0, 5.0, 55.0, 25.0)));
    }
}
