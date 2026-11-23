// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.openstreetmap.josm.plugins.buildingdetector.ImageProcessor.Point;

/**
 * Repräsentiert eine geodätisch und fernerkundlich ausgewertete Gebäudekontur.
 * Speichert sowohl die rohen Randpunkte als auch die orthogonalisierten Eckpunkte,
 * die Hauptorientierung (Azimut), Schattenwurf-Indikatoren und Nachbarschafts-Scores.
 *
 * Koordinatenkonvention: Alle Koordinaten sind Pixelindex-Koordinaten des Luftbildes, d. h. ein
 * ganzzahliger Wert bezeichnet das Zentrum eines Pixels. Die Eckpunkte werden subpixelgenau
 * (double) geführt, damit weder die OMBB-Berechnung noch spätere Drehungen die Geometrie durch
 * Runden auf ganze Pixel verschlechtern.
 */
public class BuildingContour {

    private List<Point2D.Double> corners;
    private List<Point> rawBoundaryPoints;
    private double dominantAngleRad; // Hauptorientierung im Bogenmaß [-pi/2, pi/2]
    private double rectangularity;   // Verhältnis wahre Fläche zu OMBB-Fläche [0..1]
    private double shadowScore;      // 3D-Schattenwurf-Evidenz [0..1]
    private double edgeStrength;     // Mittlere Kantenstärke entlang der Kontur [0..1]
    private double neighborhoodScore;// Nachbarschafts- & Fluchtkonsens [0..1]
    private double confidenceScore;  // Gesamtkonfidenz [0..1]

    /**
     * Konstruktor für eine erkannte Gebäudekontur.
     *
     * @param corners Geordnete Liste der Eckpunkte (ideal: 4 Punkte der OMBB oder L-Polygon)
     */
    public BuildingContour(List<Point> corners) {
        this(corners, corners, 0.0, 1.0);
    }

    public BuildingContour(List<Point> corners, List<Point> rawBoundaryPoints, double dominantAngleRad, double rectangularity) {
        this(dominantAngleRad, rectangularity, toExact(corners),
            rawBoundaryPoints != null ? rawBoundaryPoints : corners);
    }

    /**
     * Erzeugt eine Kontur aus subpixelgenauen Eckpunkten.
     *
     * @param exactCorners      Geordnete Eckpunkte in Pixelindex-Koordinaten (subpixelgenau)
     * @param rawBoundaryPoints Rohe Randpixel der Region
     * @param dominantAngleRad  Hauptorientierung im Bogenmaß
     * @param rectangularity    Verhältnis wahre Fläche zu OMBB-Fläche [0..1]
     */
    public static BuildingContour fromExactCorners(
            List<Point2D.Double> exactCorners, List<Point> rawBoundaryPoints,
            double dominantAngleRad, double rectangularity) {
        return new BuildingContour(dominantAngleRad, rectangularity, exactCorners,
            rawBoundaryPoints != null ? rawBoundaryPoints : toRounded(exactCorners));
    }

    private BuildingContour(double dominantAngleRad, double rectangularity,
            List<Point2D.Double> exactCorners, List<Point> rawBoundaryPoints) {
        this.corners = copyExact(exactCorners);
        this.rawBoundaryPoints = new ArrayList<>(rawBoundaryPoints);
        this.dominantAngleRad = normalizeAngleHalfPi(dominantAngleRad);
        this.rectangularity = Math.max(0.0, Math.min(1.0, rectangularity));
        this.shadowScore = 0.0;
        this.edgeStrength = 0.5;
        this.neighborhoodScore = 0.5;
        this.confidenceScore = 0.5;
    }

    /**
     * Eckpunkte, auf ganze Pixel gerundet (Abwärtskompatibilität, Bildschirmdarstellung).
     * Für die Georeferenzierung {@link #getExactCorners()} verwenden.
     */
    public List<Point> getCorners() {
        return Collections.unmodifiableList(toRounded(corners));
    }

    /**
     * Subpixelgenaue Eckpunkte in Pixelindex-Koordinaten (ganzzahlig = Pixelzentrum).
     *
     * @return Kopie der Eckpunktliste
     */
    public List<Point2D.Double> getExactCorners() {
        return Collections.unmodifiableList(copyExact(corners));
    }

    public void setCorners(List<Point> corners) {
        this.corners = toExact(corners);
    }

    public void setExactCorners(List<Point2D.Double> exactCorners) {
        this.corners = copyExact(exactCorners);
    }

