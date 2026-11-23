package org.openstreetmap.josm.plugins.buildingdetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.swing.Action;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.DataSource;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.imagery.ImageryInfo;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.BoundingXYVisitor;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;

/**
 * End-to-End-Test der Action in einer echten JOSM-Instanz mit einem synthetischen, georeferenzierten
 * Luftbild. Benötigt eine grafische Oberfläche (unter Linux z. B. {@code xvfb-run ant test-gui}) und
 * läuft deshalb nicht im normalen {@code ant test}.
 *
 * JOSM wird offline und mit einem temporären Profilverzeichnis gestartet; das eigene JOSM-Profil
 * bleibt unberührt.
 */
@Tag("gui")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class BuildingDetectorActionGuiTest {

    private static final double SCALE = 0.45;      // Projektionseinheiten pro Pixel, ca. 0,30 m/px in Wien
    private static final double GSD = 0.30;
    private static final int MARGIN = 30;          // Abstand des Testbildes vom Rand der Kartenansicht
    private static final int COLUMN_SPACING = 150;
    private static final int ROW_SPACING = 140;
    private static final int FIRST_CENTER = 80;
    private static final int JITTER = 12;

    private static DataSet dataSet;
    private static BuildingDetectorAction action;
    private static EastNorth imageTopLeft;
    private static List<double[][]> truth;
    private static int sceneWidth;
    private static int sceneHeight;
    private static int columns;
    private static int rows;

    /** Luftbild-Ebene, die das synthetische Bild lagerichtig in die Kartenansicht zeichnet. */
    private static final class SyntheticImageryLayer extends ImageryLayer {
        private final BufferedImage image;
        private final EastNorth topLeft;

        SyntheticImageryLayer(BufferedImage image, EastNorth topLeft) {
            super(new ImageryInfo("Synthetic Test Imagery"));
            this.image = image;
            this.topLeft = topLeft;
        }

        @Override
        public void paint(Graphics2D g, MapView mv, Bounds box) {
            MapViewState state = mv.getState();
            Point2D origin = state.getPointFor(topLeft).getInView();
            double zoom = SCALE / state.getScale();
            AffineTransform transform = new AffineTransform();
            transform.translate(origin.getX(), origin.getY());
            transform.scale(zoom, zoom);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, transform, null);
        }

        @Override protected Action getAdjustAction() { return null; }
        @Override protected List<OffsetMenuEntry> getOffsetMenuEntries() { return Collections.emptyList(); }
        @Override public Icon getIcon() { return null; }
        @Override public String getToolTipText() { return "synthetic"; }
        @Override public void mergeFrom(Layer from) { }
        @Override public boolean isMergable(Layer other) { return false; }
        @Override public void visitBoundingBox(BoundingXYVisitor v) { }
        @Override public Action[] getMenuEntries() { return new Action[0]; }
    }

    @BeforeAll
    static void startJosm() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "GUI-Test benötigt eine grafische Oberfläche");
        if (System.getProperty("josm.home") == null) {
            System.setProperty("josm.home", Files.createTempDirectory("buildingdetector-josm-home").toString());
        }
        MainApplication.main(new String[] {"--offline=ALL"});
        long deadline = System.currentTimeMillis() + 60_000;
        while (MainApplication.getMainFrame() == null || !MainApplication.getMainFrame().isVisible()) {
            assertTrue(System.currentTimeMillis() < deadline, "JOSM-Hauptfenster erscheint nicht");
            Thread.sleep(200);
        }
        Thread.sleep(1000);

        dataSet = new DataSet();
        SwingUtilities.invokeAndWait(() -> {
            ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:3857"));
            MainApplication.getLayerManager().addLayer(new OsmDataLayer(dataSet, "building-detector-test", null));
            MainApplication.getMap().mapView.zoomTo(
                ProjectionRegistry.getProjection().latlon2eastNorth(JosmTestSupport.VIENNA), SCALE);
        });
        Thread.sleep(500);

        int[] viewSize = new int[2];
        SwingUtilities.invokeAndWait(() -> {
            viewSize[0] = MainApplication.getMap().mapView.getWidth();
            viewSize[1] = MainApplication.getMap().mapView.getHeight();
        });
        // Testszene an die tatsächliche Größe der Kartenansicht anpassen
        sceneWidth = viewSize[0] - 2 * MARGIN;
        sceneHeight = viewSize[1] - 2 * MARGIN;
        columns = (sceneWidth - 2 * FIRST_CENTER - JITTER) / COLUMN_SPACING + 1;
        rows = (sceneHeight - 2 * FIRST_CENTER - JITTER) / ROW_SPACING + 1;
        assumeTrue(columns >= 2 && rows >= 2, "Kartenansicht zu klein für die Testszene: " + viewSize[0] + "x" + viewSize[1]);

        Random rnd = new Random(11);
        truth = new ArrayList<>();
        for (int gy = 0; gy < rows; gy++) {
            for (int gx = 0; gx < columns; gx++) {
                truth.add(SyntheticScene.rectangle(
                    FIRST_CENTER + gx * COLUMN_SPACING + rnd.nextDouble() * JITTER,
                    FIRST_CENTER + gy * ROW_SPACING + rnd.nextDouble() * JITTER,
                    40 + rnd.nextDouble() * 20, 26 + rnd.nextDouble() * 10, rnd.nextDouble() * 90 - 45));
            }
        }
        BufferedImage image = SyntheticScene.render(truth, sceneWidth, sceneHeight, 120, 225, 4, true).image;

        SwingUtilities.invokeAndWait(() -> {
            MapViewState state = MainApplication.getMap().mapView.getState();
            // Bildpixel (0, 0) liegt exakt auf dem Ansichtspixel (MARGIN, MARGIN)
            imageTopLeft = state.getForView(MARGIN, MARGIN).getEastNorth();
            MainApplication.getLayerManager().addLayer(new SyntheticImageryLayer(image, imageTopLeft));
            MainApplication.getLayerManager().setActiveLayer(
                MainApplication.getLayerManager().getLayersOfType(OsmDataLayer.class).get(0));
            // Heruntergeladener Bereich: nur die linken Spalten des Bildes
            Bounds downloaded = imageBounds(5, 5, downloadedLimitX(), sceneHeight - 5);
            dataSet.addDataSource(new DataSource(downloaded, "test"));
            action = new BuildingDetectorAction();
        });
    }

    // ---------------------------------------------------------------- Hilfsfunktionen

    /** Bildspalte, an der der heruntergeladene Bereich endet (mittig in der Lücke zwischen zwei Gebäudespalten). */
    private static double downloadedLimitX() {
        int lastColumnInside = Math.max(0, columns / 2 - 1);
        return FIRST_CENTER + (lastColumnInside + 0.5) * COLUMN_SPACING + JITTER / 2.0;
    }

    private static LatLon imageToLatLon(double x, double y) {
        return JosmTestSupport.inverseWebMercator(imageTopLeft.east() + x * SCALE, imageTopLeft.north() - y * SCALE);
    }

    private static Bounds imageBounds(double x0, double y0, double x1, double y1) {
        LatLon northWest = imageToLatLon(x0, y0);
        LatLon southEast = imageToLatLon(x1, y1);
        return new Bounds(southEast.lat(), northWest.lon(), northWest.lat(), southEast.lon());
    }

    /** Verschiebt die Karte so, dass der Bildursprung auf der angegebenen Ansichtsposition liegt. */
    private static void anchorImageAt(double viewX, double viewY) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MapView mv = MainApplication.getMap().mapView;
            mv.zoomTo(new EastNorth(
                imageTopLeft.east() + (mv.getWidth() / 2.0 - viewX) * SCALE,
                imageTopLeft.north() - (mv.getHeight() / 2.0 - viewY) * SCALE), SCALE);
        });
        Thread.sleep(300);
    }

    private static <T extends Component> void collect(Container container, Class<T> type, List<T> out) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
            if (child instanceof Container) {
                collect((Container) child, type, out);
            }
        }
    }

    private static JDialog waitForDialog(String titlePart) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog && window.isVisible()
                        && ((JDialog) window).getTitle() != null && ((JDialog) window).getTitle().contains(titlePart)) {
                    return (JDialog) window;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Dialog '" + titlePart + "' ist nicht erschienen");
    }

    private static JOptionPane optionPane(JDialog dialog) {
        List<JOptionPane> panes = new ArrayList<>();
        collect(dialog.getContentPane(), JOptionPane.class, panes);
        assertEquals(1, panes.size(), "Dialog enthält genau ein JOptionPane");
        return panes.get(0);
    }

    /** Schließt einen JOptionPane-Dialog sprachunabhängig mit dem angegebenen Ergebnis. */
    private static void close(JDialog dialog, int option) {
        JOptionPane pane = optionPane(dialog);
        SwingUtilities.invokeLater(() -> pane.setValue(option));
    }

    @SuppressWarnings("unchecked")
    private static JComboBox<Object> searchAreaCombo(JDialog dialog) {
        List<JComboBox> combos = new ArrayList<>();
        collect(dialog.getContentPane(), JComboBox.class, combos);
        assertEquals(1, combos.size(), "Parameterdialog enthält die Suchbereich-Auswahl");
        return combos.get(0);
    }

    private static List<String> searchAreaNames(JComboBox<Object> combo) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            names.add(((Enum<?>) combo.getItemAt(i)).name());
        }
        return names;
    }

    private static String selectedSearchArea(JComboBox<Object> combo) {
        return ((Enum<?>) combo.getSelectedItem()).name();
    }

    private static JDialog openParameterDialog() throws InterruptedException {
        SwingUtilities.invokeLater(() -> action.actionPerformed(null));
        return waitForDialog("Building Detector");
    }

    /** Wartet auf die Abschlussmeldung, schließt sie und liefert ihren Text. */
    private static String finishRun() throws InterruptedException {
        JDialog done = waitForDialog("Erkennung abgeschlossen");
        String message = String.valueOf(optionPane(done).getMessage());
        close(done, JOptionPane.OK_OPTION);
        Thread.sleep(300);
        return message;
    }

    private static List<double[][]> truthInside(double x0, double y0, double x1, double y1) {
        List<double[][]> inside = new ArrayList<>();
        for (double[][] building : truth) {
            boolean in = true;
            for (double[] c : building) {
                in &= c[0] > x0 + 2 && c[0] < x1 - 2 && c[1] > y0 + 2 && c[1] < y1 - 2;
            }
            if (in) {
                inside.add(building);
            }
        }
        return inside;
    }

    private static List<Way> buildings() {
        List<Way> ways = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            if (way.hasKey("building")) {
                ways.add(way);
            }
        }
        return ways;
    }

    /** Eckpunkt-RMSE in Metern zwischen Soll-Gebäuden und den nächstgelegenen Ways. */
    private static double cornerRmseMeters(List<double[][]> expected) {
        double sumSq = 0;
        for (double[][] building : expected) {
            double best = Double.MAX_VALUE;
            for (Way way : buildings()) {
                for (int dir = 0; dir < 2; dir++) {
                    for (int shift = 0; shift < 4; shift++) {
                        double sq = 0;
                        for (int i = 0; i < 4; i++) {
                            int j = dir == 0 ? (i + shift) % 4 : ((shift - i) % 4 + 4) % 4;
                            double d = imageToLatLon(building[i][0], building[i][1]).greatCircleDistance(way.getNode(j).getCoor());
                            sq += d * d / 4;
                        }
                        best = Math.min(best, sq);
                    }
                }
            }
            sumSq += best;
        }
        return Math.sqrt(sumSq / expected.size());
    }

    // ---------------------------------------------------------------- Tests

    @Test
    @Order(1)
    void downloadedAreaIsDefaultAndPanningDuringDetectionDoesNotMoveResults() throws Exception {
        JDialog dialog = openParameterDialog();
        JComboBox<Object> combo = searchAreaCombo(dialog);
        assertEquals(List.of("DOWNLOADED", "VIEW"), searchAreaNames(combo), "ohne Auswahl: zwei Suchbereiche");
        assertEquals("DOWNLOADED", selectedSearchArea(combo), "Standard ist der heruntergeladene Bereich");

        close(dialog, JOptionPane.OK_OPTION);
        // Liegt in der Ereigniswarteschlange vor dem Einfügen der Ergebnisse: Der Benutzer verschiebt und
        // zoomt die Karte, während die Erkennung im Hintergrund läuft.
        SwingUtilities.invokeLater(() -> {
            MapView mv = MainApplication.getMap().mapView;
            mv.zoomTo(mv.getState().getForView(333.3, 211.7).getEastNorth(), SCALE * 1.7);
        });
        finishRun();

        List<double[][]> expected = truthInside(5, 5, downloadedLimitX(), sceneHeight - 5);
        assertFalse(expected.isEmpty());
        assertTrue(expected.size() < truth.size(), "ein Teil der Szene liegt außerhalb des heruntergeladenen Bereichs");
        assertEquals(expected.size(), buildings().size(), "genau die Gebäude im heruntergeladenen Bereich");
        double rmse = cornerRmseMeters(expected);
        assertTrue(rmse < 0.8 * GSD, "Eckpunkt-RMSE " + rmse + " m trotz Verschieben während der Berechnung");
        for (Way way : buildings()) {
            assertTrue(way.isClosed() && way.getNodesCount() == 5);
        }
        assertEquals(buildings().size(), dataSet.getSelected().size(), "neue Gebäude sind ausgewählt");
    }

    @Test
    @Order(2)
    void secondRunCreatesNoDuplicatesAndSelectionIsNotPreselected() throws Exception {
        // Nicht ganzzahlige Lage: das Luftbild wird beim Rendern neu abgetastet
        anchorImageAt(MARGIN + 0.3, MARGIN - 0.3);
        int before = buildings().size();

        JDialog dialog = openParameterDialog();
        JComboBox<Object> combo = searchAreaCombo(dialog);
        assertEquals(List.of("DOWNLOADED", "SELECTION", "VIEW"), searchAreaNames(combo));
        assertEquals("DOWNLOADED", selectedSearchArea(combo), "die Auswahl des letzten Laufs wird nicht vorbelegt");
        close(dialog, JOptionPane.OK_OPTION);
        finishRun();

        assertEquals(before, buildings().size(), "bereits kartierte Gebäude werden nicht doppelt angelegt");
    }

    @Test
    @Order(3)
    void undoRemovesTheRunAndSelectionLimitsTheSearchArea() throws Exception {
        SwingUtilities.invokeAndWait(() -> UndoRedoHandler.getInstance().undo());
        assertTrue(buildings().isEmpty(), "ein Rückgängig-Schritt entfernt den gesamten Lauf");

        anchorImageAt(MARGIN - 5, MARGIN + 5);
        // Hilfslinie über die erste Gebäudezeile und die ersten beiden Spalten; ihr umgebendes Rechteck ist der Suchbereich
        double x1 = columns >= 3 ? FIRST_CENTER + 1.5 * COLUMN_SPACING + JITTER / 2.0 : sceneWidth - 5;
        double y1 = FIRST_CENTER + 0.5 * ROW_SPACING + JITTER / 2.0;
        SwingUtilities.invokeAndWait(() -> {
            Node a = new Node(imageToLatLon(10, 10));
            Node b = new Node(imageToLatLon(x1, y1));
            Way helper = new Way();
            dataSet.addPrimitive(a);
            dataSet.addPrimitive(b);
            helper.addNode(a);
            helper.addNode(b);
            dataSet.addPrimitive(helper);
            dataSet.setSelected(helper);
        });

        JDialog dialog = openParameterDialog();
        JComboBox<Object> combo = searchAreaCombo(dialog);
        SwingUtilities.invokeAndWait(() -> combo.setSelectedIndex(searchAreaNames(combo).indexOf("SELECTION")));
        close(dialog, JOptionPane.OK_OPTION);
        finishRun();

        List<double[][]> expected = truthInside(10, 10, x1, y1);
        assertFalse(expected.isEmpty());
        assertEquals(expected.size(), buildings().size(), "nur Gebäude im Rechteck der Auswahl");
        assertTrue(cornerRmseMeters(expected) < 0.8 * GSD);
    }

    @Test
    @Order(4)
    void nonConformalProjectionTriggersAWarningAndCanBeCancelled() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ProjectionRegistry.setProjection(Projections.getProjectionByCode("EPSG:4326"));
            MainApplication.getMap().mapView.zoomTo(
                new EastNorth(JosmTestSupport.VIENNA.lon(), JosmTestSupport.VIENNA.lat()), 0.3 / 111320.0);
        });
        Thread.sleep(300);
        int before = dataSet.getWays().size();

        SwingUtilities.invokeLater(() -> action.actionPerformed(null));
        JDialog warning = waitForDialog("nicht winkeltreu");
        assertTrue(String.valueOf(optionPane(warning).getMessage()).contains("m/px"), "Warnung nennt beide Bodenauflösungen");
        close(warning, JOptionPane.NO_OPTION);
        Thread.sleep(800);

        for (Window window : Window.getWindows()) {
            assertFalse(window instanceof JDialog && window.isVisible(), "nach 'Nein' erscheint kein weiterer Dialog");
        }
        assertEquals(before, dataSet.getWays().size());
    }
}
