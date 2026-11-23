// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Messwerkzeug: vergleicht die Gebäudeerkennung auf echten Orthofotos mit den in OpenStreetMap
 * vorhandenen Gebäuden.
 *
 * <p>Für jede Szene aus {@code eval/scenes.csv} wird ein Kachelmosaik geladen (Web-Mercator-Kacheln),
 * die Erkennung mit Standardparametern ausgeführt und das Ergebnis den OSM-Gebäuden im selben
 * Ausschnitt zugeordnet. Ausgegeben werden Trefferstatistik (Präzision, Trefferquote, jeweils mit
 * 95-%-Wilson-Intervall) und geometrische Abweichungen der Treffer in Metern.</p>
 *
 * <p>Bilder und OSM-Daten werden nur zwischengespeichert ({@code build-eval/cache}), nicht versioniert.
 * Das Werkzeug braucht Netzzugang und läuft deshalb nicht in der automatischen Testsuite.</p>
 *
 * <p>Grenzen der Aussage: OSM ist keine fehlerfreie Referenz, und Orthofotos zeigen Dachflächen, die
 * gegenüber dem Grundriss durch Dachüberstand und Umklappung versetzt sind. Die geometrischen
 * Abweichungen enthalten daher Anteile der Referenz und des Bildes, nicht nur der Erkennung.</p>
 */
public final class RealImageryEvaluation {

    static final String DEFAULT_TILE_URL =
        "https://mapsneu.wien.gv.at/basemap/bmaporthofoto30cm/normal/google3857/{z}/{y}/{x}.jpeg";
    static final String DEFAULT_ATTRIBUTION =
        "Orthofoto: basemap.at (CC BY 4.0) · Gebäude: © OpenStreetMap-Mitwirkende (ODbL)";
    static final String OSM_MAP_URL = "https://api.openstreetmap.org/api/0.6/map?bbox=%.7f,%.7f,%.7f,%.7f";
    static final String USER_AGENT =
        "building-detector-eval/1 (+https://github.com/geosEO5-neo/building-detector)";

    static final int TILE_SIZE = 256;
    /** Rand in Pixeln, innerhalb dessen Objekte als "vom Bildrand beschnitten" gelten. */
    static final double FRAME_MARGIN_PX = 8.0;
    /** WGS84. */
    static final double WGS84_A = 6378137.0;
    static final double WGS84_E2 = 0.00669437999014;

    private RealImageryEvaluation() {
    }

    /** Eine Testszene: Mittelpunkt, Zoomstufe und Größe des Kachelmosaiks. */
    record Scene(String name, String role, double lat, double lon, int zoom, int tilesX, int tilesY, String description) {
        int tileX0() {
            return (int) Math.floor(lonToTileX(lon, zoom)) - tilesX / 2;
        }

        int tileY0() {
            return (int) Math.floor(latToTileY(lat, zoom)) - tilesY / 2;
        }

        int width() {
            return tilesX * TILE_SIZE;
        }

        int height() {
            return tilesY * TILE_SIZE;
        }

        /** Bodenmeter je Pixel in Ost- und Nordrichtung am Szenenmittelpunkt (WGS84-Ellipsoid). */
        double[] metersPerPixel() {
            double phi = Math.toRadians(tileYToLat(tileY0() + tilesY / 2.0, zoom));
            double w2 = 1.0 - WGS84_E2 * Math.sin(phi) * Math.sin(phi);
            double primeVertical = WGS84_A / Math.sqrt(w2);
            double meridian = WGS84_A * (1.0 - WGS84_E2) / (w2 * Math.sqrt(w2));
            // Web Mercator: ein Pixel entspricht dLambda = k und dPhi = k cos(phi)
            double k = 2.0 * Math.PI / (TILE_SIZE * Math.pow(2.0, zoom));
            return new double[] {primeVertical * Math.cos(phi) * k, meridian * Math.cos(phi) * k};
        }
    }

    /** Polygon in Pixelkoordinaten samt Kennung (OSM-Way-ID bzw. laufende Nummer der Erkennung). */
    static final class Shape {
        final long id;
        final double[][] px;
        final Area area;
        final double areaPx;
        final Rectangle2D bounds;
        double confidence;

