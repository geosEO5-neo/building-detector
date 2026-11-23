// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.gui.layer.ImageryLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.buildingdetector.BuildingCreator.CreationResult;
import org.openstreetmap.josm.plugins.buildingdetector.ImageGeoreference.GroundResolution;
import org.openstreetmap.josm.plugins.buildingdetector.ImageProcessor.DetectionConfig;
import org.openstreetmap.josm.tools.Shortcut;

import static org.openstreetmap.josm.tools.I18n.tr;
import static org.openstreetmap.josm.tools.I18n.trn;

/**
 * JOSM-Action für die semi-automatische Gebäudedetektion in HOT-Tasking-Manager-Aufgaben.
 *
 * Verbesserungen:
 * - Rendert den reinen ImageryLayer offscreen (ohne störende OSM-Vektor-Overlays oder Mauszeiger)
 * - Unterstützt alle Luftbild-Quellen (Bing, Esri, Maxar, OpenAerialMap, Custom TMS)
 * - Bietet einen kompakten Fernerkundungs-Parameterdialog (Kanten-Schwellenwert, Schattenwurf-Gewicht,
 *   Mindest-/Maximalfläche in m², Nachbarschafts-Fluchtausrichtung)
 * - Begrenzt die Suche wahlweise auf den heruntergeladenen Bereich, die Auswahl oder den Kartenausschnitt
 * - Friert die Georeferenzierung beim Rendern ein ({@link ImageGeoreference}), sodass Verschieben oder
 *   Zoomen während der Berechnung die Lage der Ergebnisse nicht verfälscht
 * - Vollständig Thread-sicher zwischen Swing-EDT und Worker-Thread
 */
public class BuildingDetectorAction extends JosmAction {

    private static final long serialVersionUID = 1L;

    /** Gröbste Bodenauflösung, bei der eine Erkennung noch zugelassen wird (m/px). */
    private static final double MAX_GSD_M_PER_PX = 1.2;

    /** Toleranzen, bis zu denen die Abbildung Bild → Boden als winkeltreu gilt. */
    private static final double MAX_SCALE_ANISOTROPY = 0.02;
    private static final double MAX_AXIS_SKEW_DEG = 1.0;

    /** Bereich, in dem nach Gebäuden gesucht wird. */
    private enum SearchArea {
        DOWNLOADED, SELECTION, VIEW;

        /** Beschriftung im Parameterdialog. */
        @Override
        public String toString() {
            switch (this) {
                case DOWNLOADED: return tr("Heruntergeladener Bereich (im Kartenausschnitt)");
                case SELECTION: return tr("Umgebendes Rechteck der Auswahl");
                default: return tr("Gesamter Kartenausschnitt");
            }
        }
    }

    /** Gerendertes Luftbild samt der zum Renderzeitpunkt gültigen Georeferenzierung. */
    private record Capture(BufferedImage image, ImageGeoreference georeference) {
    }

    private final ImageProcessor imageProcessor;
    private final BuildingCreator buildingCreator;

    // Persistente Parameter innerhalb der Sitzung für schnelles Arbeiten im HOT Tasking Manager
    // Startwerte sind die Standardwerte der Erkennung, mit denen auch "ant eval-real" misst.
    private static final DetectionConfig DEFAULTS = new DetectionConfig();
    private double lastContrastThreshold = DEFAULTS.contrastThreshold;
    private double lastShadowWeight = DEFAULTS.shadowWeight;
    private double lastMinAreaM2 = DEFAULTS.minBuildingAreaM2;
    private double lastMaxAreaM2 = DEFAULTS.maxBuildingAreaM2;
    private boolean lastAlignNeighbors = DEFAULTS.alignWithNeighbors;
    private boolean lastShowDebug = false;
    private SearchArea lastSearchArea = null;

