// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/**
 * Fernerkundungs-Bildprozessor für die semi-automatische Gebäudedetektion in Luftbildern.
 *
 * Implementiert:
 * 1. Spektrale Vegetationsmaskierung (Excess Green Index ExG) & Perzentil-Kontrastspreizung
 * 2. Canny-Kantendetektion (Gauß, Sobel-Gradienten, Non-Maximum Suppression, Hysterese)
 * 3. Kombination aus geschlossenen Konturregionen & homogener Dachflächen-Segmentierung
 * 4. Automatische Sonnenazimut-Schätzung & 3D-Schattenwurf-Verifikation
 * 5. Orientierte Minimum Bounding Box (OMBB via Konvexer Hülle & Rotating Calipers)
 * 6. Nachbarschafts-Topologie (Siedlungscluster-Analyse, Fluchtwinkel-Regularisierung, NMS)
 */
public class ImageProcessor {

    /**
     * Konfigurationsparameter für den Fernerkundungs-Workflow.
     */
    public static class DetectionConfig {
        /**
         * Standardwert der Mindestfläche. Auf echten Orthofotos (0,2 m/px) waren Erkennungen unter 80 m²
         * zu rund 96 % falsch (siehe "ant eval-real"); kleinere Werte sind möglich, liefern aber viele Fehltreffer.
         */
        public static final double DEFAULT_MIN_BUILDING_AREA_M2 = 80.0;

        public double contrastThreshold = 0.08;   // Empfindlichkeit für Canny-Kanten (0.03 - 0.20)
        public double minBuildingAreaM2 = DEFAULT_MIN_BUILDING_AREA_M2;   // Minimale Gebäudefläche in m² (im Dialog einstellbar)
        public double maxBuildingAreaM2 = 2500.0; // Maximale Gebäudefläche in m²
        public double metersPerPixel = 0.25;      // Bodenauflösung (GSD), wird dynamisch aus MapView gesetzt
        public double shadowWeight = 0.35;        // Gewicht des Schattenwurfs bei der 3D-Prüfung [0..1]
        public boolean alignWithNeighbors = true; // Gebäudefluchten an Nachbarschaft angleichen
        public boolean showDebugWindows = false;  // Debug-Ansicht für Zwischenschritte
        public Rectangle regionOfInterest = null; // Optionales Suchfenster in Bildkoordinaten (null = gesamtes Bild)
    }

    /**
     * Abwärtskompatibler Aufruf mit Standard-Parametern.
     */
    public List<BuildingContour> detectBuildings(BufferedImage aerialImage, double contrastThreshold) {
        DetectionConfig config = new DetectionConfig();
        config.contrastThreshold = contrastThreshold;
        return detectBuildings(aerialImage, config);
    }

    /**
     * Hauptpipeline zur fernerkundlichen Gebäudedetektion.
     *
     * Ist {@link DetectionConfig#regionOfInterest} gesetzt, läuft die gesamte Pipeline nur auf diesem
     * Bildausschnitt (in ROI-lokalen Koordinaten). Die zurückgegebenen Konturen liegen immer in den
     * Koordinaten des übergebenen Gesamtbildes.
     */
    public List<BuildingContour> detectBuildings(BufferedImage aerialImage, DetectionConfig config) {
        if (aerialImage == null) {
            return Collections.emptyList();
        }

        // Suchfenster (ROI) auf die Bildgrenzen beschneiden; ohne ROI wird das gesamte Bild verarbeitet.
        Rectangle imageBounds = new Rectangle(0, 0, aerialImage.getWidth(), aerialImage.getHeight());
        Rectangle roi = (config.regionOfInterest != null)
            ? config.regionOfInterest.intersection(imageBounds)
            : imageBounds;
        if (roi.isEmpty()) {
            return Collections.emptyList();
        }

        // Ab hier arbeitet die gesamte Pipeline in ROI-lokalen Koordinaten.
        int width = roi.width;
        int height = roi.height;
        double gsd = Math.max(0.03, config.metersPerPixel);

        // Umrechnung der geodätischen Flächengrenzen (m²) in Pixel² anhand der aktuellen GSD
        int minAreaPx = Math.max(36, (int) Math.round(config.minBuildingAreaM2 / (gsd * gsd)));
        int maxAreaPx = Math.min((width * height) / 3, (int) Math.round(config.maxBuildingAreaM2 / (gsd * gsd)));

        try {
            // 1. Spektrale Zerlegung: Luminanz, Vegetationsmaske (ExG) und Schattenmaske extrahieren
            int[][] luminance = new int[width][height];
            boolean[][] vegetationMask = new boolean[width][height];
            int[] rgb = extractSpectralChannels(aerialImage, roi, luminance, vegetationMask);

            // 2. Robuste Perzentil-Kontrastspreizung (2% - 98%) & 5x5 Gauß-Glättung
            int[][] enhanced = stretchContrastPercentile(luminance, width, height, 0.02, 0.98);
            int[][] smoothed = applyGaussian5x5(enhanced, width, height);

            // 3. Globale Schattenmaske für 3D-Gebäudeverifikation berechnen
            boolean[][] shadowMask = computeShadowMask(smoothed, vegetationMask, width, height);

            // 4. Canny-Kantendetektion (Gradienten, NMS, Hysterese)
            double[][] gradMag = new double[width][height];
            double[][] gradDir = new double[width][height];
            boolean[][] edges = performCannyEdgeDetection(
                smoothed, vegetationMask, width, height, config.contrastThreshold, gradMag, gradDir
            );

            // 5. Morphologisches Schließen (überbrückt kleine Lücken in Dachkanten)
            boolean[][] closedEdges = morphologicalClose(edges, width, height);

            if (config.showDebugWindows) {
                showDebugImage(booleanMaskToImage(closedEdges, shadowMask, vegetationMask, width, height),
                    "Fernerkundungs-Maske (Weiß=Kante, Blau=Schatten, Grün=Vegetation)");
            }

            // 6. Globale Schätzung der Sonnen-/Schattenrichtung aus Kanten-Schatten-Übergängen
            double[] shadowVector = estimateDominantShadowDirection(closedEdges, shadowMask, gradDir, width, height);

            // 7. Kandidaten-Extraktion:
            //    a) Von Kanten umschlossene homogene Dachflächen
            //    b) Lokale adaptive Dach-Segmentierung (helle/farbhomogene Nicht-Vegetations-Objekte)
            List<BuildingContour> candidates = extractRoofCandidates(
                rgb, smoothed, closedEdges, vegetationMask, shadowMask, gradMag,
                width, height, minAreaPx, maxAreaPx, cannyLowThreshold(config.contrastThreshold), gsd
            );

            // 8. Schattenwurf-Bewertung (3D-Verifikation entlang des geschätzten Schattenvektors)
            double shadowCheckDistPx = Math.max(4.0, Math.min(25.0, 3.5 / gsd));
            for (BuildingContour contour : candidates) {
                double sScore = evaluateShadowCast(contour, shadowMask, smoothed, shadowVector, shadowCheckDistPx, width, height);
                contour.setShadowScore(sScore);
            }

            // 9. Nachbarschaftsverhältnisse:
            //    Räumliches Clustering, Fluchtwinkel-Konsens (Azimut-Harmonisierung) & Konfidenz-Filterung
            double neighborRadiusPx = Math.max(40.0, 45.0 / gsd); // ca. 45 Meter Nachbarschaftsradius
            List<BuildingContour> verified = evaluateNeighborhoodAndFilter(
                candidates, neighborRadiusPx, config.shadowWeight, config.alignWithNeighbors
            );

            // 10. Topologische Bereinigung (Verschachtelte Konturen & Non-Maximum Suppression bei Überlappung)
            List<BuildingContour> result = resolveOverlapsAndNesting(verified);

            // 11. Rücktransformation von ROI-lokalen Koordinaten in die Koordinaten des Gesamtbildes
            if (roi.x != 0 || roi.y != 0) {
                for (BuildingContour contour : result) {
                    contour.translate(roi.x, roi.y);
                }
            }
            return result;

        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * Extrahiert Luminanz und markiert Vegetation mittels Excess Green Index (ExG = 2G - R - B)
     * sowie hoher Grün-Sättigung. Gelesen wird nur der Ausschnitt {@code roi}; die Ergebnis-Arrays
     * sind ROI-lokal indiziert.
     */
    private int[] extractSpectralChannels(BufferedImage img, Rectangle roi, int[][] luminance, boolean[][] vegetationMask) {
        int width = roi.width;
        int height = roi.height;
		int[] pixels = img.getRGB(roi.x, roi.y, width, height, null, 0, width);

        for (int i = 0, y = 0; y < height; y++) {
            for (int x = 0; x < width; x++, i++) {
                int rgb = pixels[i];
				
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
				
                luminance[x][y] = (int) Math.round(
					0.299 * r + 0.587 * g + 0.114 * b
				);

                // Excess Green Index (ExG) + Normalized Green-Red Difference (NGRDI)
                int exG = 2 * g - r - b;
                double ngrdi = (g + r > 15) 
					? (double) (g - r) / (g + r) 
					: 0.0;

				vegetationMask[x][y] =
					exG > 28 &&
					ngrdi > 0.06 &&
					g > 45 &&
					luminance[x][y] < 210;
            }
        }
        return pixels;
    }

    /**
     * Robuste Kontrastspreizung über das 2%- und 98%-Quantil des Histogramms.
     */
    private int[][] stretchContrastPercentile(int[][] gray, int width, int height, double lowPct, double highPct) {
        int[] hist = new int[256];
        int total = width * height;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                hist[gray[x][y]]++;
            }
        }

        int lowCut = (int) (total * lowPct);
        int highCut = (int) (total * highPct);

        int minVal = 0, sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += hist[i];
            if (sum >= lowCut) {
                minVal = i;
                break;
            }
        }

