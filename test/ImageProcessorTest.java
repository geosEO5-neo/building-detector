// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
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
        config.minBuildingAreaM2 = SyntheticScene.MIN_BUILDING_AREA_M2;
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
                config.minBuildingAreaM2 = SyntheticScene.MIN_BUILDING_AREA_M2;
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

    /** Dachfarbe mit gleichem Farbton, aber anderer Helligkeit (Sonnen- und Schattenseite desselben Dachs). */
    private static Color shade(double factor) {
        return new Color((int) Math.round(210 * factor), (int) Math.round(128 * factor), (int) Math.round(98 * factor));
    }

    /** Ein Gebäude 80 x 50 px, gedreht, dessen Dach entlang des Firsts in Teilflächen zerfällt. */
    private static SyntheticScene roofScene(double[][][] localFaces, Color[] colors) {
        double cx = 150, cy = 120, angle = 20;
        List<double[][]> truth = new ArrayList<>();
        truth.add(SyntheticScene.rectangle(cx, cy, 80, 50, angle));
        List<double[][]> faces = new ArrayList<>();
        List<Color> faceColors = new ArrayList<>(List.of(colors));
        for (double[][] face : localFaces) {
            faces.add(SyntheticScene.place(cx, cy, angle, face));
        }
        // Eine wirklich dunkle Fläche abseits des Gebäudes (Baumschatten). Ohne sie wäre die Schattenseite des
        // Dachs das Dunkelste im Bild, und die Kontrastspreizung würde sie zum Schatten erklären.
        faces.add(SyntheticScene.rectangle(60, 200, 70, 40, 0));
        faceColors.add(new Color(25, 25, 30));
        // Heller Untergrund (Kies, Beton), damit sich jede Dachseite vom Boden abhebt
        return SyntheticScene.renderFaces(truth, faces, faceColors, 300, 240, 200, 7, true);
    }

    private static final double[][][] GABLE_FACES = {
        {{-40, -25}, {40, -25}, {40, 0}, {-40, 0}},
        {{-40, 0}, {40, 0}, {40, 25}, {-40, 25}}};

    /**
     * Walmdach mit kurzem First: Ein Giebeldreieck und eine Hauptfläche allein füllen ihr Hüllrechteck nur
     * zur Hälfte. Die Dreiecke können deshalb erst dazukommen, nachdem die beiden Hauptflächen vereint sind.
     */
    private static final double[][][] HIP_FACES = {
        {{-40, -25}, {40, -25}, {10, 0}, {-10, 0}},
        {{-40, 25}, {40, 25}, {10, 0}, {-10, 0}},
        {{-40, -25}, {-40, 25}, {-10, 0}},
        {{40, -25}, {40, 25}, {10, 0}}};

    private static void assertWholeRoof(SyntheticScene scene, String what) {
        List<BuildingContour> detected = detect(scene.image, null);
        assertEquals(1, detected.size(), what + ": genau ein Gebäude statt einzelner Dachflächen");
        double[][] outline = SyntheticScene.toContinuous(detected.get(0));
        double[] sides = SyntheticScene.sideLengths(outline);
        assertEquals(80.0, sides[0], 1.5, what + ": Länge des ganzen Dachs");
        assertEquals(50.0, sides[1], 1.5, what + ": Breite des ganzen Dachs");
        double[] centre = SyntheticScene.centroid(outline);
        double[] expected = SyntheticScene.centroid(scene.truth.get(0));
        assertEquals(expected[0], centre[0], 1.0, what + ": Lage x");
        assertEquals(expected[1], centre[1], 1.0, what + ": Lage y");
        assertTrue(detected.get(0).getRectangularity() > 0.85,
            what + ": alle Dachflächen gehören zum Gebäude, Rechteckigkeit " + detected.get(0).getRectangularity());
    }

    @Test
    void sunlitAndShadedFaceOfAGableRoofFormOneBuilding() {
        assertWholeRoof(roofScene(GABLE_FACES, new Color[] {shade(1.0), shade(0.6)}), "Satteldach");
    }

    @Test
    void fourFacesOfAHipRoofFormOneBuilding() {
        assertWholeRoof(roofScene(HIP_FACES, new Color[] {shade(1.0), shade(0.6), shade(0.85), shade(0.72)}), "Walmdach");
    }

    /** Gegenprobe: Flächen mit deutlich anderem Farbton gehören nicht zum selben Dach. */
    @Test
    void adjacentRoofsOfDifferentColourStaySeparate() {
        SyntheticScene scene = roofScene(GABLE_FACES, new Color[] {shade(1.0), new Color(70, 90, 150)});
        List<BuildingContour> detected = detect(scene.image, null);
        assertEquals(2, detected.size(), "zwei verschiedenfarbige Flächen bleiben zwei Objekte");
        for (BuildingContour contour : detected) {
            double[] sides = SyntheticScene.sideLengths(SyntheticScene.toContinuous(contour));
            assertEquals(80.0, sides[0], 1.5);
            assertEquals(25.0, sides[1], 1.5);
        }
    }

    /** Bei Überlappung bleibt das größere Objekt: Ein Aufbau auf dem Dach verdrängt nicht das Gebäude. */
    @Test
    void objectOnTheRoofDoesNotReplaceTheBuilding() {
        List<double[][]> truth = new ArrayList<>();
        truth.add(SyntheticScene.rectangle(150, 120, 90, 64, 15));
        List<double[][]> faces = new ArrayList<>(truth);
        faces.add(SyntheticScene.rectangle(150, 120, 34, 22, 15)); // z. B. Solaranlage, 47 m² bei 0,25 m/px
        List<Color> colors = List.of(new Color(225, 220, 213), new Color(120, 130, 170));
        SyntheticScene scene = SyntheticScene.renderFaces(truth, faces, colors, 300, 240, 120, 9, true);

        List<BuildingContour> detected = detect(scene.image, null);
        assertEquals(1, detected.size());
        double[] sides = SyntheticScene.sideLengths(SyntheticScene.toContinuous(detected.get(0)));
        assertEquals(90.0, sides[0], 1.5);
        assertEquals(64.0, sides[1], 1.5);
    }

    @Test
    void defaultMinimumAreaAndAspectRatioRejectImplausibleObjects() {
        List<double[][]> truth = new ArrayList<>();
        truth.add(SyntheticScene.rectangle(90, 80, 40, 30, 0));    // 75 m² bei 0,25 m/px: unter dem Standardwert
        truth.add(SyntheticScene.rectangle(250, 80, 60, 40, 0));   // 150 m²: plausibles Gebäude
        truth.add(SyntheticScene.rectangle(200, 200, 200, 30, 0)); // 375 m², aber Seitenverhältnis 6,7: Streifen
        SyntheticScene scene = SyntheticScene.render(truth, 420, 280, 120, 225, 3, false);

        List<BuildingContour> withDefaults = new ImageProcessor().detectBuildings(scene.image, new DetectionConfig());
        assertEquals(1, withDefaults.size(), "nur das plausible Gebäude");
        assertNotNull(SyntheticScene.nearest(withDefaults, truth.get(1), 3.0));

        // Die Mindestfläche bleibt einstellbar: mit kleinerem Wert kommt das kleine Gebäude hinzu
        List<BuildingContour> smallAllowed = detect(scene.image, null);
        assertEquals(2, smallAllowed.size());
        assertNotNull(SyntheticScene.nearest(smallAllowed, truth.get(0), 3.0));
        assertEquals(DetectionConfig.DEFAULT_MIN_BUILDING_AREA_M2, new DetectionConfig().minBuildingAreaM2);
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