    public BuildingDetectorAction() {
        super(
            tr("Detect Buildings (HOT Semi-Auto)"),
            "building_detector",
            tr("Semi-automatische Gebäudedetektion aus Luftbildern (Kanten, Schattenwurf, Nachbarschaft)"),
            Shortcut.registerShortcut(
                "tools:buildingdetector",
                tr("Tool: {0}", tr("Building Detector")),
                KeyEvent.VK_B,
                Shortcut.CTRL_SHIFT
            ),
            true
        );

        this.imageProcessor = new ImageProcessor();
        this.buildingCreator = new BuildingCreator();
        setEnabled(true);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        OsmDataLayer dataLayer = MainApplication.getLayerManager().getEditLayer();
        if (dataLayer == null) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Bitte laden Sie zuerst OSM-Daten oder erstellen Sie eine neue Datenebene."),
                tr("Keine Datenebene"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        ImageryLayer imageryLayer = findImageryLayer();
        if (imageryLayer == null) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Keine sichtbare Luftbild-Ebene gefunden. Bitte aktivieren Sie Bing, Esri, Maxar oder OAM."),
                tr("Keine Luftbilder"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }

        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            return;
        }
        MapView mapView = MainApplication.getMap().mapView;

        // Vorläufige Bodenauflösung in der Ansichtsmitte (für Zoom-Prüfung und Dialoganzeige).
        // Maßgeblich für die Berechnung ist später die Auflösung im Suchbereich des gerenderten Bildes.
        ImageGeoreference preview = new ImageGeoreference(mapView.getState(), mapView.getWidth(), mapView.getHeight());
        GroundResolution resolution = preview.groundResolutionAt(0.5 * mapView.getWidth(), 0.5 * mapView.getHeight());
        double currentGsd = resolution.getMeanMetersPerPixel();
        if (!(currentGsd > 0.0)) { // auch NaN
            showError(tr("Die Bodenauflösung der Kartenansicht konnte nicht bestimmt werden."));
            return;
        }
        if (currentGsd > MAX_GSD_M_PER_PX) {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                tr("Bitte zoomen Sie näher heran (aktuelle Bodenauflösung: {0} m/px). Für Gebäudedetektion wird < 0.8 m/px empfohlen.",
                   String.format("%.2f", currentGsd)),
                tr("Zoom-Level zu niedrig"),
                JOptionPane.WARNING_MESSAGE
            );
            return;
        }
        if (!confirmNonConformalProjection(resolution)) {
            return;
        }

        // Kompaktes Tuning-Panel auf dem EDT anzeigen
        DetectionConfig config = promptDetectionConfig(currentGsd, imageryLayer.getName(), dataLayer.getDataSet());
        if (config == null) {
            return; // Vom Benutzer abgebrochen
        }