        Shape(long id, double[][] px) {
            this.id = id;
            this.px = px;
            Path2D.Double path = new Path2D.Double();
            for (int i = 0; i < px.length; i++) {
                if (i == 0) {
                    path.moveTo(px[i][0], px[i][1]);
                } else {
                    path.lineTo(px[i][0], px[i][1]);
                }
            }
            path.closePath();
            this.area = new Area(path);
            this.areaPx = areaOf(this.area);
            this.bounds = this.area.getBounds2D();
        }

        boolean inside(Rectangle2D frame) {
            return frame.contains(bounds);
        }
    }

    /** Ergebnis einer Szene. */
    static final class SceneResult {
        Scene scene;
        int reference;      // auswertbare OSM-Gebäude
        int detections;     // gewertete Erkennungen
        int truePositives;
        int falsePositives;
        int falseNegatives;
        long millis;
        List<double[]> residuals = new ArrayList<>(); // je Treffer: dE, dN, iou, flächenverhältnis, dAzimut, umrissRms, umrissMax
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Map<String, String> opt = parseArgs(args);
        Path scenesFile = Paths.get(opt.getOrDefault("scenes", "eval/scenes.csv"));
        Path outDir = Paths.get(opt.getOrDefault("out", "build-eval/results"));
        Path cacheDir = Paths.get(opt.getOrDefault("cache", "build-eval/cache"));
        String tileUrl = opt.getOrDefault("tile-url", DEFAULT_TILE_URL);
        String attribution = opt.getOrDefault("attribution", DEFAULT_ATTRIBUTION);
        double iouThreshold = Double.parseDouble(opt.getOrDefault("iou", "0.5"));
        Set<String> only = new HashSet<>();
        if (opt.containsKey("only")) {
            for (String s : opt.get("only").split(",")) {
                only.add(s.trim());
            }
        }

        ImageProcessor.DetectionConfig defaults = new ImageProcessor.DetectionConfig();
        double contrast = Double.parseDouble(opt.getOrDefault("contrast", String.valueOf(defaults.contrastThreshold)));

        Files.createDirectories(outDir);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        List<SceneResult> results = new ArrayList<>();

        try (PrintWriter buildings = new PrintWriter(Files.newBufferedWriter(outDir.resolve("buildings.csv"), StandardCharsets.UTF_8));
             PrintWriter dets = new PrintWriter(Files.newBufferedWriter(outDir.resolve("detections.csv"), StandardCharsets.UTF_8))) {
            buildings.println("scene;role;osm_way;status;iou;dE_m;dN_m;area_ref_m2;area_det_m2;azimuth_diff_deg;"
                + "outline_rms_m;outline_max_m;confidence;wkt_px");
            dets.println("scene;role;detection;status;confidence;area_m2;best_iou;covered_by_reference;wkt_px");

            for (Scene scene : readScenes(scenesFile)) {
                if (!only.isEmpty() && !only.contains(scene.name())) {
                    continue;
                }
                BufferedImage image = loadMosaic(http, scene, tileUrl, cacheDir);
                List<Shape> reference = loadReference(http, scene, cacheDir);
                SceneResult r = evaluate(scene, image, reference, contrast, iouThreshold, buildings, dets,
                    outDir.resolve(scene.name() + ".png"), attribution);
                results.add(r);
            }
        }

        writeSummary(results, outDir.resolve("summary.csv"), iouThreshold);
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opt = new HashMap<>();
        for (String a : args) {
            if (a.startsWith("--") && a.contains("=")) {
                int i = a.indexOf('=');
                String value = a.substring(i + 1);
                if (!value.isEmpty()) {
                    opt.put(a.substring(2, i), value);
                }
            } else if (!a.isBlank()) {
                throw new IllegalArgumentException("Unbekanntes Argument: " + a + " (erwartet --name=wert)");
            }
        }
        return opt;
    }