        int maxVal = 255;
        sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += hist[i];
            if (sum >= highCut) {
                maxVal = i;
                break;
            }
        }

        if (maxVal <= minVal + 5) {
            maxVal = Math.min(255, minVal + 6);
        }

        double scale = 255.0 / (maxVal - minVal);
        int[][] out = new int[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int v = (int) Math.round((gray[x][y] - minVal) * scale);
                out[x][y] = Math.max(0, Math.min(255, v));
            }
        }
        return out;
    }

    /**
     * 5x5 Gauß-Filter zur Rauschunterdrückung auf Dachtexturen.
     *
     * Separierbare Näherung des ursprünglichen 5x5-Kernels: zwei 1D-Durchläufe mit [1 4 7 4 1].
     * Das äußere Produkt hat die Gewichtssumme 17 * 17 = 289 (nicht 273 wie der nicht exakt
     * separierbare Originalkernel); nur mit diesem Divisor bleibt das Ergebnis in [0..255].
     */
    private int[][] applyGaussian5x5(int[][] src, int width, int height) {
		int[] kernel = {1, 4, 7, 4, 1};
		int	divisor = 289;

		int[][]	temp = new int[width][height];
		int[][]	result = new int[width][height];

		for (int y = 0; y < height; y++) {
			for (int x = 2; x < width - 2; x++) {
				int	sum = 0;
				for (int k = -2; k <= 2; k++) {
					sum += src[x + k][y] * kernel[k + 2];
				}
				temp[x][y] = sum;
			}
		}

		for (int x = 2; x < width - 2; x++) {
			for (int y = 2; y < height - 2; y++) {
				int	sum = 0;
				for (int k = -2; k <= 2; k++) {
					sum += temp[x][y + k] * kernel[k + 2];
				}
				result[x][y] = sum / divisor;
			}
		}
		
		return result;
    }

    /**
     * Erstellt eine binäre Schattenmaske anhand des unteren Luminanz-Quantils (dunkle, homogene Schlagschatten).
     */
    private boolean[][] computeShadowMask(int[][] smoothed, boolean[][] vegetationMask, int width, int height) {
        int[] hist = new int[256];
        int count = 0;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (!vegetationMask[x][y]) {
                    hist[smoothed[x][y]]++;
                    count++;
                }
            }
        }
        // Untere 14% der Nicht-Vegetations-Pixel gelten als potenzielle Schlagschatten
        int target = (int) (count * 0.14);
        int shadowThresh = 45;
        int acc = 0;
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            if (acc >= target) {
                shadowThresh = Math.min(75, Math.max(25, i));
                break;
            }
        }

        boolean[][] shadow = new boolean[width][height];
        for (int x = 1; x < width - 1; x++) {
            for (int y = 1; y < height - 1; y++) {
                if (smoothed[x][y] <= shadowThresh && !vegetationMask[x][y]) {
                    shadow[x][y] = true;
                }
            }
        }
        return shadow;
    }

    /**
     * Vollständiger Canny-Kantendetektor:
     * Sobel-Gradient -> Non-Maximum Suppression (1-Pixel-Dünnung) -> Hysterese-Schwellenwert.
     */
    private boolean[][] performCannyEdgeDetection(
            int[][] smoothed, boolean[][] vegetationMask, int width, int height,
            double contrastThreshold, double[][] gradMag, double[][] gradDir) {

        double maxGrad = 1.0;
        for (int x = 1; x < width - 1; x++) {
            for (int y = 1; y < height - 1; y++) {
                if (vegetationMask[x][y]) {
                    continue;
                }
                double gx = -smoothed[x - 1][y - 1] + smoothed[x + 1][y - 1]
                          - 2.0 * smoothed[x - 1][y] + 2.0 * smoothed[x + 1][y]
                          - smoothed[x - 1][y + 1] + smoothed[x + 1][y + 1];

                double gy = -smoothed[x - 1][y - 1] - 2.0 * smoothed[x][y - 1] - smoothed[x + 1][y - 1]
                          + smoothed[x - 1][y + 1] + 2.0 * smoothed[x][y + 1] + smoothed[x + 1][y + 1];

                double mag = Math.hypot(gx, gy);
                gradMag[x][y] = mag;
                gradDir[x][y] = Math.atan2(gy, gx);
                if (mag > maxGrad) {
                    maxGrad = mag;
                }
            }
        }

        // Normierung auf [0..255] und Non-Maximum Suppression entlang der Gradientenrichtung
        double[][] nms = new double[width][height];
        for (int x = 1; x < width - 1; x++) {
            for (int y = 1; y < height - 1; y++) {
                gradMag[x][y] = (gradMag[x][y] / maxGrad) * 255.0;
            }
        }

        for (int x = 1; x < width - 1; x++) {
            for (int y = 1; y < height - 1; y++) {
                double mag = gradMag[x][y];
                if (mag < 5.0) continue;

                double angle = gradDir[x][y] * (180.0 / Math.PI);
                if (angle < 0) angle += 180.0;

                double q = 255.0, r = 255.0;
                if ((angle >= 0 && angle < 22.5) || (angle >= 157.5 && angle <= 180)) {
                    q = gradMag[x + 1][y];
                    r = gradMag[x - 1][y];
                } else if (angle >= 22.5 && angle < 67.5) {
                    q = gradMag[x + 1][y + 1];
                    r = gradMag[x - 1][y - 1];
                } else if (angle >= 67.5 && angle < 112.5) {
                    q = gradMag[x][y + 1];
                    r = gradMag[x][y - 1];
                } else if (angle >= 112.5 && angle < 157.5) {
                    q = gradMag[x - 1][y + 1];
                    r = gradMag[x + 1][y - 1];
                }

                if (mag >= q && mag >= r) {
                    nms[x][y] = mag;
                }
            }
        }

        // Hysterese-Schwellenwerte
        double highThresh = cannyHighThreshold(contrastThreshold);
        double lowThresh = cannyLowThreshold(contrastThreshold);

        boolean[][] edges = new boolean[width][height];
        Deque<Point> queue = new ArrayDeque<>();

        for (int x = 1; x < width - 1; x++) {
            for (int y = 1; y < height - 1; y++) {
                if (nms[x][y] >= highThresh) {
                    edges[x][y] = true;
                    queue.add(new Point(x, y));
                }
            }
        }

        while (!queue.isEmpty()) {
            Point p = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    int nx = p.x + dx;
                    int ny = p.y + dy;
                    if (nx > 0 && nx < width - 1 && ny > 0 && ny < height - 1) {
                        if (!edges[nx][ny] && nms[nx][ny] >= lowThresh) {
                            edges[nx][ny] = true;
                            queue.add(new Point(nx, ny));
                        }
                    }
                }
            }
        }
        return edges;
    }

    /** Oberer Hysterese-Schwellenwert des Canny-Detektors auf der normierten Gradientenskala [0..255]. */
    private static double cannyHighThreshold(double contrastThreshold) {
        return Math.max(18.0, Math.min(180.0, contrastThreshold * 255.0 * 1.35));
    }

    /** Unterer Hysterese-Schwellenwert des Canny-Detektors auf der normierten Gradientenskala [0..255]. */
    private static double cannyLowThreshold(double contrastThreshold) {
        return cannyHighThreshold(contrastThreshold) * 0.48;
    }

    /**
     * Morphologisches Schließen (3x3 Dilatation gefolgt von 3x3 Erosion) zum Schließen kleiner Lücken.
     */
    private boolean[][] morphologicalClose(boolean[][] mask, int width, int height) {
        boolean[][] dilated = new boolean[width][height];
        for (int x = 1; x < width - 1; x++) {
            for (int y = 1; y < height - 1; y++) {
                if (mask[x][y]) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            dilated[x + dx][y + dy] = true;
                        }
                    }
                }
            }
        }
        boolean[][] closed = new boolean[width][height];
        for (int x = 1; x < width - 1; x++) {
            for (int y = 1; y < height - 1; y++) {
                if (!dilated[x][y]) continue;
                boolean allTrue = true;
                for (int dx = -1; dx <= 1 && allTrue; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        if (!dilated[x + dx][y + dy]) {
                            allTrue = false;
                            break;
                        }
                    }
                }
                closed[x][y] = allTrue;
            }
        }
        return closed;
    }

    /**
     * Schätzt den globalen Schattenwurf-Einheitsvektor (Richtung von der hellen Dachkante in den Schlagschatten).
     */
    private double[] estimateDominantShadowDirection(
            boolean[][] edges, boolean[][] shadowMask, double[][] gradDir, int width, int height) {

        double sumX = 0.0;
        double sumY = 0.0;
        int votes = 0;

        for (int x = 4; x < width - 4; x += 2) {
            for (int y = 4; y < height - 4; y += 2) {
                if (!edges[x][y]) continue;
                // Gradient zeigt von dunkel nach hell; -Gradient zeigt von hell (Dach) nach dunkel (Schatten)
                double dirToDark = gradDir[x][y] + Math.PI;
                int sx = x + (int) Math.round(3.0 * Math.cos(dirToDark));
                int sy = y + (int) Math.round(3.0 * Math.sin(dirToDark));
                if (sx >= 0 && sx < width && sy >= 0 && sy < height && shadowMask[sx][sy]) {
                    sumX += Math.cos(dirToDark);
                    sumY += Math.sin(dirToDark);
                    votes++;
                }
            }
        }

        double norm = Math.hypot(sumX, sumY);
        if (votes < 25 || norm < 1e-3) {
            // Standard-Fallback (Nord-West / Nord-Ost kombinierte Prüfung, hier 135° bzw. -45° im Bild)
            return new double[]{0.7071, -0.7071};
        }
        return new double[]{sumX / norm, sumY / norm};
    }

    /** Kleinste Teilfläche (m²), die beim Zusammenfassen von Dachflächen berücksichtigt wird. */
    private static final double MIN_SEGMENT_AREA_M2 = 2.0;
    /** Größte Breite (m) einer Trennlinie (First, Grat, Kehle), über die hinweg Dachflächen als benachbart gelten. */
    private static final double MAX_RIDGE_WIDTH_M = 0.8;
    /** Größter Abstand der mittleren Farbanteile r/(r+g+b), g/(r+g+b) zweier Flächen desselben Dachs. */
    private static final double MAX_CHROMA_DISTANCE = 0.030;
    /** Größtes Helligkeitsverhältnis zwischen besonnter und beschatteter Fläche desselben Dachs. */
    private static final double MAX_LUMINANCE_RATIO = 3.0;
    /** Mindest-Rechteckigkeit, die eine zusammengefasste Fläche behalten muss. */
    private static final double MIN_MERGED_RECTANGULARITY = 0.56;

    /** Zusammenhängende, homogene Teilfläche (z. B. eine Dachseite) aus dem Region-Growing. */
    private static final class RoofSegment {
        int area;
        long sumR;
        long sumG;
        long sumB;
        double edgeStrengthSum;
        int edgeHitCount;
        List<Point> boundary = new ArrayList<>();
        /** Kennung der Gruppe, zu der die Teilfläche nach dem Zusammenfassen gehört. */
        int group;

        double chromaR() {
            return (double) sumR / Math.max(1L, sumR + sumG + sumB);
        }

        double chromaG() {
            return (double) sumG / Math.max(1L, sumR + sumG + sumB);
        }

        double meanLuminance() {
            return (0.299 * sumR + 0.587 * sumG + 0.114 * sumB) / Math.max(1, area);
        }

        void absorb(RoofSegment other) {
            area += other.area;
            sumR += other.sumR;
            sumG += other.sumG;
            sumB += other.sumB;
            edgeStrengthSum += other.edgeStrengthSum;
            edgeHitCount += other.edgeHitCount;
            boundary.addAll(other.boundary);
        }
    }

    /**
     * Extrahiert Dachkandidaten in drei Schritten:
     * <ol>
     *   <li>Region-Growing: homogene Teilflächen, begrenzt von Kanten, Schatten und Vegetation.</li>
     *   <li>Zusammenfassen: Teilflächen, die nur durch eine schmale Kantenlinie (First, Grat) getrennt sind,
     *       farblich zusammenpassen und gemeinsam ein Rechteck füllen, bilden ein Dach. Ohne diesen Schritt
     *       zerfällt jedes Sattel- oder Walmdach in seine unterschiedlich beleuchteten Dachseiten.</li>
     *   <li>Je Gruppe die orientierte Minimum Bounding Box (OMBB) mittels Rotating Calipers.</li>
     * </ol>
     */
    private List<BuildingContour> extractRoofCandidates(
            int[] rgb, int[][] smoothed, boolean[][] closedEdges, boolean[][] vegetationMask, boolean[][] shadowMask,
            double[][] gradMag, int width, int height, int minAreaPx, int maxAreaPx, double minEdgeGradient, double gsd) {

        int minSegmentPx = Math.max(9, (int) Math.round(MIN_SEGMENT_AREA_M2 / (gsd * gsd)));
        minSegmentPx = Math.min(minSegmentPx, minAreaPx);

        // labels: 0 = keine Teilfläche; > 0 = Index in segments; LABEL_SMALL = zu kleine Fläche (Kamin, Dachfenster)
        int[][] labels = new int[width][height];
        List<RoofSegment> segments = new ArrayList<>();
        segments.add(null); // Index 0 bleibt frei

        // Das Suchfenster (ROI) ist bereits durch width/height abgedeckt; hier nur der Filterrand.
        int minX = 2;
        int minY = 2;
        int maxX = width - 2;
        int maxY = height - 2;

        int[] dx4 = {1, -1, 0, 0};
        int[] dy4 = {0, 0, 1, -1};
        int storeLimit = maxAreaPx * 2;

        for (int startX = minX; startX < maxX; startX += 2) {
            for (int startY = minY; startY < maxY; startY += 2) {
                if (labels[startX][startY] != 0 || closedEdges[startX][startY]
                        || vegetationMask[startX][startY] || shadowMask[startX][startY]) {
                    continue;
                }

                // Homogenes Region-Growing innerhalb der Canny-Kantenbarrieren
                int id = segments.size();
                RoofSegment segment = new RoofSegment();
                List<Point> regionPixels = new ArrayList<>();
                Deque<Point> queue = new ArrayDeque<>();

                double runningMean = smoothed[startX][startY];
                int count = 0;
                boolean touchesImageBorder = false;

                queue.add(new Point(startX, startY));
                labels[startX][startY] = id;

                while (!queue.isEmpty()) {
                    Point p = queue.poll();
                    count++;
                    // Zu große Regionen (Straße, Feld, Hintergrund) werden zu Ende geflutet, aber nicht gespeichert.
                    // Ein vorzeitiger Abbruch würde den Rest der Fläche später als eigene Kandidaten liefern.
                    boolean store = count <= storeLimit;
                    if (store) {
                        regionPixels.add(p);
                        int c = rgb[p.y * width + p.x];
                        segment.sumR += (c >> 16) & 0xFF;
                        segment.sumG += (c >> 8) & 0xFF;
                        segment.sumB += c & 0xFF;
                    }

                    boolean isBoundary = false;
                    for (int k = 0; k < 4; k++) {
                        int nx = p.x + dx4[k];
                        int ny = p.y + dy4[k];

                        if (nx <= minX || nx >= maxX - 1 || ny <= minY || ny >= maxY - 1) {
                            touchesImageBorder = true;
                            isBoundary = true;
                            continue;
                        }

                        if (closedEdges[nx][ny] || shadowMask[nx][ny] || vegetationMask[nx][ny]) {
                            isBoundary = true;
                            segment.edgeStrengthSum += gradMag[nx][ny];
                            segment.edgeHitCount++;
                            continue;
                        }

                        int lumDiff = Math.abs(smoothed[nx][ny] - (int) Math.round(runningMean));
                        int stepDiff = Math.abs(smoothed[nx][ny] - smoothed[p.x][p.y]);

                        if (lumDiff <= 26 && stepDiff <= 12) {
                            if (labels[nx][ny] == 0) {
                                labels[nx][ny] = id;
                                queue.add(new Point(nx, ny));
                                runningMean = (runningMean * count + smoothed[nx][ny]) / (count + 1.0);
                            }
                        } else {
                            isBoundary = true;
                            segment.edgeStrengthSum += gradMag[nx][ny];
                            segment.edgeHitCount++;
                        }
                    }

                    if (isBoundary && store) {
                        segment.boundary.add(p);
                    }
                }

                segment.area = count;
                boolean usable = !touchesImageBorder && count >= minSegmentPx && count <= maxAreaPx
                    && segment.boundary.size() >= 8;
                if (usable) {
                    segment.group = id;
                    segments.add(segment);
                } else {
                    // Kleine Flächen dürfen beim Nachbarschaftstest überbrückt werden, alles andere trennt.
                    int mark = (count < minSegmentPx && !touchesImageBorder) ? LABEL_SMALL : LABEL_REJECTED;
                    if (count <= storeLimit) {
                        for (Point q : regionPixels) {
                            labels[q.x][q.y] = mark;
                        }
                    } else {
                        relabel(labels, id, mark, width, height);
                    }
                }
            }
        }

        mergeRoofFaces(segments, labels, closedEdges, width, height, maxAreaPx, gsd);

        List<BuildingContour> candidates = new ArrayList<>();
        for (int id = 1; id < segments.size(); id++) {
            RoofSegment group = segments.get(id);
            if (group.group != id) {
                continue; // in einer anderen Gruppe aufgegangen
            }
            int areaPx = group.area;
            if (areaPx < minAreaPx || areaPx > maxAreaPx || group.boundary.size() < 12) {
                continue;
            }

            // Berechne die konvexe Hülle und die flächenminimale orientierte Bounding-Box (OMBB)
            BuildingContour contour = computeOrientedBoundingContour(
                group.boundary, areaPx, gradMag, width, height, minEdgeGradient);
            if (contour == null) {
                continue;
            }

            // Normierte Kantenstärke [0..1]
            double meanEdge = (group.edgeHitCount > 0) ? (group.edgeStrengthSum / (group.edgeHitCount * 255.0)) : 0.0;
            contour.setEdgeStrength(Math.min(1.0, meanEdge * 2.2));

            if (contour.isRectangular() && contour.getExactArea() >= minAreaPx && contour.getExactArea() <= maxAreaPx * 1.35) {
                candidates.add(contour);
            }
        }
        return candidates;
    }

    private static final int LABEL_SMALL = -1;
    private static final int LABEL_REJECTED = -2;

    private static void relabel(int[][] labels, int from, int to, int width, int height) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (labels[x][y] == from) {
                    labels[x][y] = to;
                }
            }
        }
    }

    /**
     * Fasst Teilflächen zu Dächern zusammen. Zwei Teilflächen gelten als Seiten desselben Dachs, wenn sie
     * <ul>
     *   <li>nur durch eine schmale Kantenlinie getrennt sind (gemeinsame Grenze ausreichend lang),</li>
     *   <li>ähnliche Farbanteile haben (die Helligkeit darf sich unterscheiden: Sonnen- und Schattenseite),</li>
     *   <li>und gemeinsam ein Rechteck ausreichend füllen.</li>
     * </ul>
     * Nach dem Aufruf steht in {@link RoofSegment#group} die Kennung der Gruppe; die Gruppe selbst
     * (Eintrag mit {@code group == Index}) enthält die aufsummierten Werte aller Mitglieder.
     */
    private void mergeRoofFaces(List<RoofSegment> segments, int[][] labels, boolean[][] closedEdges,
            int width, int height, int maxAreaPx, double gsd) {
        int maxGap = Math.max(2, (int) Math.round(MAX_RIDGE_WIDTH_M / gsd));

        // Gemeinsame Grenzlängen benachbarter Teilflächen: von jedem Pixel nach rechts und unten über
        // Kantenpixel (und sehr kleine Flächen) hinweg bis zur nächsten Teilfläche laufen.
        Map<Long, Integer> shared = new HashMap<>();
        int[] stepX = {1, 0};
        int[] stepY = {0, 1};
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int a = labels[x][y];
                if (a <= 0) {
                    continue;
                }
                for (int k = 0; k < 2; k++) {
                    for (int d = 1; d <= maxGap + 1; d++) {
                        int qx = x + d * stepX[k];
                        int qy = y + d * stepY[k];
                        if (qx >= width || qy >= height) {
                            break;
                        }
                        int b = labels[qx][qy];
                        if (b == a) {
                            break;
                        }
                        if (b > 0) {
                            long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
                            shared.merge(key, 1, Integer::sum);
                            break;
                        }
                        if (!(closedEdges[qx][qy] || b == LABEL_SMALL)) {
                            break; // Schatten, Vegetation oder verworfene Fläche trennt
                        }
                    }
                }
            }
        }

        List<long[]> pairs = new ArrayList<>(shared.size());
        for (Map.Entry<Long, Integer> e : shared.entrySet()) {
            pairs.add(new long[] {e.getValue(), e.getKey() >>> 32, e.getKey() & 0xFFFFFFFFL});
        }
        pairs.sort((p, q) -> p[0] != q[0] ? Long.compare(q[0], p[0]) : p[1] != q[1] ? Long.compare(p[1], q[1]) : Long.compare(p[2], q[2]));

        // Mehrere Durchläufe: Eine Zusammenfassung kann eine zuvor abgelehnte erst ermöglichen
        // (Walmdach: erst die beiden Hauptflächen, dann die Giebeldreiecke).
        boolean changed = true;
        for (int pass = 0; pass < 6 && changed; pass++) {
            changed = false;
            for (long[] pair : pairs) {
                int ga = findGroup(segments, (int) pair[1]);
                int gb = findGroup(segments, (int) pair[2]);
                if (ga == gb) {
                    continue;
                }
                RoofSegment a = segments.get(ga);
                RoofSegment b = segments.get(gb);
                if (pair[0] < Math.max(4.0, 0.25 * Math.sqrt(Math.min(a.area, b.area)))) {
                    continue;
                }
                if (a.area + b.area > maxAreaPx) {
                    continue;
                }
                if (Math.hypot(a.chromaR() - b.chromaR(), a.chromaG() - b.chromaG()) > MAX_CHROMA_DISTANCE) {
                    continue;
                }
                double lumA = Math.max(1.0, a.meanLuminance());
                double lumB = Math.max(1.0, b.meanLuminance());
                if (Math.max(lumA, lumB) / Math.min(lumA, lumB) > MAX_LUMINANCE_RATIO) {
                    continue;
                }
                List<Point> joint = new ArrayList<>(a.boundary.size() + b.boundary.size());
                joint.addAll(a.boundary);
                joint.addAll(b.boundary);
                double boxArea = minimumBoundingBoxArea(computeConvexHull(joint));
                if (boxArea <= 1.0 || (a.area + b.area) / boxArea < MIN_MERGED_RECTANGULARITY) {
                    continue;
                }
                // Die größere Gruppe nimmt die kleinere auf
                if (a.area >= b.area) {
                    a.absorb(b);
                    b.group = ga;
                } else {
                    b.absorb(a);
                    a.group = gb;
                }
                changed = true;
            }
        }
        for (int id = 1; id < segments.size(); id++) {
            segments.get(id).group = findGroup(segments, id);
        }
    }

    private static int findGroup(List<RoofSegment> segments, int id) {
        int g = id;
        while (segments.get(g).group != g) {
            g = segments.get(g).group;
        }
        return g;
    }

    /** Fläche des kleinsten orientierten Hüllrechtecks einer konvexen Hülle (Rotating Calipers). */
    private static double minimumBoundingBoxArea(List<Point> hull) {
        if (hull.size() < 3) {
            return 0.0;
        }
        double best = Double.MAX_VALUE;
        int n = hull.size();
        for (int i = 0; i < n; i++) {
            Point p1 = hull.get(i);
            Point p2 = hull.get((i + 1) % n);
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            double len = Math.hypot(dx, dy);
            if (len < 1e-3) {
                continue;
            }
            double cos = dx / len;
            double sin = dy / len;
            double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
            double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
            for (Point p : hull) {
                double u = p.x * cos + p.y * sin;
                double v = -p.x * sin + p.y * cos;
                minU = Math.min(minU, u);
                maxU = Math.max(maxU, u);
                minV = Math.min(minV, v);
                maxV = Math.max(maxV, v);
            }
            best = Math.min(best, Math.max(1.0, maxU - minU) * Math.max(1.0, maxV - minV));
        }
        return best;
    }

    /**
     * Berechnet über die konvexe Hülle (Andrew's Monotone Chain) und Rotating Calipers
     * das exakt orientierte minimale Hüllrechteck (OMBB) der Gebäuderegion.
     */
    private BuildingContour computeOrientedBoundingContour(
            List<Point> boundaryPoints, int truePixelCount,
            double[][] gradMag, int width, int height, double minEdgeGradient) {
        List<Point> hull = computeConvexHull(boundaryPoints);
        if (hull.size() < 3) {
            return null;
        }

        double minBoxArea = Double.MAX_VALUE;
        double bestAngle = 0.0;
        double bestMinU = 0, bestMaxU = 0, bestMinV = 0, bestMaxV = 0;

        int n = hull.size();
        for (int i = 0; i < n; i++) {
            Point p1 = hull.get(i);
            Point p2 = hull.get((i + 1) % n);
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            if (Math.hypot(dx, dy) < 1e-3) continue;

            double angle = Math.atan2(dy, dx);
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
            double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;

            for (Point p : hull) {
                double u = p.x * cos + p.y * sin;
                double v = -p.x * sin + p.y * cos;
                if (u < minU) minU = u;
                if (u > maxU) maxU = u;
                if (v < minV) minV = v;
                if (v > maxV) maxV = v;
            }

            double widthU = Math.max(1.0, maxU - minU);
            double heightV = Math.max(1.0, maxV - minV);
            double boxArea = widthU * heightV;

            if (boxArea < minBoxArea) {
                minBoxArea = boxArea;
                bestAngle = angle;
                bestMinU = minU;
                bestMaxU = maxU;
                bestMinV = minV;
                bestMaxV = maxV;
            }
        }

        if (minBoxArea <= 1.0) {
            return null;
        }

        // Kompaktheits-/Rechteckigkeitsmaß (Verhältnis wahre Dachfläche zu OMBB-Fläche)
        double rectangularity = (double) truePixelCount / minBoxArea;
        if (rectangularity < 0.56) {
            // Zu unregelmäßig (z. B. Straßenabschnitt oder verzweigter Hof)
            return null;
        }

        double cos = Math.cos(bestAngle);
        double sin = Math.sin(bestAngle);

        // Die OMBB umschließt bisher nur die Zentren der Randpixel einer Region, die ihrerseits vor der
        // Kante endet (Glättung, Kantenpixel). Ohne Korrektur fällt das Rechteck systematisch zu klein aus.
        // Jede Seite wird deshalb auf das Gradientenmaximum, also die tatsächliche Dachkante, verschoben.
        double[] box = refineSidesToGradientMaximum(
            cos, sin, bestMinU, bestMaxU, bestMinV, bestMaxV, gradMag, width, height, minEdgeGradient);
        bestMinU = box[0];
        bestMaxU = box[1];
        bestMinV = box[2];
        bestMaxV = box[3];

        // Rücktransformation der 4 orthogonalen OMBB-Eckpunkte in Bildkoordinaten
        double[][] uvCorners = {
            {bestMinU, bestMinV},
            {bestMaxU, bestMinV},
            {bestMaxU, bestMaxV},
            {bestMinU, bestMaxV}
        };

        // Eckpunkte subpixelgenau belassen: Runden auf ganze Pixel würde die Rechtwinkligkeit zerstören
        // und die Orientierung kurzer Gebäudeseiten um bis zu ca. 1° verfälschen.
        List<Point2D.Double> corners = new ArrayList<>(4);
        for (double[] uv : uvCorners) {
            corners.add(new Point2D.Double(uv[0] * cos - uv[1] * sin, uv[0] * sin + uv[1] * cos));
        }

        // Hauptorientierung der längeren Gebäudeseite zuweisen
        double lenU = bestMaxU - bestMinU;
        double lenV = bestMaxV - bestMinV;
        double dominantAngle = (lenU >= lenV) ? bestAngle : (bestAngle + Math.PI / 2.0);

        return BuildingContour.fromExactCorners(corners, boundaryPoints, dominantAngle, rectangularity);
    }

    /** Suchfenster der Kantenverfeinerung entlang der Seitennormalen in Pixeln (negativ = nach innen). */
    private static final double REFINE_MIN_OFFSET_PX = -1.5;
    private static final double REFINE_MAX_OFFSET_PX = 3.5;
    private static final double REFINE_STEP_PX = 0.25;

    /**
     * Subpixelgenaue Kantenverfeinerung der OMBB: Jede der vier Rechteckseiten wird parallel zu sich
     * selbst auf die Lage verschoben, an der der mittlere Gradientenbetrag entlang der Seite maximal ist.
     * Orientierung und Rechtwinkligkeit bleiben unverändert.
     *
     * Findet sich im Suchfenster kein signifikantes inneres Maximum (z. B. kontrastarme Dachseite), wird
     * die Seite nur um die halbe Pixelausdehnung nach außen gesetzt, sodass das Rechteck die Flächen der
     * Randpixel statt nur deren Zentren umschließt.
     *
     * @return {minU, maxU, minV, maxV} des verfeinerten Rechtecks im gedrehten (u, v)-System
     */
    private double[] refineSidesToGradientMaximum(
            double cos, double sin, double minU, double maxU, double minV, double maxV,
            double[][] gradMag, int width, int height, double minEdgeGradient) {

        // Halbe Ausdehnung eines Pixelquadrats in Richtung der Rechteckachsen
        double pixelHalfExtent = 0.5 * (Math.abs(cos) + Math.abs(sin));

        double offMinU = refineSideOffset(cos, sin, minU, -1.0, minV, maxV, true, gradMag, width, height, minEdgeGradient);
        double offMaxU = refineSideOffset(cos, sin, maxU, +1.0, minV, maxV, true, gradMag, width, height, minEdgeGradient);
        double offMinV = refineSideOffset(cos, sin, minV, -1.0, minU, maxU, false, gradMag, width, height, minEdgeGradient);
        double offMaxV = refineSideOffset(cos, sin, maxV, +1.0, minU, maxU, false, gradMag, width, height, minEdgeGradient);

        return new double[] {
            minU - (Double.isNaN(offMinU) ? pixelHalfExtent : offMinU),
            maxU + (Double.isNaN(offMaxU) ? pixelHalfExtent : offMaxU),
            minV - (Double.isNaN(offMinV) ? pixelHalfExtent : offMinV),
            maxV + (Double.isNaN(offMaxV) ? pixelHalfExtent : offMaxV)
        };
    }

    /**
     * Sucht für eine Rechteckseite den Versatz entlang der nach außen gerichteten Normalen, bei dem das
     * Gradientenprofil sein Maximum hat (Parabel-Interpolation zwischen den Abtastschritten).
     *
     * @param sideCoord  Lage der Seite auf ihrer Normalenachse (u bzw. v)
     * @param outward    +1 bzw. -1: Richtung der Außennormalen auf dieser Achse
     * @param from       Beginn der Seite auf der Tangentenachse
     * @param to         Ende der Seite auf der Tangentenachse
     * @param normalIsU  true, wenn die Seitennormale die u-Achse ist
     * @return Versatz nach außen in Pixeln oder NaN, wenn kein signifikantes inneres Maximum existiert
     */
    private double refineSideOffset(
            double cos, double sin, double sideCoord, double outward, double from, double to, boolean normalIsU,
            double[][] gradMag, int width, int height, double minEdgeGradient) {

        // Eckbereiche aussparen: dort überlagern sich die Gradienten der Nachbarseiten
        double length = to - from;
        double margin = Math.max(1.0, 0.15 * length);
        double start = from + margin;
        double end = to - margin;
        if (end - start < 3.0) {
            return Double.NaN;
        }
        int samples = Math.max(4, (int) Math.round(end - start) + 1);

        int steps = (int) Math.round((REFINE_MAX_OFFSET_PX - REFINE_MIN_OFFSET_PX) / REFINE_STEP_PX) + 1;
        double[] profile = new double[steps];
        int best = -1;
        for (int i = 0; i < steps; i++) {
            double normalCoord = sideCoord + outward * (REFINE_MIN_OFFSET_PX + i * REFINE_STEP_PX);
            double sum = 0.0;
            for (int k = 0; k < samples; k++) {
                double tangentCoord = start + (end - start) * k / (samples - 1);
                double u = normalIsU ? normalCoord : tangentCoord;
                double v = normalIsU ? tangentCoord : normalCoord;
                sum += sampleBilinear(gradMag, u * cos - v * sin, u * sin + v * cos, width, height);
            }
            profile[i] = sum / samples;
            if (best < 0 || profile[i] > profile[best]) {
                best = i;
            }
        }

        // Nur ein inneres, ausreichend starkes Maximum ist eine verlässliche Kantenlage
        if (best <= 0 || best >= steps - 1 || profile[best] < minEdgeGradient) {
            return Double.NaN;
        }
        double left = profile[best - 1];
        double mid = profile[best];
        double right = profile[best + 1];
        double denom = left - 2.0 * mid + right;
        double subStep = (denom < -1e-9) ? 0.5 * (left - right) / denom : 0.0;
        return REFINE_MIN_OFFSET_PX + (best + subStep) * REFINE_STEP_PX;
    }

    /** Bilineare Interpolation eines Rasters an einer subpixelgenauen Position (außerhalb des Bildes: 0). */
    private static double sampleBilinear(double[][] raster, double x, double y, int width, int height) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        if (x0 < 0 || y0 < 0 || x0 + 1 >= width || y0 + 1 >= height) {
            return 0.0;
        }
        double fx = x - x0;
        double fy = y - y0;
        return raster[x0][y0] * (1.0 - fx) * (1.0 - fy)
             + raster[x0 + 1][y0] * fx * (1.0 - fy)
             + raster[x0][y0 + 1] * (1.0 - fx) * fy
             + raster[x0 + 1][y0 + 1] * fx * fy;
    }

    /**
     * Berechnet die konvexe Hülle einer Punktmenge (Andrew's Monotone Chain, O(N log N)).
     */
    private List<Point> computeConvexHull(List<Point> pts) {
        List<Point> sorted = new ArrayList<>(pts);
        sorted.sort((a, b) -> a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.y, b.y));

        List<Point> hull = new ArrayList<>();
        for (Point p : sorted) {
            while (hull.size() >= 2 && crossProduct(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(p);
        }
        for (int i = sorted.size() - 2, t = hull.size() + 1; i >= 0; i--) {
            Point p = sorted.get(i);
            while (hull.size() >= t && crossProduct(hull.get(hull.size() - 2), hull.get(hull.size() - 1), p) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(p);
        }
        if (hull.size() > 1) {
            hull.remove(hull.size() - 1);
        }
        return hull;
    }

    private long crossProduct(Point o, Point a, Point b) {
        return (long) (a.x - o.x) * (b.y - o.y) - (long) (a.y - o.y) * (b.x - o.x);
    }

    /**
     * Prüft den 3D-Schattenwurf entlang der Kontur:
     * Abtastung in Richtung des geschätzten Sonnen-Schattenvektors sowie entlang der dunkleren Gebäudekanten.
     */
    private double evaluateShadowCast(
            BuildingContour contour, boolean[][] shadowMask, int[][] smoothed,
            double[] shadowVec, double maxDistPx, int width, int height) {

        Point center = contour.getCenter();
        int centerLum = (center.x >= 0 && center.x < width && center.y >= 0 && center.y < height)
            ? smoothed[center.x][center.y] : 140;

        List<Point> boundary = contour.getRawBoundaryPoints();
        if (boundary.isEmpty()) {
            return 0.0;
        }

        int shadowHitsDirectional = 0;
        int directionalSamples = 0;
        int contrastDrops = 0;

        int step = Math.max(1, boundary.size() / 40);
        for (int i = 0; i < boundary.size(); i += step) {
            Point p = boundary.get(i);
            // Prüfe nur Randpunkte, die auf der dem Schatten zugewandten Seite des Schwerpunkts liegen
            double toEdgeX = p.x - center.x;
            double toEdgeY = p.y - center.y;
            double dot = toEdgeX * shadowVec[0] + toEdgeY * shadowVec[1];

            if (dot >= 0) {
                directionalSamples++;
                boolean hitShadow = false;
                boolean hitDrop = false;
                for (double d = 2.0; d <= maxDistPx; d += 1.5) {
                    int sx = (int) Math.round(p.x + d * shadowVec[0]);
                    int sy = (int) Math.round(p.y + d * shadowVec[1]);
                    if (sx >= 0 && sx < width && sy >= 0 && sy < height) {
                        if (shadowMask[sx][sy]) {
                            hitShadow = true;
                        }
                        if (centerLum - smoothed[sx][sy] > 32) {
                            hitDrop = true;
                        }
                    }
                }
                if (hitShadow) shadowHitsDirectional++;
                if (hitDrop) contrastDrops++;
            }
        }

        if (directionalSamples == 0) {
            return 0.2;
        }
        double ratioShadow = (double) shadowHitsDirectional / directionalSamples;
        double ratioDrop = (double) contrastDrops / directionalSamples;
        return Math.min(1.0, 0.65 * ratioShadow + 0.35 * ratioDrop);
    }

    /**
     * Wertet Nachbarschaftsverhältnisse aus:
     * 1. Bestimmt lokale Nachbarn im Siedlungscluster (Radius neighborRadiusPx).
     * 2. Berechnet den lokalen Orientierungs-Konsens (modulo 90° für orthogonale Straßenraster).
     * 3. Richtet leicht abweichende Gebäude (<= 12°) an der Nachbarschaftsflucht aus.
     * 4. Berechnet den Gesamtkonfidenz-Score aus Rechteckigkeit, Kantenstärke, Schattenwurf & Nachbarschaft.
     */
    private List<BuildingContour> evaluateNeighborhoodAndFilter(
            List<BuildingContour> candidates, double neighborRadiusPx,
            double shadowWeight, boolean alignWithNeighbors) {

        int n = candidates.size();
        List<BuildingContour> accepted = new ArrayList<>();
        double[] targetAngles = new double[n];

        for (int i = 0; i < n; i++) {
            BuildingContour current = candidates.get(i);
            Point c1 = current.getCenter();
            double a1 = current.getDominantAngleRad();

            int neighborCount = 0;
            // Zirkulärer Mittelwert für 90°-periodische Gebäudewinkel (4 * theta)
            double sumSin4 = Math.sin(4.0 * a1) * 1.5;
            double sumCos4 = Math.cos(4.0 * a1) * 1.5;

            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                BuildingContour other = candidates.get(j);
                Point c2 = other.getCenter();
                double dist = Math.hypot(c2.x - c1.x, c2.y - c1.y);

                if (dist <= neighborRadiusPx) {
                    neighborCount++;
                    double weight = 1.0 - (dist / neighborRadiusPx);
                    double a2 = other.getDominantAngleRad();
                    sumSin4 += weight * Math.sin(4.0 * a2);
                    sumCos4 += weight * Math.cos(4.0 * a2);
                }
            }

            double consensusAngle = 0.25 * Math.atan2(sumSin4, sumCos4);
            double angleDiff = Math.abs(BuildingContour.normalizeAnglePiOver2(a1 - consensusAngle));
            targetAngles[i] = a1;

            double alignBonus = 0.0;
            if (neighborCount >= 1 && angleDiff <= Math.toRadians(14.0)) {
                alignBonus = 1.0 - (angleDiff / Math.toRadians(14.0));
                if (alignWithNeighbors && angleDiff <= Math.toRadians(12.0)) {
                    // Angleichen der Gebäudeflucht an den Siedlungskonsens
                    targetAngles[i] = a1 - BuildingContour.normalizeAnglePiOver2(a1 - consensusAngle);
                }
            }

            double clusterDensityScore = Math.min(1.0, neighborCount / 3.0);
            // Ohne Nachbarn gibt es keine Aussage: neutraler Wert statt Abwertung. Sonst würde ein einzeln
            // stehendes Gebäude (Hof, Hütte) allein wegen seiner Lage verworfen.
            double neighborhoodScore = (neighborCount == 0) ? 0.5 : 0.55 * clusterDensityScore + 0.45 * alignBonus;
            current.setNeighborhoodScore(neighborhoodScore);

            // Gewichtete Gesamtkonfidenz
            double geomScore = 0.55 * current.getRectangularity() + 0.45 * current.getEdgeStrength();
            double confidence = (1.0 - shadowWeight - 0.20) * geomScore
                              + shadowWeight * current.getShadowScore()
                              + 0.20 * neighborhoodScore;
            current.setConfidenceScore(confidence);
        }

        for (int i = 0; i < n; i++) {
            BuildingContour c = candidates.get(i);
            // Akzeptiere Kandidaten mit hoher Gesamtkonfidenz oder starker Geometrie + Schatten
            if (c.getConfidenceScore() >= 0.42 || (c.getRectangularity() > 0.72 && c.getShadowScore() > 0.40)) {
                if (c.getAspectRatio() > MAX_ASPECT_RATIO) {
                    continue; // schmale Streifen: Gehwege, Dachränder, Straßenstücke
                }
                if (alignWithNeighbors) {
                    c.alignToAngle(targetAngles[i]);
                }
                accepted.add(c);
            }
        }
        return accepted;
    }

    /** Größtes Seitenverhältnis eines Gebäudes. Schmalere Rechtecke waren in der Messung fast nie Gebäude. */
    private static final double MAX_ASPECT_RATIO = 4.0;

    /** Ab diesem überdeckten Anteil der kleineren Kontur gelten zwei Kandidaten als dasselbe Objekt. */
    private static final double MAX_OVERLAP_RATIO = 0.28;

    /**
     * Bereinigt verschachtelte und überlappende Kandidaten (Non-Maximum Suppression).
     *
     * Bei Überlappung bleibt der Kandidat mit der größeren gestützten Fläche (Rechteckfläche mal
     * Rechteckigkeit) erhalten: Eine einzelne Dachseite soll nicht das ganze Dach verdrängen, zu dem sie gehört.
     */
    private List<BuildingContour> resolveOverlapsAndNesting(List<BuildingContour> contours) {
        List<BuildingContour> sorted = new ArrayList<>(contours);
        sorted.sort(Comparator.comparingDouble((BuildingContour c) -> c.getExactArea() * c.getRectangularity()).reversed());

        List<BuildingContour> result = new ArrayList<>();
        for (BuildingContour candidate : sorted) {
            boolean suppressed = false;
            for (BuildingContour kept : result) {
                if (candidate.computeOverlapRatio(kept) > MAX_OVERLAP_RATIO) {
                    suppressed = true;
                    break;
                }
            }
            if (!suppressed) {
                result.add(candidate);
            }
        }
        return result;
    }

    private BufferedImage booleanMaskToImage(
            boolean[][] edges, boolean[][] shadow, boolean[][] veg, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (edges[x][y]) {
                    img.setRGB(x, y, Color.WHITE.getRGB());
                } else if (shadow[x][y]) {
                    img.setRGB(x, y, new Color(40, 110, 220).getRGB());
                } else if (veg[x][y]) {
                    img.setRGB(x, y, new Color(30, 140, 50).getRGB());
                } else {
                    img.setRGB(x, y, Color.BLACK.getRGB());
                }
            }
        }
        return img;
    }

    private static void showDebugImage(BufferedImage image, String title) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(title);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(new JLabel(new ImageIcon(image)));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    /**
     * Einfache Point-Klasse für Bildkoordinaten.
     */
    public static class Point {
        public int x, y;

        public Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ")";
        }
    }
}
