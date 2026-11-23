// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.buildingdetector.ImageProcessor.Point;

/** Geometrie der Gebäudekontur: Subpixelgenauigkeit, Verschiebung, Drehung. */
class BuildingContourTest {

    private static final double EPS = 1e-9;

    private static List<Point2D.Double> exactRectangle() {
        List<Point2D.Double> corners = new ArrayList<>();
        for (double[] c : SyntheticScene.rectangle(100.25, 80.75, 40.5, 25.25, 17.0)) {
            corners.add(new Point2D.Double(c[0], c[1]));
        }
        return corners;
    }

    @Test
    void exactCornersAreNotRoundedToWholePixels() {
        BuildingContour contour = BuildingContour.fromExactCorners(exactRectangle(), null, 0.0, 1.0);
        List<Point2D.Double> expected = exactRectangle();
        for (int i = 0; i < 4; i++) {
            assertEquals(expected.get(i).x, contour.getExactCorners().get(i).x, EPS);
            assertEquals(expected.get(i).y, contour.getExactCorners().get(i).y, EPS);
            // Die ganzzahlige Sicht bleibt für bestehende Aufrufer erhalten
            assertEquals(Math.round(expected.get(i).x), contour.getCorners().get(i).x);
            assertEquals(Math.round(expected.get(i).y), contour.getCorners().get(i).y);
        }
        assertEquals(40.5 * 25.25, contour.getExactArea(), 1e-6, "Fläche nach Gaußscher Trapezformel");
    }

    @Test
    void exactCornersAreDefensiveCopies() {
        BuildingContour contour = BuildingContour.fromExactCorners(exactRectangle(), null, 0.0, 1.0);
        contour.getExactCorners().get(0).x += 1000.0;
        assertEquals(exactRectangle().get(0).x, contour.getExactCorners().get(0).x, EPS);
    }

    @Test
    void translateMovesCornersAndBoundaryExactlyOnce() {
        // Eck- und Randpunktliste teilen sich hier dieselben Point-Instanzen
        List<Point> shared = Arrays.asList(new Point(10, 20), new Point(50, 20), new Point(50, 40), new Point(10, 40));
        BuildingContour contour = new BuildingContour(shared);

        contour.translate(100, 200);

        assertEquals(110.0, contour.getExactCorners().get(0).x, EPS);
        assertEquals(220.0, contour.getExactCorners().get(0).y, EPS);
        assertEquals(110, contour.getRawBoundaryPoints().get(0).x);
        assertEquals(220, contour.getRawBoundaryPoints().get(0).y);
        assertEquals(10, shared.get(0).x, "Punkte des Aufrufers bleiben unverändert");
        assertEquals(new Rectangle(110, 220, 40, 20), contour.getBoundingBox());
    }

    @Test
    void alignToAngleKeepsAnExactRectangle() {
        BuildingContour contour = BuildingContour.fromExactCorners(exactRectangle(), null, Math.toRadians(17.0), 1.0);
        Point2D.Double centerBefore = contour.getExactCenter();

        contour.alignToAngle(Math.toRadians(11.0));

        List<Point2D.Double> c = contour.getExactCorners();
        for (int i = 0; i < 4; i++) {
            Point2D.Double prev = c.get((i + 3) % 4);
            Point2D.Double curr = c.get(i);
            Point2D.Double next = c.get((i + 1) % 4);
            double dot = (prev.x - curr.x) * (next.x - curr.x) + (prev.y - curr.y) * (next.y - curr.y);
            assertEquals(0.0, dot, 1e-6, "Innenwinkel bleibt exakt 90°");
        }
        assertEquals(40.5 * 25.25, contour.getExactArea(), 1e-6, "Drehung ist flächentreu");
        assertEquals(centerBefore.x, contour.getExactCenter().x, 1e-9);
        assertEquals(centerBefore.y, contour.getExactCenter().y, 1e-9);
        double edgeAngle = Math.toDegrees(Math.atan2(c.get(1).y - c.get(0).y, c.get(1).x - c.get(0).x));
        assertEquals(11.0, edgeAngle, 1e-6);
        assertTrue(contour.isRectangular());
    }

    @Test
    void boundingBoxEnclosesSubPixelCorners() {
        BuildingContour contour = BuildingContour.fromExactCorners(exactRectangle(), null, 0.0, 1.0);
        Rectangle box = contour.getBoundingBox();
        for (Point2D.Double p : contour.getExactCorners()) {
            assertTrue(p.x >= box.x && p.x <= box.x + box.width && p.y >= box.y && p.y <= box.y + box.height);
        }
    }
}
