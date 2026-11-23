// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.plugins.buildingdetector.ImageProcessor.DetectionConfig;

/** Bildverarbeitungs-Pipeline auf synthetischen Szenen mit bekannter Geometrie. */
class ImageProcessorTest {

    private static List<BuildingContour> detect(BufferedImage image, Rectangle roi) {
        DetectionConfig config = new DetectionConfig();
        config.regionOfInterest = roi;
        return new ImageProcessor().detectBuildings(image, config);
    }

    private static List<String> describe(List<BuildingContour> contours, int dx, int dy) {
        List<String> out = new ArrayList<>();
        for (BuildingContour c : contours) {
            StringBuilder sb = new StringBuilder();
            for (Point2D.Double p : c.getExactCorners()) {
                sb.append(String.format("(%.3f,%.3f)", p.x + dx, p.y + dy));
            }
            out.add(sb.toString());
        }
        out.sort(null);
        return out;
    }

    @Test
    void nullImageYieldsNoBuildings() {
        assertTrue(new ImageProcessor().detectBuildings(null, new DetectionConfig()).isEmpty());
    }

    @Test
    void findsEveryBuildingInTheScene() {
        SyntheticScene scene = SyntheticScene.randomGrid(4, 3, 1, 120, 225, true);
        List<BuildingContour> detected = detect(scene.image, null);
        assertEquals(scene.truth.size(), detected.size());
        for (double[][] building : scene.truth) {
            assertNotNull(SyntheticScene.nearest(detected, building, 6.0), "jedes Soll-Gebäude hat eine Detektion");
        }
    }

    /**
     * Regression: Der separierte Gauß-Kern [1 4 7 4 1] hat die Gewichtssumme 289. Mit dem Divisor 273
     * überschritt das geglättete Bild 255, das Schatten-Histogramm lief über und die Erkennung lieferte
     * stillschweigend eine leere Liste.
     */
    @Test
    void brightSceneDoesNotOverflowTheSmoothedImage() {
        SyntheticScene scene = SyntheticScene.randomGrid(3, 2, 2, 150, 250, true);
        assertEquals(scene.truth.size(), detect(scene.image, null).size());
    }

    @Test
    void roiResultEqualsDetectionOnCroppedImageShiftedBack() {
        SyntheticScene scene = SyntheticScene.randomGrid(4, 3, 3, 120, 225, true);
        Rectangle roi = new Rectangle(190, 60, 420, 330);

        BufferedImage crop = new BufferedImage(roi.width, roi.height, BufferedImage.TYPE_INT_RGB);
        crop.getGraphics().drawImage(scene.image.getSubimage(roi.x, roi.y, roi.width, roi.height), 0, 0, null);

        List<String> viaRoi = describe(detect(scene.image, roi), 0, 0);
        List<String> viaCrop = describe(detect(crop, null), roi.x, roi.y);

        assertFalse(viaRoi.isEmpty());
        assertEquals(viaCrop, viaRoi, "ROI-Lauf liefert Bildkoordinaten des Gesamtbildes");
    }

    @Test
    void buildingsOutsideTheRoiAreNotReturned() {
        SyntheticScene scene = SyntheticScene.randomGrid(4, 3, 4, 120, 225, true);
        Rectangle roi = new Rectangle(190, 60, 420, 330);
        List<BuildingContour> inRoi = detect(scene.image, roi);
        assertTrue(inRoi.size() < detect(scene.image, null).size());
        for (BuildingContour c : inRoi) {
            assertTrue(roi.contains(c.getBoundingBox()), "Kontur liegt vollständig im ROI: " + c.getBoundingBox());
        }
    }

    @Test
    void roiIsClippedToTheImageAndDegenerateRoisYieldNothing() {
        SyntheticScene scene = SyntheticScene.randomGrid(3, 2, 5, 120, 225, true);
        int w = scene.image.getWidth();
        int h = scene.image.getHeight();
        Rectangle imageBounds = new Rectangle(0, 0, w, h);

        List<BuildingContour> partlyOutside = detect(scene.image, new Rectangle(-80, -50, 420, 330));
        assertFalse(partlyOutside.isEmpty());
        for (BuildingContour c : partlyOutside) {
            assertTrue(imageBounds.contains(c.getBoundingBox()));
        }
        assertTrue(detect(scene.image, new Rectangle(w + 50, h + 50, 100, 100)).isEmpty(), "ROI außerhalb des Bildes");
        assertTrue(detect(scene.image, new Rectangle(10, 10, 3, 3)).isEmpty(), "ROI kleiner als ein Gebäude");
        assertTrue(detect(scene.image, new Rectangle(10, 10, 0, 0)).isEmpty(), "leeres ROI");
    }

