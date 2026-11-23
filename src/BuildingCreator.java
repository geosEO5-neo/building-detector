package org.openstreetmap.josm.plugins.buildingdetector;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Projection;

import static org.openstreetmap.josm.tools.I18n.tr;

/**
 * Geodätische Konvertierung erkannter Gebäudekonturen in echte OSM-Vektordaten.
 *
 * Features:
 * - Transformation über die zum Aufnahmezeitpunkt eingefrorene {@link ImageGeoreference}
 *   (subpixelgenau, Pixelzentrum-Konvention, unabhängig von späterem Verschieben/Zoomen der Karte)
 * - Orthogonalisierung (exakte 90°-Winkel) in der Kartenprojektion; am Boden rechtwinklig, sofern die
 *   Projektion winkeltreu ist (Mercator, UTM, Lambert, ...)
 * - Kollisions- und Überlappungsprüfung gegen bestehende building=*-Ways im DataSet
 * - Vollständige Integration in den JOSM UndoRedoHandler (SequenceCommand für Strg+Z)
 * - Automatische Selektion der neu erstellten Gebäude für sofortige Qualitätskontrolle im HOT-Workflow
 */
public class BuildingCreator {

    /**
     * Ergebnis eines Erstellungslaufs.
     *
     * @param created              Anzahl der neu angelegten Gebäude
     * @param skippedExisting      Verworfen, weil am Ort bereits ein Gebäude kartiert ist
     * @param skippedOutsideArea   Verworfen, weil (teilweise) außerhalb des zulässigen Bereichs
     * @param skippedInvalid       Verworfen, weil die Kontur nicht georeferenzierbar war
     */
    public record CreationResult(int created, int skippedExisting, int skippedOutsideArea, int skippedInvalid) {
    }

    private enum Outcome { CREATED, EXISTING, OUTSIDE_AREA, INVALID }

    /**
     * Erstellt OSM-Gebäude über den JOSM-Command-Stack (inkl. Undo/Redo) und wählt sie aus.
     *
     * @param detectedBuildings Liste der erkannten Gebäudekonturen (Bildkoordinaten)
     * @param dataSet           Aktives OSM-DataSet
     * @param georeference      Georeferenzierung des ausgewerteten Bildes zum Aufnahmezeitpunkt
     * @param allowedArea       Optionaler zulässiger Bereich in geographischen Koordinaten
     *                          (x = Länge, y = Breite), z. B. der heruntergeladene Bereich; {@code null} = keine
     *                          Einschränkung. Gebäude müssen vollständig innerhalb liegen.
     * @return Zusammenfassung des Laufs
     */
    public CreationResult createOSMBuildings(
            List<BuildingContour> detectedBuildings, DataSet dataSet,
            ImageGeoreference georeference, Area allowedArea) {
        if (detectedBuildings == null || detectedBuildings.isEmpty() || dataSet == null || georeference == null) {
            return new CreationResult(0, 0, 0, 0);
        }

        List<Command> commands = new LinkedList<>();
        List<OsmPrimitive> createdWays = new ArrayList<>();
        int existing = 0;
        int outside = 0;
        int invalid = 0;

        for (BuildingContour contour : detectedBuildings) {
            List<Way> created = new ArrayList<>(1);
            switch (buildOrthogonalizedOsmWay(contour, dataSet, georeference, allowedArea, commands, created)) {
                case CREATED -> createdWays.addAll(created);
                case EXISTING -> existing++;
                case OUTSIDE_AREA -> outside++;
                default -> invalid++;
            }
        }

        if (!commands.isEmpty()) {
            Command seqCmd = new SequenceCommand(
                tr("Building Detector: {0} Gebäude erstellt", createdWays.size()),
                commands
            );
            // Ausführung über den offiziellen JOSM UndoRedoHandler (ermöglicht Strg+Z)
            UndoRedoHandler.getInstance().add(seqCmd);
            dataSet.setSelected(createdWays);
        }

        return new CreationResult(createdWays.size(), existing, outside, invalid);
    }