    private static List<Point2D.Double> toExact(List<Point> points) {
        List<Point2D.Double> exact = new ArrayList<>(points.size());
        for (Point p : points) {
            exact.add(new Point2D.Double(p.x, p.y));
        }
        return exact;
    }

    private static List<Point2D.Double> copyExact(List<Point2D.Double> points) {
        List<Point2D.Double> copy = new ArrayList<>(points.size());
        for (Point2D.Double p : points) {
            copy.add(new Point2D.Double(p.x, p.y));
        }
        return copy;
    }

    private static List<Point> toRounded(List<Point2D.Double> points) {
        List<Point> rounded = new ArrayList<>(points.size());
        for (Point2D.Double p : points) {
            rounded.add(new Point((int) Math.round(p.x), (int) Math.round(p.y)));
        }
        return rounded;
    }

    public List<Point> getRawBoundaryPoints() {
        return Collections.unmodifiableList(rawBoundaryPoints);
    }

    /**
     * Berechnet die exakte Polygonfläche mittels Gaußscher Trapezformel (Shoelace-Formel).
     *
     * @return Fläche in Quadratpixeln
     */
    public double getExactArea() {
        if (corners == null || corners.size() < 3) {
            return 0.0;
        }
        double sum = 0.0;
        int n = corners.size();
        for (int i = 0; i < n; i++) {
            Point2D.Double p1 = corners.get(i);
            Point2D.Double p2 = corners.get((i + 1) % n);
            sum += p1.x * p2.y - p2.x * p1.y;
        }
        return Math.abs(sum) * 0.5;
    }

    /**
     * Gibt die gerundete Polygonfläche in Quadratpixeln zurück (Abwärtskompatibilität).
     *
     * @return Fläche in Quadratpixeln
     */
    public int getArea() {
        return (int) Math.round(getExactArea());
    }

    /**
     * Berechnet den Umfang der Gebäudekontur.
     *
     * @return Umfang in Pixeln
     */
    public double getPerimeter() {
        if (corners == null || corners.size() < 2) {
            return 0.0;
        }
        double perimeter = 0.0;
        int n = corners.size();
        for (int i = 0; i < n; i++) {
            Point2D.Double current = corners.get(i);
            Point2D.Double next = corners.get((i + 1) % n);
            perimeter += Math.hypot(next.x - current.x, next.y - current.y);
        }
        return perimeter;
    }

    /**
     * Berechnet den geometrischen Schwerpunkt (Centroid) der Kontur, auf ganze Pixel gerundet.
     *
     * @return Schwerpunkt als Point
     */
    public Point getCenter() {
        Point2D.Double c = getExactCenter();
        return new Point((int) Math.round(c.x), (int) Math.round(c.y));
    }

    /**
     * Berechnet den subpixelgenauen Schwerpunkt der Eckpunkte.
     *
     * @return Schwerpunkt in Pixelindex-Koordinaten
     */
    public Point2D.Double getExactCenter() {
        if (corners == null || corners.isEmpty()) {
            return new Point2D.Double(0, 0);
        }
        double sumX = 0;
        double sumY = 0;
        for (Point2D.Double corner : corners) {
            sumX += corner.x;
            sumY += corner.y;
        }
        return new Point2D.Double(sumX / corners.size(), sumY / corners.size());
    }

    /**
     * Berechnet das Seitenverhältnis (Länge zu Breite) unabhängig von der Drehung.
     *
     * @return Seitenverhältnis >= 1.0
     */
    public double getAspectRatio() {
        if (corners == null || corners.size() < 4) {
            return 1.0;
        }
        double d1 = Math.hypot(corners.get(1).x - corners.get(0).x, corners.get(1).y - corners.get(0).y);
        double d2 = Math.hypot(corners.get(2).x - corners.get(1).x, corners.get(2).y - corners.get(1).y);
        double minSide = Math.max(1.0, Math.min(d1, d2));
        double maxSide = Math.max(d1, d2);
        return maxSide / minSide;
    }