    static List<Scene> readScenes(Path file) throws IOException {
        List<Scene> scenes = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("name;")) {
                continue;
            }
            String[] f = t.split(";", -1);
            if (f.length < 8) {
                throw new IOException("Zeile mit zu wenigen Feldern in " + file + ": " + line);
            }
            scenes.add(new Scene(f[0].trim(), f[1].trim(), Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                Integer.parseInt(f[4].trim()), Integer.parseInt(f[5].trim()), Integer.parseInt(f[6].trim()), f[7].trim()));
        }
        return scenes;
    }

    // ---------------------------------------------------------------------------------------------
    // Daten laden
    // ---------------------------------------------------------------------------------------------

    private static byte[] fetch(HttpClient http, String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT).timeout(Duration.ofSeconds(90)).GET().build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                last = new IOException("HTTP " + response.statusCode() + " für " + url);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(1500L * (attempt + 1));
        }
        throw last;
    }

    private static BufferedImage loadMosaic(HttpClient http, Scene scene, String tileUrl, Path cacheDir)
            throws IOException, InterruptedException {
        BufferedImage mosaic = new BufferedImage(scene.width(), scene.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = mosaic.createGraphics();
        // Der Zwischenspeicher hängt von der Bildquelle ab, damit sich verschiedene Quellen nicht mischen.
        Path tileDir = cacheDir.resolve("tiles-" + Integer.toHexString(tileUrl.hashCode()));
        for (int i = 0; i < scene.tilesX(); i++) {
            for (int j = 0; j < scene.tilesY(); j++) {
                int x = scene.tileX0() + i;
                int y = scene.tileY0() + j;
                Path file = tileDir.resolve(scene.zoom() + "/" + y + "/" + x + ".img");
                if (!Files.exists(file)) {
                    String url = tileUrl.replace("{z}", String.valueOf(scene.zoom()))
                        .replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
                    Files.createDirectories(file.getParent());
                    Files.write(file, fetch(http, url));
                }
                BufferedImage tile = ImageIO.read(new ByteArrayInputStream(Files.readAllBytes(file)));
                if (tile == null) {
                    throw new IOException("Kachel nicht lesbar: " + file);
                }
                g.drawImage(tile, i * TILE_SIZE, j * TILE_SIZE, null);
            }
        }
        g.dispose();
        return mosaic;
    }

    /** OSM-Gebäude (Ways und äußere Ringe von Multipolygonen) im Bildausschnitt, in Pixelkoordinaten. */
    private static List<Shape> loadReference(HttpClient http, Scene scene, Path cacheDir) throws Exception {
        Path file = cacheDir.resolve("osm/" + scene.name() + ".osm");
        if (!Files.exists(file)) {
            double west = tileXToLon(scene.tileX0(), scene.zoom());
            double east = tileXToLon(scene.tileX0() + scene.tilesX(), scene.zoom());
            double north = tileYToLat(scene.tileY0(), scene.zoom());
            double south = tileYToLat(scene.tileY0() + scene.tilesY(), scene.zoom());
            Files.createDirectories(file.getParent());
            Files.write(file, fetch(http, String.format(Locale.ROOT, OSM_MAP_URL, west, south, east, north)));
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = factory.newDocumentBuilder().parse(file.toFile());

        Map<String, double[]> nodes = new HashMap<>();
        NodeList nodeList = doc.getElementsByTagName("node");
        for (int i = 0; i < nodeList.getLength(); i++) {
            Element e = (Element) nodeList.item(i);
            double lat = Double.parseDouble(e.getAttribute("lat"));
            double lon = Double.parseDouble(e.getAttribute("lon"));
            nodes.put(e.getAttribute("id"), new double[] {
                (lonToTileX(lon, scene.zoom()) - scene.tileX0()) * TILE_SIZE,
                (latToTileY(lat, scene.zoom()) - scene.tileY0()) * TILE_SIZE});
        }

        Map<String, double[][]> ways = new HashMap<>();
        Set<String> buildingWays = new java.util.LinkedHashSet<>();
        NodeList wayList = doc.getElementsByTagName("way");
        for (int i = 0; i < wayList.getLength(); i++) {
            Element e = (Element) wayList.item(i);
            NodeList refs = e.getElementsByTagName("nd");
            List<double[]> pts = new ArrayList<>();
            boolean complete = true;
            for (int k = 0; k < refs.getLength(); k++) {
                double[] p = nodes.get(((Element) refs.item(k)).getAttribute("ref"));
                if (p == null) {
                    complete = false;
                    break;
                }
                pts.add(p);
            }
            boolean closed = refs.getLength() >= 4
                && ((Element) refs.item(0)).getAttribute("ref").equals(((Element) refs.item(refs.getLength() - 1)).getAttribute("ref"));
            if (!complete || !closed) {
                continue;
            }
            ways.put(e.getAttribute("id"), pts.subList(0, pts.size() - 1).toArray(new double[0][]));
            if (isBuilding(e)) {
                buildingWays.add(e.getAttribute("id"));
            }
        }

        NodeList relationList = doc.getElementsByTagName("relation");
        for (int i = 0; i < relationList.getLength(); i++) {
            Element e = (Element) relationList.item(i);
            if (!isBuilding(e)) {
                continue;
            }
            NodeList members = e.getElementsByTagName("member");
            for (int k = 0; k < members.getLength(); k++) {
                Element m = (Element) members.item(k);
                if ("way".equals(m.getAttribute("type")) && "outer".equals(m.getAttribute("role"))
                        && ways.containsKey(m.getAttribute("ref"))) {
                    buildingWays.add(m.getAttribute("ref"));
                }
            }
        }

        List<Shape> shapes = new ArrayList<>();
        for (String id : buildingWays) {
            Shape s = new Shape(Long.parseLong(id), ways.get(id));
            if (s.areaPx > 0.0) {
                shapes.add(s);
            }
        }
        return shapes;
    }

    private static boolean isBuilding(Element wayOrRelation) {
        NodeList tags = wayOrRelation.getElementsByTagName("tag");
        for (int i = 0; i < tags.getLength(); i++) {
            Element t = (Element) tags.item(i);
            if ("building".equals(t.getAttribute("k"))) {
                return !"no".equals(t.getAttribute("v"));
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Auswertung
    // ---------------------------------------------------------------------------------------------

    private static SceneResult evaluate(Scene scene, BufferedImage image, List<Shape> reference, double contrast,
            double iouThreshold, PrintWriter buildings, PrintWriter dets, Path overlayFile, String attribution)
            throws IOException {
        double[] mpp = scene.metersPerPixel();
        double pixelAreaM2 = mpp[0] * mpp[1];

        ImageProcessor.DetectionConfig config = new ImageProcessor.DetectionConfig();
        config.contrastThreshold = contrast;
        config.metersPerPixel = Math.sqrt(pixelAreaM2); // flächentreue mittlere Auflösung, wie im Plugin

        long t0 = System.nanoTime();
        List<BuildingContour> contours = new ImageProcessor().detectBuildings(image, config);
        long millis = (System.nanoTime() - t0) / 1_000_000L;

        List<Shape> detected = new ArrayList<>();
        for (int i = 0; i < contours.size(); i++) {
            List<Point2D.Double> corners = contours.get(i).getExactCorners();
            double[][] px = new double[corners.size()][];
            for (int k = 0; k < px.length; k++) {
                // Erkennung liefert Pixelindizes (Pixelmitte = ganzzahlig); Bildkoordinaten liegen 0,5 px daneben
                px[k] = new double[] {corners.get(k).x + 0.5, corners.get(k).y + 0.5};
            }
            Shape s = new Shape(i, px);
            s.confidence = contours.get(i).getConfidenceScore();
            if (s.areaPx > 0.0) {
                detected.add(s);
            }
        }

        Rectangle2D frame = new Rectangle2D.Double(FRAME_MARGIN_PX, FRAME_MARGIN_PX,
            scene.width() - 2 * FRAME_MARGIN_PX, scene.height() - 2 * FRAME_MARGIN_PX);

        // Auswertbar sind nur Referenzgebäude, die vollständig im Bild liegen und die Mindestfläche erreichen.
        boolean[] evaluable = new boolean[reference.size()];
        for (int j = 0; j < reference.size(); j++) {
            Shape ref = reference.get(j);
            evaluable[j] = ref.inside(frame) && ref.areaPx * pixelAreaM2 >= config.minBuildingAreaM2;
        }

        // Paarweise IoU, danach gierige Eins-zu-eins-Zuordnung in absteigender Reihenfolge.
        List<double[]> pairs = new ArrayList<>();
        double[] bestIou = new double[detected.size()];
        double[] covered = new double[detected.size()];
        double[] coveredIgnored = new double[detected.size()];
        for (int i = 0; i < detected.size(); i++) {
            Shape d = detected.get(i);
            for (int j = 0; j < reference.size(); j++) {
                Shape ref = reference.get(j);
                if (!d.bounds.intersects(ref.bounds)) {
                    continue;
                }
                Area inter = new Area(d.area);
                inter.intersect(ref.area);
                double a = areaOf(inter);
                if (a <= 0.0) {
                    continue;
                }
                double iou = a / (d.areaPx + ref.areaPx - a);
                pairs.add(new double[] {iou, i, j});
                bestIou[i] = Math.max(bestIou[i], iou);
                covered[i] += a / d.areaPx;
                if (!evaluable[j]) {
                    coveredIgnored[i] += a / d.areaPx;
                }
            }
        }
        pairs.sort((p, q) -> Double.compare(q[0], p[0]));
        int[] matchOfDet = new int[detected.size()];
        int[] matchOfRef = new int[reference.size()];
        java.util.Arrays.fill(matchOfDet, -1);
        java.util.Arrays.fill(matchOfRef, -1);
        Map<Integer, Double> iouOfDet = new HashMap<>();
        for (double[] p : pairs) {
            if (p[0] < iouThreshold) {
                break;
            }
            int i = (int) p[1];
            int j = (int) p[2];
            if (matchOfDet[i] >= 0 || matchOfRef[j] >= 0) {
                continue;
            }
            matchOfDet[i] = j;
            matchOfRef[j] = i;
            iouOfDet.put(i, p[0]);
        }

        SceneResult result = new SceneResult();
        result.scene = scene;
        result.millis = millis;

        // Erkennungen einordnen: Treffer, Fehltreffer oder nicht gewertet (Bildrand bzw. nicht auswertbare Referenz).
        String[] detStatus = new String[detected.size()];
        for (int i = 0; i < detected.size(); i++) {
            Shape d = detected.get(i);
            String status;
            if (matchOfDet[i] >= 0) {
                status = evaluable[matchOfDet[i]] ? "TP" : "ignored";
            } else if (!d.inside(frame) || coveredIgnored[i] > 0.5) {
                status = "ignored";
            } else {
                status = "FP";
            }
            detStatus[i] = status;
            if ("TP".equals(status)) {
                result.truePositives++;
            } else if ("FP".equals(status)) {
                result.falsePositives++;
            }
            dets.println(String.join(";", scene.name(), scene.role(), String.valueOf(d.id), status,
                fmt(d.confidence, 3), fmt(d.areaPx * pixelAreaM2, 1), fmt(bestIou[i], 3),
                fmt(Math.min(1.0, covered[i]), 3), wkt(d.px)));
        }
        result.detections = result.truePositives + result.falsePositives;

        for (int j = 0; j < reference.size(); j++) {
            Shape ref = reference.get(j);
            if (!evaluable[j]) {
                buildings.println(String.join(";", scene.name(), scene.role(), String.valueOf(ref.id), "ignored",
                    "", "", "", fmt(ref.areaPx * pixelAreaM2, 1), "", "", "", "", "", wkt(ref.px)));
                continue;
            }
            result.reference++;
            if (matchOfRef[j] < 0) {
                result.falseNegatives++;
                buildings.println(String.join(";", scene.name(), scene.role(), String.valueOf(ref.id), "FN",
                    "", "", "", fmt(ref.areaPx * pixelAreaM2, 1), "", "", "", "", "", wkt(ref.px)));
                continue;
            }
            Shape d = detected.get(matchOfRef[j]);
            double[][] refM = toMetric(ref.px, mpp);
            double[][] detM = toMetric(d.px, mpp);
            double[] cRef = centroid(refM);
            double[] cDet = centroid(detM);
            double dE = cDet[0] - cRef[0];
            double dN = cDet[1] - cRef[1];
            double iou = iouOfDet.get(matchOfRef[j]);
            double ratio = d.areaPx / ref.areaPx;
            double dAz = Math.toDegrees(normalizeQuarterTurn(orientation(detM) - orientation(refM)));
            double[] outline = outlineDeviation(detM, refM);
            result.residuals.add(new double[] {dE, dN, iou, ratio, dAz, outline[0], outline[1]});
            buildings.println(String.join(";", scene.name(), scene.role(), String.valueOf(ref.id), "TP",
                fmt(iou, 3), fmt(dE, 3), fmt(dN, 3), fmt(ref.areaPx * pixelAreaM2, 1), fmt(d.areaPx * pixelAreaM2, 1),
                fmt(dAz, 2), fmt(outline[0], 3), fmt(outline[1], 3), fmt(d.confidence, 3), wkt(ref.px)));
        }

        writeOverlay(scene, image, reference, evaluable, detected, detStatus, overlayFile, attribution, result, iouThreshold);
        return result;
    }

    private static void writeOverlay(Scene scene, BufferedImage image, List<Shape> reference, boolean[] evaluable,
            List<Shape> detected, String[] detStatus, Path file, String attribution, SceneResult r, double iouThreshold)
            throws IOException {
        int band = 92;
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight() + band, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.drawImage(image, 0, 0, null);

        Color refColor = new Color(0, 200, 255);
        Color hit = new Color(0, 255, 0);
        Color miss = new Color(255, 40, 40);
        Color ignored = new Color(255, 200, 0);
        g.setStroke(new BasicStroke(2f));
        for (int j = 0; j < reference.size(); j++) {
            g.setColor(evaluable[j] ? refColor : new Color(0, 200, 255, 110));
            g.draw(reference.get(j).area);
        }
        for (int i = 0; i < detected.size(); i++) {
            g.setColor("TP".equals(detStatus[i]) ? hit : "FP".equals(detStatus[i]) ? miss : ignored);
            g.draw(detected.get(i).area);
        }

        int h = image.getHeight();
        g.setColor(new Color(20, 20, 20));
        g.fillRect(0, h, out.getWidth(), band);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
        int x = 16;
        String[] labels = {"OSM-Gebäude (Referenz)", String.format("Erkennung, Treffer (IoU > %.1f)", iouThreshold),
            "Erkennung, kein Treffer", "nicht gewertet (Bildrand)"};
        Color[] colors = {refColor, hit, miss, ignored};
        for (int i = 0; i < labels.length; i++) {
            g.setColor(colors[i]);
            g.setStroke(new BasicStroke(4f));
            g.drawRect(x, h + 14, 26, 20);
            g.setColor(new Color(240, 240, 240));
            g.drawString(labels[i], x + 36, h + 32);
            x += 62 + g.getFontMetrics().stringWidth(labels[i]);
        }
        g.setColor(new Color(190, 190, 190));
        g.drawString(String.format("%s · %.2f m/px · %d Referenz, %d Erkennungen, %d Treffer · %s",
            scene.name(), Math.sqrt(scene.metersPerPixel()[0] * scene.metersPerPixel()[1]),
            r.reference, r.detections, r.truePositives, attribution), 16, h + 70);
        g.dispose();
        ImageIO.write(out, "png", file.toFile());
    }

    private static void writeSummary(List<SceneResult> results, Path file, double iouThreshold) throws IOException {
        Map<String, SceneResult> pooled = new LinkedHashMap<>();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("scene;role;gsd_m;reference;detections;tp;fp;fn;precision;precision_lo;precision_hi;"
                + "recall;recall_lo;recall_hi;f1;mean_iou;bias_dE_m;bias_dN_m;sd_dE_m;sd_dN_m;outline_rms_m;millis");
            // Konsolenausgabe bewusst nur in ASCII: Ant und manche Terminals geben Umlaute fehlerhaft wieder.
            System.out.printf("%nTreffer ab IoU > %.2f; Intervalle: 95 %% nach Wilson%n", iouThreshold);
            System.out.printf("%-16s %-8s %5s %5s %4s %4s %4s  %-20s %-20s %5s%n",
                "Szene", "Rolle", "Ref", "Erk", "TP", "FP", "FN", "Precision [95 %]", "Recall [95 %]", "F1");
            for (SceneResult r : results) {
                printRow(w, r.scene.name(), r.scene.role(), r);
                SceneResult p = pooled.computeIfAbsent(r.scene.role(), k -> new SceneResult());
                p.reference += r.reference;
                p.detections += r.detections;
                p.truePositives += r.truePositives;
                p.falsePositives += r.falsePositives;
                p.falseNegatives += r.falseNegatives;
                p.millis += r.millis;
                p.residuals.addAll(r.residuals);
            }
            for (Map.Entry<String, SceneResult> e : pooled.entrySet()) {
                printRow(w, "GESAMT", e.getKey(), e.getValue());
            }
        }
        System.out.println("\nErgebnisse: " + file.getParent().toAbsolutePath());
    }

    private static void printRow(PrintWriter w, String name, String role, SceneResult r) {
        double[] p = wilson(r.truePositives, r.truePositives + r.falsePositives);
        double[] q = wilson(r.truePositives, r.reference);
        double f1 = (p[0] + q[0] > 0) ? 2 * p[0] * q[0] / (p[0] + q[0]) : 0.0;
        double[] stat = residualStatistics(r.residuals);
        String gsd = (r.scene != null)
            ? fmt(Math.sqrt(r.scene.metersPerPixel()[0] * r.scene.metersPerPixel()[1]), 4) : "";
        w.println(String.join(";", name, role, gsd, String.valueOf(r.reference), String.valueOf(r.detections),
            String.valueOf(r.truePositives), String.valueOf(r.falsePositives), String.valueOf(r.falseNegatives),
            fmt(p[0], 4), fmt(p[1], 4), fmt(p[2], 4), fmt(q[0], 4), fmt(q[1], 4), fmt(q[2], 4), fmt(f1, 4),
            fmt(stat[0], 3), fmt(stat[1], 3), fmt(stat[2], 3), fmt(stat[3], 3), fmt(stat[4], 3), fmt(stat[5], 3),
            String.valueOf(r.millis)));
        System.out.printf("%-16s %-8s %5d %5d %4d %4d %4d  %5.1f %% [%4.1f-%4.1f]   %5.1f %% [%4.1f-%4.1f]  %5.2f%n",
            name, role, r.reference, r.detections, r.truePositives, r.falsePositives, r.falseNegatives,
            100 * p[0], 100 * p[1], 100 * p[2], 100 * q[0], 100 * q[1], 100 * q[2], f1);
    }

    /** @return {mittlere IoU, Mittel dE, Mittel dN, Standardabweichung dE, Standardabweichung dN, RMS Umrissabweichung} */
    static double[] residualStatistics(List<double[]> residuals) {
        int n = residuals.size();
        if (n == 0) {
            return new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        }
        double sE = 0, sN = 0, sIou = 0, sOutline2 = 0;
        for (double[] r : residuals) {
            sE += r[0];
            sN += r[1];
            sIou += r[2];
            sOutline2 += r[5] * r[5];
        }
        double mE = sE / n;
        double mN = sN / n;
        double vE = 0, vN = 0;
        for (double[] r : residuals) {
            vE += (r[0] - mE) * (r[0] - mE);
            vN += (r[1] - mN) * (r[1] - mN);
        }
        double sdE = n > 1 ? Math.sqrt(vE / (n - 1)) : Double.NaN;
        double sdN = n > 1 ? Math.sqrt(vN / (n - 1)) : Double.NaN;
        return new double[] {sIou / n, mE, mN, sdE, sdN, Math.sqrt(sOutline2 / n)};
    }

    // ---------------------------------------------------------------------------------------------
    // Geometrie und Statistik
    // ---------------------------------------------------------------------------------------------

    /** 95-%-Wilson-Intervall für einen Anteil k/n: {Schätzwert, untere Grenze, obere Grenze}. */
    static double[] wilson(int k, int n) {
        if (n == 0) {
            return new double[] {0.0, 0.0, 1.0};
        }
        double z = 1.959963984540054;
        double p = (double) k / n;
        double denom = 1.0 + z * z / n;
        double centre = (p + z * z / (2.0 * n)) / denom;
        double half = z * Math.sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n)) / denom;
        return new double[] {p, Math.max(0.0, centre - half), Math.min(1.0, centre + half)};
    }

    /** Fläche einer {@link Area} aus geraden Segmenten (Gaußsche Flächenformel über alle Teilpfade). */
    static double areaOf(Area area) {
        double sum = 0.0;
        double[] c = new double[6];
        double startX = 0, startY = 0, lastX = 0, lastY = 0;
        for (PathIterator it = area.getPathIterator(null, 0.01); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                startX = c[0];
                startY = c[1];
                lastX = c[0];
                lastY = c[1];
            } else if (type == PathIterator.SEG_LINETO) {
                sum += lastX * c[1] - c[0] * lastY;
                lastX = c[0];
                lastY = c[1];
            } else if (type == PathIterator.SEG_CLOSE) {
                sum += lastX * startY - startX * lastY;
                lastX = startX;
                lastY = startY;
            }
        }
        return Math.abs(sum) / 2.0;
    }

    /** Pixel → lokales metrisches Ost-Nord-System (Ursprung beliebig, Nord nach oben). */
    static double[][] toMetric(double[][] px, double[] metersPerPixel) {
        double[][] m = new double[px.length][];
        for (int i = 0; i < px.length; i++) {
            m[i] = new double[] {px[i][0] * metersPerPixel[0], -px[i][1] * metersPerPixel[1]};
        }
        return m;
    }

    /** Flächenschwerpunkt eines einfachen Polygons. */
    static double[] centroid(double[][] p) {
        double a = 0, cx = 0, cy = 0;
        for (int i = 0; i < p.length; i++) {
            double[] u = p[i];
            double[] v = p[(i + 1) % p.length];
            double cross = u[0] * v[1] - v[0] * u[1];
            a += cross;
            cx += (u[0] + v[0]) * cross;
            cy += (u[1] + v[1]) * cross;
        }
        if (Math.abs(a) < 1e-12) {
            return new double[] {p[0][0], p[0][1]};
        }
        return new double[] {cx / (3.0 * a), cy / (3.0 * a)};
    }

    /** Hauptrichtung der Gebäudeseiten modulo 90° (längengewichtetes zirkuläres Mittel über 4·Richtung). */
    static double orientation(double[][] p) {
        double s = 0, c = 0;
        for (int i = 0; i < p.length; i++) {
            double dx = p[(i + 1) % p.length][0] - p[i][0];
            double dy = p[(i + 1) % p.length][1] - p[i][1];
            double len = Math.hypot(dx, dy);
            double theta = Math.atan2(dy, dx);
            s += len * Math.sin(4.0 * theta);
            c += len * Math.cos(4.0 * theta);
        }
        return 0.25 * Math.atan2(s, c);
    }

    /** Bringt eine Winkeldifferenz in den Bereich (−45°, +45°]. */
    static double normalizeQuarterTurn(double angle) {
        double quarter = Math.PI / 2.0;
        double a = angle % quarter;
        if (a > quarter / 2.0) {
            a -= quarter;
        } else if (a <= -quarter / 2.0) {
            a += quarter;
        }
        return a;
    }

    /**
     * Abstand des erkannten Umrisses vom Referenzumriss: Punkte im Abstand von höchstens 0,25 m auf dem
     * erkannten Umriss, jeweils kürzester Abstand zum Referenzumriss.
     *
     * @return {RMS, Maximum} in Metern
     */
    static double[] outlineDeviation(double[][] detected, double[][] reference) {
        double sum2 = 0.0;
        double max = 0.0;
        int count = 0;
        for (int i = 0; i < detected.length; i++) {
            double[] a = detected[i];
            double[] b = detected[(i + 1) % detected.length];
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) / 0.25));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                double d = distanceToOutline(a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), reference);
                sum2 += d * d;
                max = Math.max(max, d);
                count++;
            }
        }
        return new double[] {Math.sqrt(sum2 / count), max};
    }

    static double distanceToOutline(double x, double y, double[][] polygon) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] b = polygon[(i + 1) % polygon.length];
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            double len2 = dx * dx + dy * dy;
            double t = len2 > 0 ? Math.max(0.0, Math.min(1.0, ((x - a[0]) * dx + (y - a[1]) * dy) / len2)) : 0.0;
            best = Math.min(best, Math.hypot(x - (a[0] + t * dx), y - (a[1] + t * dy)));
        }
        return best;
    }

    static double lonToTileX(double lon, int zoom) {
        return (lon + 180.0) / 360.0 * Math.pow(2.0, zoom);
    }

    static double latToTileY(double lat, int zoom) {
        double phi = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(phi) + 1.0 / Math.cos(phi)) / Math.PI) / 2.0 * Math.pow(2.0, zoom);
    }

    static double tileXToLon(double x, int zoom) {
        return x / Math.pow(2.0, zoom) * 360.0 - 180.0;
    }

    static double tileYToLat(double y, int zoom) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * y / Math.pow(2.0, zoom)))));
    }

    private static String fmt(double v, int digits) {
        return Double.isNaN(v) ? "" : String.format(Locale.ROOT, "%." + digits + "f", v);
    }

    private static String wkt(double[][] p) {
        StringBuilder sb = new StringBuilder("POLYGON((");
        for (int i = 0; i <= p.length; i++) {
            double[] q = p[i % p.length];
            if (i > 0) {
                sb.append(',');
            }
            sb.append(fmt(q[0], 2)).append(' ').append(fmt(q[1], 2));
        }
        return sb.append("))").toString();
    }
}