    /**
     * Konvertiert eine Kontur projektionsgetreu, orthogonalisiert ihre Geometrie im
     * lokalen EastNorth-System und prüft auf Konflikte mit bestehenden OSM-Gebäuden.
     */
    private Outcome buildOrthogonalizedOsmWay(
            BuildingContour contour, DataSet dataSet, ImageGeoreference georeference, Area allowedArea,
            List<Command> commands, List<Way> created) {
        List<Point2D.Double> pixelCorners = contour.getExactCorners();
        if (pixelCorners.size() < 4) {
            return Outcome.INVALID;
        }

        // 1. Subpixelgenaue Bildkoordinaten über die eingefrorene Georeferenzierung in EastNorth überführen
        Projection projection = georeference.getProjection();
        List<EastNorth> eastNorthCorners = new ArrayList<>(pixelCorners.size());
        for (Point2D.Double pt : pixelCorners) {
            EastNorth en = georeference.pixelToEastNorth(pt.x, pt.y);
            if (en == null || !en.isValid()) {
                return Outcome.INVALID;
            }
            eastNorthCorners.add(en);
        }

        // 2. Exakte Orthogonalisierung (Rechtwinkligkeit für HOT-Validierung erzwingen)
        List<EastNorth> squaredEastNorth = orthogonalizeRectangleEastNorth(eastNorthCorners);

        List<LatLon> squaredLatLons = new ArrayList<>(squaredEastNorth.size());
        for (EastNorth en : squaredEastNorth) {
            LatLon ll = projection.eastNorth2latlon(en);
            if (ll == null || !ll.isValid()) {
                return Outcome.INVALID;
            }
            squaredLatLons.add(ll);
        }

        // 3. Nur Gebäude zulassen, die vollständig im zulässigen (z. B. heruntergeladenen) Bereich liegen:
        //    außerhalb davon sind vorhandene OSM-Gebäude nicht bekannt, Duplikate wären nicht erkennbar.
        if (allowedArea != null) {
            for (LatLon ll : squaredLatLons) {
                if (!allowedArea.contains(ll.lon(), ll.lat())) {
                    return Outcome.OUTSIDE_AREA;
                }
            }
        }

        // 4. Prüfe auf Kollision / Überlappung mit bereits im DataSet vorhandenen Gebäuden
        if (conflictsWithExistingBuilding(squaredLatLons, dataSet)) {
            return Outcome.EXISTING;
        }

        // 5. Erzeuge OSM-Nodes und geschlossenen Way über AddCommands
        List<Node> buildingNodes = new ArrayList<>(squaredLatLons.size() + 1);
        for (LatLon ll : squaredLatLons) {
            Node node = new Node(ll);
            commands.add(new AddCommand(dataSet, node));
            buildingNodes.add(node);
        }
        buildingNodes.add(buildingNodes.get(0)); // Geschlossener Ring

        Way buildingWay = new Way();
        buildingWay.setNodes(buildingNodes);

        Map<String, String> tags = new HashMap<>();
        tags.put("building", "yes");
        buildingWay.setKeys(tags);

        commands.add(new AddCommand(dataSet, buildingWay));
        created.add(buildingWay);
        return Outcome.CREATED;
    }

