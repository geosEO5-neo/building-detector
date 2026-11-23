package org.openstreetmap.josm.plugins.buildingdetector;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Erzeugt synthetische Luftbilder mit exakt bekannter Gebäudegeometrie für die Tests.
 *
 * Koordinatenkonvention der Sollwerte: kontinuierliche Bildkoordinaten, Pixel {@code (i, j)} bedeckt
 * {@code [i, i+1) x [j, j+1)}. Die Detektion liefert Pixelindex-Koordinaten (ganzzahlig = Pixelzentrum);
 * {@link #toContinuous(BuildingContour)} rechnet sie in die Soll-Konvention um.
 */
final class SyntheticScene {

    /** Supersampling-Faktor: simuliert Mischpixel an Dachkanten (Punktantwort des Sensors). */
    private static final int SUPERSAMPLING = 4;

    /** Versatz des Schlagschattens gegenüber dem Dach in Pixeln (nach Südosten). */
    static final double SHADOW_OFFSET_PX = 6.0;

    final BufferedImage image;
    final List<double[][]> truth;

    private SyntheticScene(BufferedImage image, List<double[][]> truth) {
        this.image = image;
        this.truth = truth;
    }

    /** Eckpunkte eines gedrehten Rechtecks (Mittelpunkt, Seitenlängen, Drehwinkel in Grad). */
    static double[][] rectangle(double cx, double cy, double w, double h, double angleDeg) {
        double a = Math.toRadians(angleDeg);
        double c = Math.cos(a);
        double s = Math.sin(a);
        double[][] local = {{-w / 2, -h / 2}, {w / 2, -h / 2}, {w / 2, h / 2}, {-w / 2, h / 2}};
        double[][] out = new double[4][2];
        for (int i = 0; i < 4; i++) {
            out[i][0] = cx + local[i][0] * c - local[i][1] * s;
            out[i][1] = cy + local[i][0] * s + local[i][1] * c;
        }
        return out;
    }

    /**
     * Regelmäßiges Raster zufällig gedrehter Gebäude mit zufälligen Abmessungen.
     *
     * @param seed    Startwert des Zufallsgenerators (reproduzierbar)
     * @param shadows true: jedes Gebäude wirft einen Schlagschatten
     */
    static SyntheticScene randomGrid(int columns, int rows, long seed, int background, int roof, boolean shadows) {
        Random rnd = new Random(seed);
        List<double[][]> truth = new ArrayList<>();
        for (int gy = 0; gy < rows; gy++) {
            for (int gx = 0; gx < columns; gx++) {
                double w = 40 + rnd.nextDouble() * 24;
                double h = 26 + rnd.nextDouble() * 12;
                double angle = rnd.nextDouble() * 90 - 45;
                double x = 110 + gx * 165 + rnd.nextDouble() * 20;
                double y = 110 + gy * 155 + rnd.nextDouble() * 20;
                truth.add(rectangle(x, y, w, h, angle));
            }
        }
        int width = 110 + columns * 165 + 20;
        int height = 110 + rows * 155 + 20;
        return render(truth, width, height, background, roof, seed, shadows);
    }

    /** Rendert die Soll-Gebäude mit Supersampling und leichtem Sensorrauschen. */
    static SyntheticScene render(List<double[][]> truth, int width, int height,
            int background, int roof, long noiseSeed, boolean shadows) {
        int ss = SUPERSAMPLING;
        BufferedImage big = new BufferedImage(width * ss, height * ss, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = big.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(background, background - 4, background - 10));
        g.fillRect(0, 0, width * ss, height * ss);
        if (shadows) {
            g.setColor(new Color(25, 25, 30));
            for (double[][] r : truth) {
                fill(g, r, SHADOW_OFFSET_PX, SHADOW_OFFSET_PX);
            }
        }
        g.setColor(new Color(roof, roof - 5, roof - 12));
        for (double[][] r : truth) {
            fill(g, r, 0, 0);
        }
        g.dispose();

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(noiseSeed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int sr = 0, sg = 0, sb = 0;
                for (int j = 0; j < ss; j++) {
                    for (int i = 0; i < ss; i++) {
                        int rgb = big.getRGB(x * ss + i, y * ss + j);
                        sr += (rgb >> 16) & 255;
                        sg += (rgb >> 8) & 255;
                        sb += rgb & 255;
                    }
                }
                int noise = (int) Math.round(rnd.nextGaussian() * 2.0);
                int n = ss * ss;
                img.setRGB(x, y, (clamp(sr / n + noise) << 16) | (clamp(sg / n + noise) << 8) | clamp(sb / n + noise));
            }
        }
        return new SyntheticScene(img, truth);
    }

    private static void fill(Graphics2D g, double[][] polygon, double dx, double dy) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < polygon.length; i++) {
            double x = (polygon[i][0] + dx) * SUPERSAMPLING;
            double y = (polygon[i][1] + dy) * SUPERSAMPLING;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.closePath();
        g.fill(path);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** Eckpunkte einer erkannten Kontur in kontinuierlichen Bildkoordinaten (Pixelzentrum = Index + 0,5). */
    static double[][] toContinuous(BuildingContour contour) {
        List<Point2D.Double> corners = contour.getExactCorners();
        double[][] out = new double[corners.size()][2];
        for (int i = 0; i < out.length; i++) {
            out[i][0] = corners.get(i).x + 0.5;
            out[i][1] = corners.get(i).y + 0.5;
        }
        return out;
    }

    static double[] centroid(double[][] polygon) {
        double x = 0, y = 0;
        for (double[] p : polygon) {
            x += p[0];
            y += p[1];
        }
        return new double[] {x / polygon.length, y / polygon.length};
    }

    /** Erkannte Kontur, deren Schwerpunkt dem Soll-Gebäude am nächsten liegt (oder null). */
    static BuildingContour nearest(List<BuildingContour> detected, double[][] truthPolygon, double maxDistancePx) {
        double[] tc = centroid(truthPolygon);
        BuildingContour best = null;
        double bestDistance = maxDistancePx;
        for (BuildingContour c : detected) {
            double[] dc = centroid(toContinuous(c));
            double d = Math.hypot(dc[0] - tc[0], dc[1] - tc[1]);
            if (d < bestDistance) {
                bestDistance = d;
                best = c;
            }
        }
        return best;
    }

    /**
     * Mittleres Abstandsquadrat der vier Eckpunkte bei bester Zuordnung (Startecke und Umlaufsinn
     * eines Rechtecks sind beliebig).
     */
    static double meanSquaredCornerError(double[][] detected, double[][] truthPolygon) {
        double best = Double.MAX_VALUE;
        for (int dir = 0; dir < 2; dir++) {
            for (int shift = 0; shift < 4; shift++) {
                double sq = 0;
                for (int i = 0; i < 4; i++) {
                    int j = dir == 0 ? (i + shift) % 4 : ((shift - i) % 4 + 4) % 4;
                    double ex = detected[j][0] - truthPolygon[i][0];
                    double ey = detected[j][1] - truthPolygon[i][1];
                    sq += ex * ex + ey * ey;
                }
                best = Math.min(best, sq / 4);
            }
        }
        return best;
    }

    /** Längere und kürzere Seitenlänge eines Vierecks. */
    static double[] sideLengths(double[][] polygon) {
        double s1 = Math.hypot(polygon[1][0] - polygon[0][0], polygon[1][1] - polygon[0][1]);
        double s2 = Math.hypot(polygon[2][0] - polygon[1][0], polygon[2][1] - polygon[1][1]);
        return new double[] {Math.max(s1, s2), Math.min(s1, s2)};
    }
}