        performBuildingDetection(imageryLayer, dataLayer, config, lastSearchArea);
    }

    /**
     * Die Rechteckanpassung erfolgt im Bild. Sie ist nur zulässig, wenn das Bild den Boden lokal
     * winkeltreu abbildet (quadratische Pixel, rechtwinklige Pixelachsen). Bei nicht winkeltreuen
     * Kartenprojektionen (z. B. EPSG:4326) wird der Benutzer gewarnt.
     *
     * @return true, wenn fortgefahren werden soll
     */
    private boolean confirmNonConformalProjection(GroundResolution resolution) {
        if (resolution.isConformal(MAX_SCALE_ANISOTROPY, MAX_AXIS_SKEW_DEG)) {
            return true;
        }
        int choice = JOptionPane.showConfirmDialog(
            MainApplication.getMainFrame(),
            tr("Die aktive Kartenprojektion bildet den Boden hier nicht winkeltreu ab\n"
             + "(Bodenauflösung {0} m/px in x- und {1} m/px in y-Richtung).\n\n"
             + "Rechtwinklige Gebäude erscheinen im Bild verzerrt, erkannte Umrisse werden am Boden\n"
             + "nicht rechtwinklig. Empfohlen wird eine winkeltreue Projektion (z. B. Mercator).\n\n"
             + "Trotzdem fortfahren?",
               String.format("%.3f", resolution.getMetersPerPixelX()),
               String.format("%.3f", resolution.getMetersPerPixelY())),
            tr("Projektion nicht winkeltreu"),
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        );
        return choice == JOptionPane.YES_OPTION;
    }

    /**
     * Ermittelt die im aktuellen Zustand wählbaren Suchbereiche. Der heruntergeladene Bereich steht an
     * erster Stelle: Nur dort sind vorhandene OSM-Gebäude bekannt und Duplikate vermeidbar.
     */
    private static List<SearchArea> availableSearchAreas(DataSet dataSet) {
        List<SearchArea> areas = new ArrayList<>(3);
        if (dataSet != null && !dataSet.getDataSourceBounds().isEmpty()) {
            areas.add(SearchArea.DOWNLOADED);
        }
        if (dataSet != null && !dataSet.getSelected().isEmpty()) {
            areas.add(SearchArea.SELECTION);
        }
        areas.add(SearchArea.VIEW);
        return areas;
    }

    /**
     * Zeigt einen übersichtlichen Parameter-Dialog für Kanten-, Schatten- und Nachbarschaftsparameter.
     */
    private DetectionConfig promptDetectionConfig(double currentGsd, String imageryName, DataSet dataSet) {
        JPanel panel = new JPanel(new GridLayout(0, 2, 8, 6));

        List<SearchArea> searchAreas = availableSearchAreas(dataSet);
        JComboBox<SearchArea> searchAreaCombo = new JComboBox<>(searchAreas.toArray(new SearchArea[0]));
        // Letzte Wahl beibehalten, sofern noch verfügbar. Die Auswahl wird nie automatisch vorbelegt:
        // nach einem Lauf sind die neu erzeugten Gebäude selektiert, ein Folgelauf würde sonst nur
        // deren Umgebung absuchen.
        if (lastSearchArea != null && lastSearchArea != SearchArea.SELECTION && searchAreas.contains(lastSearchArea)) {
            searchAreaCombo.setSelectedItem(lastSearchArea);
        } else {
            searchAreaCombo.setSelectedItem(searchAreas.contains(SearchArea.DOWNLOADED) ? SearchArea.DOWNLOADED : SearchArea.VIEW);
        }

        JSpinner contrastSpinner = new JSpinner(new SpinnerNumberModel(lastContrastThreshold, 0.02, 0.30, 0.01));
        JSpinner shadowSpinner = new JSpinner(new SpinnerNumberModel(lastShadowWeight, 0.00, 0.80, 0.05));
        JSpinner minAreaSpinner = new JSpinner(new SpinnerNumberModel(lastMinAreaM2, 4.0, 500.0, 2.0));
        JSpinner maxAreaSpinner = new JSpinner(new SpinnerNumberModel(lastMaxAreaM2, 50.0, 10000.0, 50.0));
        JCheckBox alignCheck = new JCheckBox(tr("An Nachbarschaftsflucht ausrichten"), lastAlignNeighbors);
        JCheckBox debugCheck = new JCheckBox(tr("Fernerkundungs-Maske anzeigen (Debug)"), lastShowDebug);

        panel.add(new JLabel(tr("Aktives Luftbild:")));
        panel.add(new JLabel(imageryName + String.format(" (GSD: %.2f m/px)", currentGsd)));
        panel.add(new JLabel(tr("Suchbereich:")));
        panel.add(searchAreaCombo);
        panel.add(new JLabel(tr("Canny-Kanten-Schwellenwert (0.04 - 0.15):")));
        panel.add(contrastSpinner);
        panel.add(new JLabel(tr("3D-Schattenwurf-Gewichtung (0.0 - 0.8):")));
        panel.add(shadowSpinner);
        panel.add(new JLabel(tr("Minimale Gebäudefläche (m²):")));
        panel.add(minAreaSpinner);
        panel.add(new JLabel(tr("Maximale Gebäudefläche (m²):")));
        panel.add(maxAreaSpinner);
        panel.add(alignCheck);
        panel.add(debugCheck);

        int res = JOptionPane.showConfirmDialog(
            MainApplication.getMainFrame(),
            panel,
            tr("HOT Building Detector – Fernerkundungs-Parameter"),
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE
        );

        if (res != JOptionPane.OK_OPTION) {
            return null;
        }

        lastContrastThreshold = ((Number) contrastSpinner.getValue()).doubleValue();
        lastShadowWeight = ((Number) shadowSpinner.getValue()).doubleValue();
        lastMinAreaM2 = ((Number) minAreaSpinner.getValue()).doubleValue();
        lastMaxAreaM2 = ((Number) maxAreaSpinner.getValue()).doubleValue();
        lastAlignNeighbors = alignCheck.isSelected();
        lastShowDebug = debugCheck.isSelected();
        lastSearchArea = (SearchArea) searchAreaCombo.getSelectedItem();

        DetectionConfig config = new DetectionConfig();
        config.contrastThreshold = lastContrastThreshold;
        config.shadowWeight = lastShadowWeight;
        config.minBuildingAreaM2 = lastMinAreaM2;
        config.maxBuildingAreaM2 = lastMaxAreaM2;
        config.metersPerPixel = currentGsd;
        config.alignWithNeighbors = lastAlignNeighbors;
        config.showDebugWindows = lastShowDebug;
        return config;
    }

    /**
     * Extrahiert das reine Luftbild auf dem EDT und führt die Detektion asynchron aus.
     */
    private void performBuildingDetection(
            ImageryLayer imageryLayer, OsmDataLayer dataLayer, DetectionConfig config, SearchArea searchArea) {
        // 1. Sauberes Offscreen-Rendering des ImageryLayers ohne OSM-Daten-Overlay.
        //    Die Georeferenzierung wird im selben Schritt eingefroren.
        Capture capture = extractPureImagery(imageryLayer);
        if (capture == null) {
            showError(tr("Konnte kein Luftbild extrahieren. Stellen Sie sicher, dass die Luftbild-Ebene sichtbar ist."));
            return;
        }
        BufferedImage aerialImage = capture.image();
        ImageGeoreference georeference = capture.georeference();
        DataSet dataSet = dataLayer.getDataSet();

        // 2. Suchbereich in ein Pixelfenster (ROI) des gerenderten Bildes umrechnen
        Rectangle roi = resolveRegionOfInterest(searchArea, dataSet, georeference);
        if (roi != null && roi.isEmpty()) {
            showError(tr("Der gewählte Suchbereich liegt außerhalb des sichtbaren Kartenausschnitts."));
            return;
        }
        config.regionOfInterest = roi;

        // Nur vollständig im heruntergeladenen Bereich liegende Gebäude übernehmen
        Area allowedArea = (searchArea == SearchArea.DOWNLOADED) ? dataSet.getDataSourceArea() : null;

        // 3. Flächentreue Bodenauflösung im Zentrum des Suchbereichs (Flächenschwellen in m²)
        double centerX = (roi != null) ? roi.getCenterX() : 0.5 * aerialImage.getWidth();
        double centerY = (roi != null) ? roi.getCenterY() : 0.5 * aerialImage.getHeight();
        double gsd = georeference.groundResolutionAt(centerX, centerY).getMeanMetersPerPixel();
        if (gsd > 0.0) {
            config.metersPerPixel = gsd;
        }

        // 4. Hintergrund-Thread für rechenintensive Canny-, Schatten- & Nachbarschaftsanalyse
        Thread detectionThread = new Thread(() -> {
            try {
                List<BuildingContour> detectedBuildings = imageProcessor.detectBuildings(aerialImage, config);

                // 5. Erstellung der OSM-Objekte und UndoRedo-Registrierung zurück auf dem Swing-EDT
                SwingUtilities.invokeLater(() -> {
                    CreationResult result = buildingCreator.createOSMBuildings(
                        detectedBuildings, dataSet, georeference, allowedArea);
                    MainApplication.getMap().repaint();

                    StringBuilder message = new StringBuilder(
                        tr("{0} Gebäude erkannt, orthogonalisiert und ausgewählt.", result.created()));
                    if (result.skippedExisting() > 0) {
                        message.append('\n').append(
                            trn("{0} Kandidat verworfen: dort ist bereits ein Gebäude kartiert.",
                                "{0} Kandidaten verworfen: dort ist bereits ein Gebäude kartiert.",
                                result.skippedExisting(), result.skippedExisting()));
                    }
                    if (result.skippedOutsideArea() > 0) {
                        message.append('\n').append(
                            trn("{0} Kandidat verworfen: nicht vollständig im heruntergeladenen Bereich.",
                                "{0} Kandidaten verworfen: nicht vollständig im heruntergeladenen Bereich.",
                                result.skippedOutsideArea(), result.skippedOutsideArea()));
                    }
                    message.append('\n').append(tr("(Tipp: Mit Strg+Z rückgängig machen oder Auswahl direkt prüfen)"));

                    JOptionPane.showMessageDialog(
                        MainApplication.getMainFrame(),
                        message.toString(),
                        tr("Erkennung abgeschlossen"),
                        JOptionPane.INFORMATION_MESSAGE
                    );
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                showError(tr("Fehler bei der Bildverarbeitung: {0}", ex.getMessage()));
            }
        }, "JOSM-BuildingDetector-Worker");

        detectionThread.start();
    }

    /**
     * Rechnet den gewählten Suchbereich in ein Pixelfenster des gerenderten Bildes um.
     *
     * @return {@code null} für das gesamte Bild, ein leeres Rechteck, wenn der Bereich außerhalb liegt
     */
    private static Rectangle resolveRegionOfInterest(SearchArea searchArea, DataSet dataSet, ImageGeoreference georeference) {
        if (searchArea == null || searchArea == SearchArea.VIEW || dataSet == null) {
            return null;
        }

        Rectangle roi = new Rectangle();
        if (searchArea == SearchArea.DOWNLOADED) {
            // Vereinigung der Pixelfenster aller heruntergeladenen Rechtecke. Lücken dazwischen werden
            // später über den exakten heruntergeladenen Bereich ausgeschlossen.
            for (Bounds bounds : dataSet.getDataSourceBounds()) {
                Rectangle part = georeference.boundsToPixelRectangle(bounds);
                if (!part.isEmpty()) {
                    roi = roi.isEmpty() ? part : roi.union(part);
                }
            }
        } else {
            BBox selectionBox = new BBox();
            for (OsmPrimitive primitive : dataSet.getSelected()) {
                if (!primitive.isIncomplete()) {
                    selectionBox.add(primitive.getBBox());
                }
            }
            if (selectionBox.isValid()) {
                roi = georeference.boundsToPixelRectangle(new Bounds(
                    selectionBox.getMinLat(), selectionBox.getMinLon(),
                    selectionBox.getMaxLat(), selectionBox.getMaxLon()));
            }
        }
        return roi;
    }

    /**
     * Rendert ausschließlich den ImageryLayer in ein Offscreen-BufferedImage, sodass
     * bestehende OSM-Linien, Nodes, Auswahl-Highlights und UI-Elemente das Kantenbild nicht verfälschen.
     * Der Zustand der Kartenansicht wird im selben EDT-Schritt wie das Rendern festgehalten.
     */
    private Capture extractPureImagery(ImageryLayer imageryLayer) {
        if (MainApplication.getMap() == null || MainApplication.getMap().mapView == null) {
            return null;
        }
        MapView mapView = MainApplication.getMap().mapView;
        int w = mapView.getWidth();
        int h = mapView.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }

        try {
            AtomicReference<Capture> ref = new AtomicReference<>();
            Runnable renderTask = () -> {
                MapViewState state = mapView.getState();
                BufferedImage offscreen = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2d = offscreen.createGraphics();
                try {
                    Bounds bounds = mapView.getRealBounds();
                    imageryLayer.paint(g2d, mapView, bounds);
                } finally {
                    g2d.dispose();
                }
                ref.set(new Capture(offscreen, new ImageGeoreference(state, w, h)));
            };

            if (SwingUtilities.isEventDispatchThread()) {
                renderTask.run();
            } else {
                SwingUtilities.invokeAndWait(renderTask);
            }

            Capture rendered = ref.get();
            if (rendered != null) {
                return rendered;
            }
        } catch (Exception ex) {
            // Fallback auf Robot-Screenshot falls das direkte Layer-Painting fehlschlägt
            ex.printStackTrace();
        }

        try {
            MapViewState state = mapView.getState();
            Point screenPos = mapView.getLocationOnScreen();
            Rectangle rect = new Rectangle(screenPos.x, screenPos.y, w, h);
            return new Capture(new Robot().createScreenCapture(rect), new ImageGeoreference(state, w, h));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Findet die oberste sichtbare Luftbild-Ebene (unterstützt Bing, Esri, Maxar, OAM, Custom TMS etc.).
     */
    private ImageryLayer findImageryLayer() {
        List<Layer> layers = MainApplication.getLayerManager().getLayers();
        for (Layer layer : layers) {
            if (layer instanceof ImageryLayer && layer.isVisible()) {
                return (ImageryLayer) layer;
            }
        }
        return null;
    }

    private void showError(String message) {
        SwingUtilities.invokeLater(() -> {
            JOptionPane.showMessageDialog(
                MainApplication.getMainFrame(),
                message,
                tr("Fehler"),
                JOptionPane.ERROR_MESSAGE
            );
        });
    }
}