    /**
     * Prüft die echte Rechtwinkligkeit der Kontur anhand der Innenwinkel (Skalarprodukt)
     * und des Seitenverhältnisses.
     *
     * @return True, wenn alle Innenwinkel nahe 90° liegen und das Seitenverhältnis plausibel ist
     */
    public boolean isRectangular() {
        if (corners == null || (corners.size() != 4 && corners.size() != 6 && corners.size() != 8)) {
            return false;
        }
        if (getAspectRatio() > 8.0 || rectangularity < 0.55) {
            return false;
        }

        int n = corners.size();
        for (int i = 0; i < n; i++) {
            Point2D.Double prev = corners.get((i - 1 + n) % n);
            Point2D.Double curr = corners.get(i);
            Point2D.Double next = corners.get((i + 1) % n);

            double vx1 = prev.x - curr.x;
            double vy1 = prev.y - curr.y;
            double vx2 = next.x - curr.x;
            double vy2 = next.y - curr.y;

            double len1 = Math.hypot(vx1, vy1);
            double len2 = Math.hypot(vx2, vy2);
            if (len1 < 1e-3 || len2 < 1e-3) {
                return false;
            }

            // Kosinus des eingeschlossenen Winkels (bei 90° ist cos = 0)
            double cosTheta = Math.abs((vx1 * vx2 + vy1 * vy2) / (len1 * len2));
            if (cosTheta > 0.22) { // Erlaubt ca. +/- 12.5° Abweichung vor finaler Orthogonalisierung
                return false;
            }
        }
        return true;
    }

    /**
     * Rotiert die Eckpunkte der Kontur um ihren Schwerpunkt auf einen neuen Zielwinkel,
     * z. B. zur Ausrichtung an der dominanten Gebäudeflucht der Nachbarschaft.
     *
     * @param newAngleRad Neuer Hauptwinkel im Bogenmaß
     */
    public void alignToAngle(double newAngleRad) {
        double delta = normalizeAnglePiOver2(newAngleRad - this.dominantAngleRad);
        if (Math.abs(delta) < 1e-4 || corners.isEmpty()) {
            return;
        }
        Point2D.Double center = getExactCenter();
        double cos = Math.cos(delta);
        double sin = Math.sin(delta);

        // Drehung ohne Runden auf ganze Pixel: die Kontur bleibt ein exaktes Rechteck.
        List<Point2D.Double> rotated = new ArrayList<>(corners.size());
        for (Point2D.Double p : corners) {
            double dx = p.x - center.x;
            double dy = p.y - center.y;
            rotated.add(new Point2D.Double(center.x + dx * cos - dy * sin, center.y + dx * sin + dy * cos));
        }
        this.corners = rotated;
        this.dominantAngleRad = normalizeAngleHalfPi(newAngleRad);
    }

