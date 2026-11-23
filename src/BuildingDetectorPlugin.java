// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Logging;

/**
 * Building Detector Plugin für JOSM.
 *
 * Semi-automatische Fernerkundungs-Erkennung von Gebäuden aus Luftbildern
 * (Canny-Kantendetektion, 3D-Schattenwurf-Verifikation, Nachbarschafts-Konsens
 * und geodätische Orthogonalisierung für HOT-Tasking-Manager-Aufgaben).
 */
public class BuildingDetectorPlugin extends Plugin {

    private final BuildingDetectorAction action;

    /**
     * Konstruktor für das Building Detector Plugin.
     *
     * @param info Plugin-Informationen von JOSM
     */
    public BuildingDetectorPlugin(PluginInformation info) {
        super(info);

        this.action = new BuildingDetectorAction();

        // Saubere Integration in das JOSM-"Werkzeuge" (Tools)-Menü ohne doppelte Menüleisten-Einträge
        if (MainApplication.getMenu() != null && MainApplication.getMenu().toolsMenu != null) {
            MainMenu.add(MainApplication.getMenu().toolsMenu, action, false);
        }
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        super.mapFrameInitialized(oldFrame, newFrame);
        action.setEnabled(newFrame != null);
        if (newFrame != null) {
            Logging.info("Building Detector Plugin (HOT Remote Sensing Edition) bereit.");
        }
    }
}