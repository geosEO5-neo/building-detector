// SPDX-License-Identifier: GPL-2.0-or-later
// Copyright (C) 2026 Sero. Lizenz: GNU GPL, Version 2 oder später; siehe Datei LICENSE.
package org.openstreetmap.josm.plugins.buildingdetector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.gui.MapViewState;
import org.openstreetmap.josm.plugins.buildingdetector.BuildingCreator.CreationResult;
import org.openstreetmap.josm.plugins.buildingdetector.ImageProcessor.DetectionConfig;

/**
 * Gesamte Kette Bild → Georeferenzierung → OSM-Geometrie gegen unabhängig gerechnete Sollkoordinaten.
 */
class BuildingCreatorTest {

    private static final double SCALE = 0.45; // Projektionseinheiten pro Pixel, ca. 0,30 m/px in Wien

    private static SyntheticScene scene;
    private static List<BuildingContour> detected;

    private MapViewState state;
    private ImageGeoreference georeference;
    private double gsd;

    @BeforeAll
    static void detectOnce() {
        scene = SyntheticScene.randomGrid(5, 4, 5, 120, 225, true);
        DetectionConfig config = new DetectionConfig();
        config.metersPerPixel = 0.30;
        config.minBuildingAreaM2 = SyntheticScene.MIN_BUILDING_AREA_M2;
        config.alignWithNeighbors = false;
        detected = new ImageProcessor().detectBuildings(scene.image, config);
    }

    @BeforeEach
    void setUp() {
        Projection mercator = JosmTestSupport.useProjection("EPSG:3857");
        int w = scene.image.getWidth();
        int h = scene.image.getHeight();
        state = JosmTestSupport.viewState(mercator, JosmTestSupport.VIENNA, SCALE, w, h);
        georeference = new ImageGeoreference(state, w, h);
        gsd = georeference.groundResolutionAt(w / 2.0, h / 2.0).getMeanMetersPerPixel();
        UndoRedoHandler.getInstance().clean();
    }

    /** Soll-Eckpunkte: kontinuierliche Bildkoordinate → EastNorth → LatLon, ohne ImageGeoreference. */
    private LatLon[] expectedCorners(double[][] building) {
        EastNorth topLeft = state.getForView(0, 0).getEastNorth();
        LatLon[] corners = new LatLon[4];
        for (int i = 0; i < 4; i++) {
            corners[i] = JosmTestSupport.inverseWebMercator(
                topLeft.east() + building[i][0] * SCALE, topLeft.north() - building[i][1] * SCALE);
        }
        return corners;
    }

    private static Way nearestWay(DataSet dataSet, LatLon[] expected) {
        Way best = null;
        double bestDistance = 5.0;
        for (Way way : dataSet.getWays()) {
            double lat = 0, lon = 0, eLat = 0, eLon = 0;
            for (int i = 0; i < 4; i++) {
                lat += way.getNode(i).lat() / 4;
                lon += way.getNode(i).lon() / 4;
                eLat += expected[i].lat() / 4;
                eLon += expected[i].lon() / 4;
            }
            double d = new LatLon(lat, lon).greatCircleDistance(new LatLon(eLat, eLon));
            if (d < bestDistance) {
                bestDistance = d;
                best = way;
            }
        }
        return best;
    }

    @Test
    void buildingsLandAtTheirTrueGroundPosition() {
        DataSet dataSet = new DataSet();
        CreationResult result = new BuildingCreator().createOSMBuildings(detected, dataSet, georeference, null);
        assertEquals(scene.truth.size(), result.created());
        assertEquals(dataSet.getWays().size(), dataSet.getSelected().size(), "neue Gebäude sind ausgewählt");

        double sumSq = 0, sumEast = 0, sumNorth = 0, maxAngleDeviation = 0;
        for (double[][] building : scene.truth) {
            LatLon[] expected = expectedCorners(building);
            Way way = nearestWay(dataSet, expected);
            assertNotNull(way, "zu jedem Soll-Gebäude existiert ein Way");
            assertTrue(way.isClosed() && way.getNodesCount() == 5 && "yes".equals(way.get("building")));

            double bestSq = Double.MAX_VALUE, east = 0, north = 0;
            for (int dir = 0; dir < 2; dir++) {
                for (int shift = 0; shift < 4; shift++) {
                    double sq = 0, e = 0, n = 0;
                    for (int i = 0; i < 4; i++) {
                        int j = dir == 0 ? (i + shift) % 4 : ((shift - i) % 4 + 4) % 4;
                        double d = expected[i].greatCircleDistance(way.getNode(j).getCoor());
                        double[] v = ImageGeoreference.localMetricVector(expected[i], way.getNode(j).getCoor());
                        sq += d * d / 4;
                        e += v[0] / 4;
                        n += v[1] / 4;
                    }
                    if (sq < bestSq) {
                        bestSq = sq;
                        east = e;
                        north = n;
                    }
                }
            }
            sumSq += bestSq;
            sumEast += east;
            sumNorth += north;

            for (int i = 0; i < 4; i++) {
                double[] u = ImageGeoreference.localMetricVector(way.getNode(i).getCoor(), way.getNode((i + 1) % 4).getCoor());
                double[] v = ImageGeoreference.localMetricVector(way.getNode((i + 1) % 4).getCoor(), way.getNode((i + 2) % 4).getCoor());
                double cos = (u[0] * v[0] + u[1] * v[1]) / (Math.hypot(u[0], u[1]) * Math.hypot(v[0], v[1]));
                maxAngleDeviation = Math.max(maxAngleDeviation, Math.abs(Math.toDegrees(Math.acos(cos)) - 90.0));
            }
        }
        int n = scene.truth.size();
        double rmse = Math.sqrt(sumSq / n);
        assertTrue(rmse < 0.6 * gsd, "Eckpunkt-RMSE " + rmse + " m bei GSD " + gsd + " m/px");
        assertEquals(0.0, sumEast / n, 0.25 * gsd, "kein systematischer Versatz nach Osten");
        assertEquals(0.0, sumNorth / n, 0.25 * gsd, "kein systematischer Versatz nach Norden");
        // Rechtwinklig in der Projektion; Web-Mercator ist am Boden nur bis auf ca. 0,2° winkeltreu
        assertTrue(maxAngleDeviation < 0.25, "Abweichung vom rechten Winkel am Boden: " + maxAngleDeviation + "°");
    }