    /**
     * Verschiebt die Kontur (Eckpunkte und rohe Randpunkte) um den angegebenen Versatz,
     * z. B. von ROI-lokalen Koordinaten zurück in die Koordinaten des Gesamtbildes.
     * Schwerpunkt, Bounding-Box und Fläche werden aus den Eckpunkten berechnet und folgen automatisch.
     *
     * @param dx Versatz in x-Richtung (Pixel)
     * @param dy Versatz in y-Richtung (Pixel)
     */
    public void translate(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return;
        }
        // Neue Punkt-Objekte anlegen, statt an Ort und Stelle zu verschieben: Aufrufer können
        // dieselben Point-Instanzen noch in eigenen Listen halten.
        List<Point2D.Double> movedCorners = new ArrayList<>(corners.size());
        for (Point2D.Double p : corners) {
            movedCorners.add(new Point2D.Double(p.x + dx, p.y + dy));
        }
        List<Point> movedBoundary = new ArrayList<>(rawBoundaryPoints.size());
        for (Point p : rawBoundaryPoints) {
            movedBoundary.add(new Point(p.x + dx, p.y + dy));
        }
        this.corners = movedCorners;
        this.rawBoundaryPoints = movedBoundary;
    }

    /**
     * Anteil der kleineren der beiden Konturen, der von der anderen überdeckt wird (0 = getrennt,
     * 1 = die kleinere liegt vollständig in der größeren).
     *
     * Die Schnittfläche wird exakt aus den Eckpunkten berechnet (Clipping konvexer Polygone). Ein Vergleich
     * der achsparallelen Hüllrechtecke würde schräg stehende Nachbargebäude fälschlich als überlappend werten.
     */
    public double computeOverlapRatio(BuildingContour other) {
        if (corners == null || other.corners == null || corners.size() < 3 || other.corners.size() < 3) {
            return 0.0;
        }
        if (!this.getBoundingBox().intersects(other.getBoundingBox())) {
            return 0.0;
        }
        double minArea = Math.min(this.getExactArea(), other.getExactArea());
        if (minArea <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, convexIntersectionArea(this.corners, other.corners) / minArea);
    }

    /** Schnittfläche zweier konvexer Polygone (Sutherland-Hodgman), unabhängig vom Umlaufsinn. */
    static double convexIntersectionArea(List<Point2D.Double> subject, List<Point2D.Double> clip) {
        List<Point2D.Double> output = new ArrayList<>(subject);
        double orientation = Math.signum(signedArea(clip));
        if (orientation == 0.0) {
            return 0.0;
        }
        int n = clip.size();
        for (int i = 0; i < n && !output.isEmpty(); i++) {
            Point2D.Double a = clip.get(i);
            Point2D.Double b = clip.get((i + 1) % n);
            List<Point2D.Double> input = output;
            output = new ArrayList<>(input.size() + 2);
            for (int k = 0; k < input.size(); k++) {
                Point2D.Double p = input.get(k);
                Point2D.Double q = input.get((k + 1) % input.size());
                double sideP = orientation * ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x));
                double sideQ = orientation * ((b.x - a.x) * (q.y - a.y) - (b.y - a.y) * (q.x - a.x));
                if (sideP >= 0) {
                    output.add(p);
                }
                if ((sideP > 0 && sideQ < 0) || (sideP < 0 && sideQ > 0)) {
                    double t = sideP / (sideP - sideQ);
                    output.add(new Point2D.Double(p.x + t * (q.x - p.x), p.y + t * (q.y - p.y)));
                }
            }
        }
        return output.size() < 3 ? 0.0 : Math.abs(signedArea(output));
    }

    private static double signedArea(List<Point2D.Double> polygon) {
        double sum = 0.0;
        for (int i = 0; i < polygon.size(); i++) {
            Point2D.Double p = polygon.get(i);
            Point2D.Double q = polygon.get((i + 1) % polygon.size());
            sum += p.x * q.y - q.x * p.y;
        }
        return 0.5 * sum;
    }

    public Polygon toAwtPolygon() {
        Polygon poly = new Polygon();
        for (Point p : toRounded(corners)) {
            poly.addPoint(p.x, p.y);
        }
        return poly;
    }

    public Rectangle getBoundingBox() {
        if (corners == null || corners.isEmpty()) {
            return new Rectangle(0, 0, 0, 0);
        }
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Point2D.Double corner : corners) {
            minX = Math.min(minX, corner.x);
            maxX = Math.max(maxX, corner.x);
            minY = Math.min(minY, corner.y);
            maxY = Math.max(maxY, corner.y);
        }
        // Kleinstes ganzzahliges Rechteck, das die subpixelgenaue Kontur vollständig enthält
        int x0 = (int) Math.floor(minX);
        int y0 = (int) Math.floor(minY);
        int x1 = (int) Math.ceil(maxX);
        int y1 = (int) Math.ceil(maxY);
        return new Rectangle(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
    }

    /**
     * Normalisiert einen Winkel in das Intervall [-pi/4, pi/4] für 90°-symmetrische Rechtecke.
     */
    public static double normalizeAnglePiOver2(double angle) {
        double halfPi = Math.PI / 2.0;
        double a = angle % halfPi;
        if (a > Math.PI / 4.0) {
            a -= halfPi;
        } else if (a < -Math.PI / 4.0) {
            a += halfPi;
        }
        return a;
    }

    public static double normalizeAngleHalfPi(double angle) {
        double a = angle % Math.PI;
        if (a > Math.PI / 2.0) {
            a -= Math.PI;
        } else if (a < -Math.PI / 2.0) {
            a += Math.PI;
        }
        return a;
    }

    public double getDominantAngleRad() {
        return dominantAngleRad;
    }

    public void setDominantAngleRad(double dominantAngleRad) {
        this.dominantAngleRad = normalizeAngleHalfPi(dominantAngleRad);
    }

    public double getRectangularity() {
        return rectangularity;
    }

    public double getShadowScore() {
        return shadowScore;
    }

    public void setShadowScore(double shadowScore) {
        this.shadowScore = Math.max(0.0, Math.min(1.0, shadowScore));
    }

    public double getEdgeStrength() {
        return edgeStrength;
    }

    public void setEdgeStrength(double edgeStrength) {
        this.edgeStrength = Math.max(0.0, Math.min(1.0, edgeStrength));
    }

    public double getNeighborhoodScore() {
        return neighborhoodScore;
    }

    public void setNeighborhoodScore(double neighborhoodScore) {
        this.neighborhoodScore = Math.max(0.0, Math.min(1.0, neighborhoodScore));
    }

    public double getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(double confidenceScore) {
        this.confidenceScore = Math.max(0.0, Math.min(1.0, confidenceScore));
    }

    @Override
    public String toString() {
        return String.format(
            "BuildingContour{area=%d px², corners=%d, angle=%.1f°, shadow=%.2f, neighbor=%.2f, conf=%.2f}",
            getArea(), corners.size(), Math.toDegrees(dominantAngleRad), shadowScore, neighborhoodScore, confidenceScore
        );
    }
}