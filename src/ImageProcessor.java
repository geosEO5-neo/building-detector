package org.openstreetmap.josm.plugins.buildingdetector;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
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
        public double contrastThreshold = 0.08;   // Empfindlichkeit für Canny-Kanten (0.03 - 0.20)
        public double minBuildingAreaM2 = 12.0;   // Minimale Gebäudefläche in m² (für HOT Hütten/Häuser)
        public double maxBuildingAreaM2 = 2500.0; // Maximale Gebäudefläche in m²
        public double metersPerPixel = 0.25;      // Bodenauflösung (GSD), wird dynamisch aus MapView gesetzt
        public double shadowWeight = 0.35;        // Gewicht des Schattenwurfs bei der 3D-Prüfung [0..1]
        public boolean alignWithNeighbors = true; // Gebäudefluchten an Nachbarschaft angleichen
        public boolean showDebugWindows = false;  // Debug-Ansicht für Zwischenschritte
        public Rectangle regionOfInterest = null; // Optionales Suchfenster im Bild
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
     */
    public List<BuildingContour> detectBuildings(BufferedImage aerialImage, DetectionConfig config) {
        if (aerialImage == null) {
            return Collections.emptyList();
        }

        int width = aerialImage.getWidth();
        int height = aerialImage.getHeight();
        double gsd = Math.max(0.03, config.metersPerPixel);

        // Umrechnung der geodätischen Flächengrenzen (m²) in Pixel² anhand der aktuellen GSD
        int minAreaPx = Math.max(36, (int) Math.round(config.minBuildingAreaM2 / (gsd * gsd)));
        int maxAreaPx = Math.min((width * height) / 3, (int) Math.round(config.maxBuildingAreaM2 / (gsd * gsd)));

        try {
            // 1. Spektrale Zerlegung: Luminanz, Vegetationsmaske (ExG) und Schattenmaske extrahieren
            int[][] luminance = new int[width][height];
            boolean[][] vegetationMask = new boolean[width][height];
            extractSpectralChannels(aerialImage, luminance, vegetationMask);

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
                smoothed, closedEdges, vegetationMask, shadowMask, gradMag,
                width, height, minAreaPx, maxAreaPx, config.regionOfInterest
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
            return resolveOverlapsAndNesting(verified);

        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    /**
     * Extrahiert Luminanz und markiert Vegetation mittels Excess Green Index (ExG = 2G - R - B)
     * sowie hoher Grün-Sättigung.
     */
    private void extractSpectralChannels(BufferedImage img, int[][] luminance, boolean[][] vegetationMask) {
        int width = img.getWidth();
        int height = img.getHeight();
		int[] pixels = img.getRGB(0, 0, width, height, null, 0, width);

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
     */
    private int[][] applyGaussian5x5(int[][] src, int width, int height) {
		int[] kernel = {1, 4, 7, 4, 1};
		int	divisor = 273;

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
        double highThresh = Math.max(18.0, Math.min(180.0, contrastThreshold * 255.0 * 1.35));
        double lowThresh = highThresh * 0.48;

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

    /**
     * Extrahiert homogene Dachregionen, die von Kanten begrenzt werden, und bildet deren
     * orientierte Minimum Bounding Box (OMBB) mittels Rotating Calipers.
     */
    private List<BuildingContour> extractRoofCandidates(
            int[][] smoothed, boolean[][] closedEdges, boolean[][] vegetationMask, boolean[][] shadowMask,
            double[][] gradMag, int width, int height, int minAreaPx, int maxAreaPx, Rectangle roi) {

        List<BuildingContour> candidates = new ArrayList<>();
        boolean[][] visited = new boolean[width][height];

        int minX = (roi != null) ? Math.max(2, roi.x) : 2;
        int minY = (roi != null) ? Math.max(2, roi.y) : 2;
        int maxX = (roi != null) ? Math.min(width - 2, roi.x + roi.width) : width - 2;
        int maxY = (roi != null) ? Math.min(height - 2, roi.y + roi.height) : height - 2;

        int[] dx4 = {1, -1, 0, 0};
        int[] dy4 = {0, 0, 1, -1};

        for (int startX = minX; startX < maxX; startX += 2) {
            for (int startY = minY; startY < maxY; startY += 2) {
                if (visited[startX][startY] || closedEdges[startX][startY]
                        || vegetationMask[startX][startY] || shadowMask[startX][startY]) {
                    continue;
                }

                // Homogenes Region-Growing innerhalb der Canny-Kantenbarrieren
                List<Point> regionPixels = new ArrayList<>();
                List<Point> boundaryPixels = new ArrayList<>();
                Deque<Point> queue = new ArrayDeque<>();

                int seedLum = smoothed[startX][startY];
                double runningMean = seedLum;
                double edgeStrengthSum = 0.0;
                int edgeHitCount = 0;
                boolean touchesImageBorder = false;

                queue.add(new Point(startX, startY));
                visited[startX][startY] = true;

                while (!queue.isEmpty()) {
                    Point p = queue.poll();
                    regionPixels.add(p);

                    if (regionPixels.size() > maxAreaPx * 2) {
                        // Zu groß (z. B. Hintergrund / Feld / Straße) -> abbrechen
                        break;
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
                            edgeStrengthSum += gradMag[nx][ny];
                            edgeHitCount++;
                            continue;
                        }

                        int lumDiff = Math.abs(smoothed[nx][ny] - (int) Math.round(runningMean));
                        int stepDiff = Math.abs(smoothed[nx][ny] - smoothed[p.x][p.y]);

                        if (lumDiff <= 26 && stepDiff <= 12) {
                            if (!visited[nx][ny]) {
                                visited[nx][ny] = true;
                                queue.add(new Point(nx, ny));
                                runningMean = (runningMean * regionPixels.size() + smoothed[nx][ny]) / (regionPixels.size() + 1.0);
                            }
                        } else {
                            isBoundary = true;
                            edgeStrengthSum += gradMag[nx][ny];
                            edgeHitCount++;
                        }
                    }

                    if (isBoundary) {
                        boundaryPixels.add(p);
                    }
                }

                int areaPx = regionPixels.size();
                if (touchesImageBorder || areaPx < minAreaPx || areaPx > maxAreaPx || boundaryPixels.size() < 12) {
                    continue;
                }

                // Berechne die konvexe Hülle und die flächenminimale orientierte Bounding-Box (OMBB)
                BuildingContour contour = computeOrientedBoundingContour(boundaryPixels, areaPx);
                if (contour == null) {
                    continue;
                }

                // Normierte Kantenstärke [0..1]
                double meanEdge = (edgeHitCount > 0) ? (edgeStrengthSum / (edgeHitCount * 255.0)) : 0.0;
                contour.setEdgeStrength(Math.min(1.0, meanEdge * 2.2));

                if (contour.isRectangular() && contour.getExactArea() >= minAreaPx && contour.getExactArea() <= maxAreaPx * 1.35) {
                    candidates.add(contour);
                }
            }
        }
        return candidates;
    }

    /**
     * Berechnet über die konvexe Hülle (Andrew's Monotone Chain) und Rotating Calipers
     * das exakt orientierte minimale Hüllrechteck (OMBB) der Gebäuderegion.
     */
    private BuildingContour computeOrientedBoundingContour(List<Point> boundaryPoints, int truePixelCount) {
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

        // Rücktransformation der 4 orthogonalen OMBB-Eckpunkte in Bildkoordinaten
        double cos = Math.cos(bestAngle);
        double sin = Math.sin(bestAngle);
        double[][] uvCorners = {
            {bestMinU, bestMinV},
            {bestMaxU, bestMinV},
            {bestMaxU, bestMaxV},
            {bestMinU, bestMaxV}
        };

        List<Point> corners = new ArrayList<>(4);
        for (double[] uv : uvCorners) {
            int x = (int) Math.round(uv[0] * cos - uv[1] * sin);
            int y = (int) Math.round(uv[0] * sin + uv[1] * cos);
            corners.add(new Point(x, y));
        }

        // Hauptorientierung der längeren Gebäudeseite zuweisen
        double lenU = bestMaxU - bestMinU;
        double lenV = bestMaxV - bestMinV;
        double dominantAngle = (lenU >= lenV) ? bestAngle : (bestAngle + Math.PI / 2.0);

        return new BuildingContour(corners, boundaryPoints, dominantAngle, rectangularity);
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
            double neighborhoodScore = 0.55 * clusterDensityScore + 0.45 * alignBonus;
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
                if (alignWithNeighbors) {
                    c.alignToAngle(targetAngles[i]);
                }
                accepted.add(c);
            }
        }
        return accepted;
    }

    /**
     * Bereinigt verschachtelte Konturen (Innenhöfe) und überlappende Detektionen mittels
     * Konfidenz-sortierter Non-Maximum Suppression (NMS).
     */
    private List<BuildingContour> resolveOverlapsAndNesting(List<BuildingContour> contours) {
        List<BuildingContour> sorted = new ArrayList<>(contours);
        sorted.sort(Comparator.comparingDouble(BuildingContour::getConfidenceScore).reversed());

        List<BuildingContour> result = new ArrayList<>();
        for (BuildingContour candidate : sorted) {
            boolean suppressed = false;
            for (BuildingContour kept : result) {
                double overlap = candidate.computeOverlapRatio(kept);
                Rectangle rCand = candidate.getBoundingBox();
                Rectangle rKept = kept.getBoundingBox();

                if (overlap > 0.28 || rKept.contains(rCand) || rCand.contains(rKept)) {
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
