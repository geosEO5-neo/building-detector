// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.preferences.JosmBaseDirectories;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.ProjectionRegistry;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

/**
 * Minimale JOSM-Umgebung für Tests ohne Benutzeroberfläche: Einstellungen nur im Speicher,
 * Kartenprojektion frei wählbar. Es wird kein JOSM-Fenster geöffnet.
 */
final class JosmTestSupport {

    /** Testgebiet: Wien, Stephansplatz. */
    static final LatLon VIENNA = new LatLon(48.2082, 16.3738);

    /** Parameter des WGS84-Ellipsoids für unabhängige Sollrechnungen in den Tests. */
    static final double WGS84_A = 6378137.0;
    static final double WGS84_E2 = 0.00669437999014;

    private static boolean initialized;

    private JosmTestSupport() {
    }

    static synchronized void initHeadless() {
        if (!initialized) {
            Config.setPreferencesInstance(new MemoryPreferences());
            Config.setBaseDirectoriesProvider(JosmBaseDirectories.getInstance());
            initialized = true;
        }
    }

    /** Setzt die aktive Kartenprojektion und liefert sie zurück. */
    static Projection useProjection(String code) {
        initHeadless();
        Projection projection = Projections.getProjectionByCode(code);
        ProjectionRegistry.setProjection(projection);
        return projection;
    }

    /**
     * Zustand einer Kartenansicht der angegebenen Größe, zentriert auf {@code center}.
     *
     * @param scale Projektionseinheiten pro Pixel
     */
    static MapViewState viewState(Projection projection, LatLon center, double scale, int width, int height) {
        EastNorth c = projection.latlon2eastNorth(center);
        return MapViewState.createDefaultState(width, height).usingScale(scale).usingCenter(c);
    }

    /** Unabhängige Sollrechnung: Umkehrung der sphärischen Mercator-Abbildung (EPSG:3857). */
    static LatLon inverseWebMercator(double east, double north) {
        return new LatLon(
            Math.toDegrees(Math.atan(Math.sinh(north / WGS84_A))),
            Math.toDegrees(east / WGS84_A));
    }

    /** Querkrümmungsradius N des WGS84-Ellipsoids. */
    static double primeVerticalRadius(double latDeg) {
        double s = Math.sin(Math.toRadians(latDeg));
        return WGS84_A / Math.sqrt(1.0 - WGS84_E2 * s * s);
    }

    /** Meridiankrümmungsradius M des WGS84-Ellipsoids. */
    static double meridianRadius(double latDeg) {
        double s = Math.sin(Math.toRadians(latDeg));
        return WGS84_A * (1.0 - WGS84_E2) / Math.pow(1.0 - WGS84_E2 * s * s, 1.5);
    }
}