    /**
     * Die Umrisse dürfen weder systematisch zu klein noch versetzt sein. Vor der Kantenverfeinerung
     * waren alle Seitenlängen um rund 2,9 px zu kurz und die Kontur um 0,5 px versetzt.
     */
    @Test
    void outlinesAreUnbiasedAtSubPixelLevel() {
        for (boolean shadows : new boolean[] {false, true}) {
            double sumLong = 0, sumShort = 0, sumDx = 0, sumDy = 0, sumSq = 0;
            int n = 0;
            for (long seed = 11; seed <= 12; seed++) {
                SyntheticScene scene = SyntheticScene.randomGrid(5, 4, seed, 120, 225, shadows);
                DetectionConfig config = new DetectionConfig();
                config.alignWithNeighbors = false;
                List<BuildingContour> detected = new ImageProcessor().detectBuildings(scene.image, config);
                for (double[][] building : scene.truth) {
                    BuildingContour match = SyntheticScene.nearest(detected, building, 6.0);
                    assertNotNull(match);
                    double[][] corners = SyntheticScene.toContinuous(match);
                    double[] sides = SyntheticScene.sideLengths(corners);
                    double[] truthSides = SyntheticScene.sideLengths(building);
                    double[] c = SyntheticScene.centroid(corners);
                    double[] tc = SyntheticScene.centroid(building);
                    sumLong += sides[0] - truthSides[0];
                    sumShort += sides[1] - truthSides[1];
                    sumDx += c[0] - tc[0];
                    sumDy += c[1] - tc[1];
                    sumSq += SyntheticScene.meanSquaredCornerError(corners, building);
                    n++;
                }
            }
            String label = shadows ? " (mit Schatten)" : " (ohne Schatten)";
            assertEquals(0.0, sumLong / n, 0.5, "mittlerer Fehler der langen Seite in px" + label);
            assertEquals(0.0, sumShort / n, 0.5, "mittlerer Fehler der kurzen Seite in px" + label);
            assertEquals(0.0, sumDx / n, 0.25, "mittlerer Versatz in x in px" + label);
            assertEquals(0.0, sumDy / n, 0.25, "mittlerer Versatz in y in px" + label);
            assertTrue(Math.sqrt(sumSq / n) < 0.6, "Eckpunkt-RMSE " + Math.sqrt(sumSq / n) + " px" + label);
        }
    }

    /** Eckpunkte dürfen nicht auf ganze Pixel gerundet werden: gedrehte Umrisse bleiben exakte Rechtecke. */
    @Test
    void detectedOutlinesAreExactRectangles() {
        SyntheticScene scene = SyntheticScene.randomGrid(4, 3, 6, 120, 225, true);
        List<BuildingContour> detected = detect(scene.image, null);
        assertFalse(detected.isEmpty());
        for (BuildingContour contour : detected) {
            List<Point2D.Double> c = contour.getExactCorners();
            assertEquals(4, c.size());
            for (int i = 0; i < 4; i++) {
                Point2D.Double prev = c.get((i + 3) % 4);
                Point2D.Double curr = c.get(i);
                Point2D.Double next = c.get((i + 1) % 4);
                double ux = prev.x - curr.x, uy = prev.y - curr.y;
                double vx = next.x - curr.x, vy = next.y - curr.y;
                double cos = (ux * vx + uy * vy) / (Math.hypot(ux, uy) * Math.hypot(vx, vy));
                assertEquals(0.0, cos, 1e-9, "Innenwinkel exakt 90°");
            }
        }
    }

    @Test
    void closelySpacedBuildingsAreSeparatedAndDoNotOverlap() {
        for (int gap : new int[] {2, 3, 5}) {
            List<double[][]> truth = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                truth.add(SyntheticScene.rectangle(120 + i * (40 + gap), 150, 40, 30, 0));
            }
            SyntheticScene scene = SyntheticScene.render(truth, 600, 300, 120, 225, 3, false);
            List<BuildingContour> detected = detect(scene.image, null);
            assertEquals(truth.size(), detected.size(), "Lücke " + gap + " px");

            for (int i = 0; i < detected.size(); i++) {
                double[] sides = SyntheticScene.sideLengths(SyntheticScene.toContinuous(detected.get(i)));
                assertEquals(40.0, sides[0], 1.2, "Länge bei Lücke " + gap + " px");
                assertEquals(30.0, sides[1], 1.2, "Breite bei Lücke " + gap + " px");
                for (int j = i + 1; j < detected.size(); j++) {
                    Area intersection = toArea(detected.get(i));
                    intersection.intersect(toArea(detected.get(j)));
                    assertTrue(intersection.isEmpty(), "Nachbargebäude überlappen bei Lücke " + gap + " px");
                }
            }
        }
    }

    private static Area toArea(BuildingContour contour) {
        Path2D.Double path = new Path2D.Double();
        List<Point2D.Double> corners = contour.getExactCorners();
        path.moveTo(corners.get(0).x, corners.get(0).y);
        for (int i = 1; i < corners.size(); i++) {
            path.lineTo(corners.get(i).x, corners.get(i).y);
        }
        path.closePath();
        return new Area(path);
    }
}