    /**
     * Die Georeferenzierung ist eingefroren: Eine später geänderte aktive Projektion (oder Kartenansicht)
     * darf die Lage der Ergebnisse nicht verändern.
     */
    @Test
    void resultDoesNotDependOnTheCurrentProjectionOrView() {
        DataSet reference = new DataSet();
        new BuildingCreator().createOSMBuildings(detected, reference, georeference, null);

        JosmTestSupport.useProjection("EPSG:32633");
        DataSet afterChange = new DataSet();
        new BuildingCreator().createOSMBuildings(detected, afterChange, georeference, null);

        List<Way> a = new ArrayList<>(reference.getWays());
        assertEquals(a.size(), afterChange.getWays().size());
        for (Way way : a) {
            LatLon[] corners = new LatLon[4];
            for (int i = 0; i < 4; i++) {
                corners[i] = way.getNode(i).getCoor();
            }
            Way twin = nearestWay(afterChange, corners);
            assertNotNull(twin);
            for (int i = 0; i < 4; i++) {
                assertEquals(0.0, corners[i].greatCircleDistance(twin.getNode(i).getCoor()), 1e-6);
            }
        }
    }

    @Test
    void candidatesOutsideTheAllowedAreaAreSkipped() {
        Rectangle window = new Rectangle(200, 150, 401, 351);
        LatLon northWest = georeference.pixelToLatLon(window.x - 0.5, window.y - 0.5);
        LatLon southEast = georeference.pixelToLatLon(window.x + window.width - 0.5, window.y + window.height - 0.5);
        Bounds bounds = new Bounds(southEast.lat(), northWest.lon(), northWest.lat(), southEast.lon());
        Area allowed = new Area(new Rectangle2D.Double(
            bounds.getMinLon(), bounds.getMinLat(), bounds.getMaxLon() - bounds.getMinLon(), bounds.getMaxLat() - bounds.getMinLat()));

        int expectedInside = 0;
        for (double[][] building : scene.truth) {
            boolean inside = true;
            for (double[] c : building) {
                inside &= c[0] > window.x + 1 && c[0] < window.x + window.width - 1
                       && c[1] > window.y + 1 && c[1] < window.y + window.height - 1;
            }
            if (inside) {
                expectedInside++;
            }
        }
        assertTrue(expectedInside > 0 && expectedInside < scene.truth.size(), "Testszene liegt teils innen, teils außen");

        DataSet dataSet = new DataSet();
        CreationResult result = new BuildingCreator().createOSMBuildings(detected, dataSet, georeference, allowed);
        assertEquals(expectedInside, result.created());
        assertEquals(detected.size() - expectedInside, result.skippedOutsideArea());
        assertEquals(expectedInside, dataSet.getWays().size());
    }

    @Test
    void existingBuildingsAreNotDuplicatedAndOneUndoRemovesTheRun() {
        DataSet dataSet = new DataSet();
        BuildingCreator creator = new BuildingCreator();
        CreationResult first = creator.createOSMBuildings(detected, dataSet, georeference, null);
        CreationResult second = creator.createOSMBuildings(detected, dataSet, georeference, null);

        assertEquals(detected.size(), first.created());
        assertEquals(0, second.created());
        assertEquals(detected.size(), second.skippedExisting());
        assertEquals(detected.size(), dataSet.getWays().size());

        UndoRedoHandler.getInstance().undo();
        assertTrue(dataSet.getWays().isEmpty(), "ein Rückgängig-Schritt entfernt alle Gebäude des Laufs");
        assertTrue(dataSet.getNodes().isEmpty());
    }

    @Test
    void emptyOrMissingInputCreatesNothing() {
        DataSet dataSet = new DataSet();
        BuildingCreator creator = new BuildingCreator();
        assertEquals(0, creator.createOSMBuildings(null, dataSet, georeference, null).created());
        assertEquals(0, creator.createOSMBuildings(new ArrayList<>(), dataSet, georeference, null).created());
        assertEquals(0, creator.createOSMBuildings(detected, dataSet, null, null).created());
        assertTrue(dataSet.getWays().isEmpty());
    }
}
