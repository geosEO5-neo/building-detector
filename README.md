# HOT Semi-Auto Building Detector (JOSM Plugin)

> [!WARNING]
> **Status: experimentell.** Die Erkennung ist bisher nur mit synthetisch erzeugten Testbildern geprüft und **nicht an echten Luftbildern validiert**. Jedes erzeugte Gebäude muss vor dem Hochladen von Hand mit dem Luftbild verglichen und bei Bedarf korrigiert oder gelöscht werden. Bitte keine ungeprüften Ergebnisse nach OpenStreetMap hochladen.

Ein spezialisiertes JOSM-Plugin für die semi-automatische Erfassung von Gebäuden aus Luftbildern, optimiert für Aufgaben im **HOT Tasking Manager**. 

Das Plugin nutzt fortschrittliche Fernerkundungsmethoden (Kantendetektion, Schattenwurf-Analyse, Vegetationsmaskierung) kombiniert mit strengen geodätischen Regeln, um realistische, topologisch korrekte OSM-Gebäude zu generieren.

## ✨ Neue Features (Remote Sensing Edition)

- **Erweiterte Bildverarbeitung (Canny & ExG)**: Robuste Kantendetektion (Canny-Algorithmus) gepaart mit einem Excess Green (ExG) Index, um Baumkronen zuverlässig von Dächern zu unterscheiden.
- **3D-Schattenwurf-Verifikation**: Schätzt den Sonnenazimut und wertet den Schlagschatten aus, um echte 3D-Baukörper von flachen 2D-Strukturen (Parkplätze, Innenhöfe) zu trennen.
- **Nachbarschafts-Konsens**: Analysiert das lokale Siedlungscluster und richtet neue Gebäude automatisch an der dominanten Gebäudeflucht der Umgebung aus (Azimut-Regularisierung).
- **Geodätische Präzision**: Bestimmt die Bodenauflösung (GSD) im Suchbereich auf dem WGS84-Ellipsoid, führt Gebäudeecken subpixelgenau, friert die Georeferenzierung beim Rendern ein und erzwingt rechtwinklige Gebäudeecken (90°) in der Kartenprojektion. Details siehe [Geodätische Hinweise](#-geodätische-hinweise).
- **Wählbarer Suchbereich**: Die Suche lässt sich auf den heruntergeladenen Bereich, das umgebende Rechteck der Auswahl oder den gesamten Kartenausschnitt begrenzen.
- **Nahtlose JOSM-Integration**: Vollständige Unterstützung für den JOSM `UndoRedoHandler` (`Strg+Z`), automatische Selektion der neuen Objekte zur sofortigen Qualitätskontrolle und Konfliktvermeidung mit bereits kartierten OSM-Ways.

## 📦 Installation

### Automatische Installation
1. Lade die kompilierte `.jar`-Datei herunter.
2. Kopiere sie in den JOSM-Plugin-Ordner:
   - **Windows**: `%APPDATA%\JOSM\plugins\`
   - **Linux**: `~/.local/share/JOSM/plugins/` (oder `~/.josm/plugins/`)
   - **macOS**: `~/Library/JOSM/plugins/`
3. Starte JOSM neu und aktiviere das Plugin in den Einstellungen (`F12` -> Plugins).

### Manuelle Kompilierung
```bash
# Baue das Plugin und installiere es automatisch (Linux/macOS)
ant clean install
```
*Tipp: Nutze `ant clean dist`, falls du die `.jar` manuell verschieben möchtest.*

## 🚀 Verwendung (Der HOT-Workflow)

1. **Vorbereiten**: Lade deine Task-BBox im JOSM herunter und aktiviere das empfohlene Luftbild (z. B. Maxar, Bing, Esri).
2. **Zoomen**: Zoome nah an das gewünschte Siedlungscluster heran (empfohlene Bodenauflösung: `< 0.8 m/px`).
3. **Erkennen**: 
   - Klicke auf `Tools > Detect Buildings (HOT Semi-Auto)` 
   - Oder drücke `Strg + Shift + B`
4. **Tuning-Dialog**: Passe die Fernerkundungs-Parameter kurz an die lokalen Gegebenheiten an:
   - *Suchbereich*: Standard ist der heruntergeladene Bereich innerhalb des Kartenausschnitts. Nur dort kennt JOSM die vorhandenen Gebäude und kann Duplikate vermeiden; Kandidaten, die nicht vollständig darin liegen, werden verworfen. Alternativ: umgebendes Rechteck der Auswahl oder gesamter Kartenausschnitt.
   - *Kontrast-Schwellenwert*: Niedriger (z.B. 0.05) für verblasste Blechdächer, höher (z.B. 0.12) für klare Kontraste.
   - *Schatten-Gewicht*: Wichtig in trockenen Regionen zur Vermeidung von "False Positives" auf dem Boden.
5. **Prüfen**: Die generierten Gebäude werden sofort im JOSM selektiert. Prüfe die Geometrie und drücke bei Fehlern einfach `Strg+Z` (Rückgängig).

## 🧠 Technische Pipeline

1. **Spektrale Vorverarbeitung**: Perzentil-Kontrastspreizung (2%–98%) und Vegetationsmaskierung.
2. **Kanten- & Regionsextraktion**: Non-Maximum Suppression und Hysterese (Canny) generieren scharfe Barrieren. Region-Growing detektiert homogene Dachflächen.
3. **OMBB & Orthogonalisierung**: *Rotating Calipers* finden die flächenminimale orientierte Bounding Box (OMBB). Anschließend werden die Eckpunkte in die aktuelle Kartenprojektion überführt und orthogonalisiert.
4. **Scoring & NMS**: Gebäude werden anhand von Kantenstärke, Schattenwurf und Nachbarschaft bewertet. Überlappungen werden mittels Non-Maximum Suppression aufgelöst.

## 📐 Geodätische Hinweise

- **Eingefrorene Georeferenzierung**: Die Zuordnung Bild ↔ Boden wird beim Rendern des Luftbildes festgehalten. Verschieben oder Zoomen der Karte während der Berechnung verändert die Lage der Ergebnisse nicht.
- **Pixelkonvention**: Bildkoordinaten beziehen sich auf Pixelzentren. Bei der Umrechnung in Kartenkoordinaten wird der halbe Pixel berücksichtigt, sodass kein systematischer Versatz von 0,5 px entsteht.
- **Subpixelgenaue Umrisse**: Eckpunkte werden nicht auf ganze Pixel gerundet. Jede Rechteckseite wird auf das Gradientenmaximum (die Dachkante) verschoben; ohne diese Korrektur fallen die Umrisse je Seitenlänge um rund 3 px zu klein aus.
- **Bodenauflösung**: Die GSD wird im Zentrum des Suchbereichs getrennt für beide Pixelachsen im lokalen metrischen System des WGS84-Ellipsoids bestimmt. Für Flächenschwellen in m² wird die flächentreue mittlere Auflösung verwendet.
- **Winkeltreue**: Die Rechteckanpassung erfolgt im Bild und setzt eine lokal winkeltreue Kartenprojektion voraus (Mercator, UTM, Gauß-Krüger, Lambert). Bei nicht winkeltreuen Projektionen wie EPSG:4326 warnt das Plugin vor dem Start.
- **Grenzen der Lagegenauigkeit**: Die innere Genauigkeit der Umrisse liegt auf synthetischen Testbildern bei wenigen Zehntelpixeln. Die absolute Lage hängt vom Luftbild ab: Ein Bildversatz (in JOSM über den Bildversatz der Ebene korrigierbar) überträgt sich vollständig auf die Gebäude. Erkannt wird außerdem die Dachfläche; bei hohen Gebäuden in nicht lotrecht aufgenommenen Bildern ist das Dach gegenüber dem Grundriss versetzt und muss von Hand an den Gebäudefuß geschoben werden.

## 🧪 Tests

```bash
ant test                 # Tests ohne Benutzeroberfläche (wenige Sekunden)
ant test-gui             # End-to-End-Test in einer echten JOSM-Instanz
xvfb-run ant test-gui    # dasselbe ohne Bildschirm, z. B. auf einem Linux-Server
```

- **Voraussetzungen**: JDK 21 und Apache Ant. JUnit wird beim ersten Lauf einmalig von Maven Central nach `lib/test/` geladen und über eine SHA-256-Prüfsumme verifiziert.
- **Testdaten**: Alle Tests arbeiten mit synthetisch erzeugten Luftbildern, deren Gebäudegeometrie exakt bekannt ist (`test/SyntheticScene.java`). Es werden keine fremden Bilddaten benötigt oder mitgeliefert.
- **`ant test`** prüft die Bildverarbeitung (Erkennung, Suchfenster, Lage- und Größentreue der Umrisse im Subpixelbereich), die Konturgeometrie, die Georeferenzierung (Pixelkonvention, Bodenauflösung gegen die Ellipsoidformeln, Winkeltreue) und die Erzeugung der OSM-Objekte gegen unabhängig gerechnete Sollkoordinaten.
- **`ant test-gui`** startet JOSM offline mit einem temporären Profil unter `build-test/` (das eigene JOSM-Profil bleibt unberührt), blendet ein synthetisches Luftbild ein und bedient die Action samt Dialogen: Suchbereiche, Verschieben der Karte während der Berechnung, Duplikatvermeidung, Rückgängig und die Warnung bei nicht winkeltreuer Projektion. Ohne grafische Oberfläche wird der Test übersprungen.
- Die Tests übersetzen in ein eigenes Verzeichnis `build-test/`; `build/` und `dist/` werden nicht verändert.
- **Automatisch auf GitHub**: Bei jedem Push und Pull Request führt GitHub Actions `ant test` aus (`.github/workflows/tests.yml`). Der GUI-Test läuft dort nicht.

## 🤝 Mitwirken & Lizenz

Dieses Plugin steht unter der **GPL-3.0 Lizenz**. Pull Requests, Bug Reports und Feedback sind in der OSM-Community jederzeit willkommen!