    /**
     * Erzwingt exakte 90°-Innenwinkel eines 4-Eck-Gebäudes in den projizierten Koordinaten
     * (EastNorth), sodass das Gebäude die JOSM-Validierung ("Q"-Taste) fehlerfrei besteht.
     * In winkeltreuen Projektionen entspricht das einem rechten Winkel am Boden.
     */
    private List<EastNorth> orthogonalizeRectangleEastNorth(List<EastNorth> pts) {
        if (pts.size() != 4) {
            return pts;
        }

        // Schwerpunkt berechnen
        double cx = 0.0, cy = 0.0;
        for (EastNorth p : pts) {
            cx += p.east();
            cy += p.north();
        }
        cx /= 4.0;
        cy /= 4.0;

        // Richtungsvektoren der beiden Hauptkantenpaare (0->1 und 3->2 sowie 1->2 und 0->3)
        double ux = (pts.get(1).east() - pts.get(0).east()) + (pts.get(2).east() - pts.get(3).east());
        double uy = (pts.get(1).north() - pts.get(0).north()) + (pts.get(2).north() - pts.get(3).north());

        double vx = (pts.get(2).east() - pts.get(1).east()) + (pts.get(3).east() - pts.get(0).east());
        double vy = (pts.get(2).north() - pts.get(1).north()) + (pts.get(3).north() - pts.get(0).north());

        // Kombiniere die Richtung von u und die um 90° gedrehte Richtung von v für maximale Winkelstabilität
        double angleU = Math.atan2(uy, ux);
        double angleVOrtho = Math.atan2(-vx, vy);
        double diff = BuildingContour.normalizeAnglePiOver2(angleVOrtho - angleU);
        double theta = angleU + 0.5 * diff;

        double cos = Math.cos(theta);
        double sin = Math.sin(theta);

        // Projiziere alle 4 Eckpunkte auf das orthogonale Achsenpaar (e1, e2)
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;

        for (EastNorth p : pts) {
            double dx = p.east() - cx;
            double dy = p.north() - cy;
            double u = dx * cos + dy * sin;
            double v = -dx * sin + dy * cos;
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }

        double[][] localRect = {
            {minU, minV},
            {maxU, minV},
            {maxU, maxV},
            {minU, maxV}
        };

        List<EastNorth> squared = new ArrayList<>(4);
        for (double[] uv : localRect) {
            double east = cx + uv[0] * cos - uv[1] * sin;
            double north = cy + uv[0] * sin + uv[1] * cos;
            squared.add(new EastNorth(east, north));
        }
        return squared;
    }

    /**
     * Prüft über räumlichen BBox-Index und geometrischen Polygonschnitt, ob am Zielort
     * bereits ein kartiertes Gebäude im OSM-DataSet existiert.
     */
    private boolean conflictsWithExistingBuilding(List<LatLon> candidatePolygon, DataSet dataSet) {
        if (candidatePolygon.isEmpty()) {
            return true;
        }

        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        double sumLat = 0.0, sumLon = 0.0;

        Path2D.Double candPath = new Path2D.Double();
        for (int i = 0; i < candidatePolygon.size(); i++) {
            LatLon ll = candidatePolygon.get(i);
            minLat = Math.min(minLat, ll.lat());
            maxLat = Math.max(maxLat, ll.lat());
            minLon = Math.min(minLon, ll.lon());
            maxLon = Math.max(maxLon, ll.lon());
            sumLat += ll.lat();
            sumLon += ll.lon();

            if (i == 0) {
                candPath.moveTo(ll.lon(), ll.lat());
            } else {
                candPath.lineTo(ll.lon(), ll.lat());
            }
        }
        candPath.closePath();
        Area candArea = new Area(candPath);
        LatLon candCenter = new LatLon(sumLat / candidatePolygon.size(), sumLon / candidatePolygon.size());

        // Suche im DataSet mit leicht erweiterter BBox (~15 Meter Puffer)
        double bufferDeg = 0.00015;
        BBox searchBBox = new BBox(minLon - bufferDeg, minLat - bufferDeg, maxLon + bufferDeg, maxLat + bufferDeg);
        Collection<Way> nearbyWays = dataSet.searchWays(searchBBox);

        for (Way way : nearbyWays) {
            if (way.isDeleted() || !way.isUsable() || !way.hasKey("building")) {
                continue;
            }
            List<Node> nodes = way.getNodes();
            if (nodes.size() < 3) {
                continue;
            }

            Path2D.Double wayPath = new Path2D.Double();
            double wLat = 0.0, wLon = 0.0;
            int validNodes = 0;

            for (int i = 0; i < nodes.size(); i++) {
                LatLon c = nodes.get(i).getCoor();
                if (c == null) continue;
                if (validNodes == 0) {
                    wayPath.moveTo(c.lon(), c.lat());
                } else {
                    wayPath.lineTo(c.lon(), c.lat());
                }
                wLat += c.lat();
                wLon += c.lon();
                validNodes++;
            }
            if (validNodes < 3) continue;
            wayPath.closePath();

            // 1. Prüfe Schwerpunktabstand (< 4.5 Meter gilt als Duplikat)
            LatLon existingCenter = new LatLon(wLat / validNodes, wLon / validNodes);
            if (candCenter.greatCircleDistance(existingCenter) < 4.5) {
                return true;
            }

            // 2. Prüfe echte geometrische Flächenüberschneidung
            Area existingArea = new Area(wayPath);
            existingArea.intersect(candArea);
            if (!existingArea.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}