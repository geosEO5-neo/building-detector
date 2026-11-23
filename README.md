# HOT Semi-Auto Building Detector (JOSM Plugin)

> [!WARNING]
> **Status: experimentell.** Messung im November 2026 an acht Orthofoto-Ausschnitten in Österreich (basemap.at, 0,2 m/px; Referenz: vorhandene OSM-Gebäude; Treffer ab IoU > 0,5; Details unter [Messung an echten Luftbildern](#-messung-an-echten-luftbildern)): Auf den fünf Kontrollszenen ist etwa **jedes dritte erzeugte Gebäude ein Treffer** (Präzision 30 %, 95-%-Intervall 25–36 %), und **etwa jedes sechste vorhandene Gebäude wird gefunden** (Trefferquote 17 %, 14–21 %). Gebäude unter 80 m² werden mit den Standardwerten gar nicht erkannt, und auch Treffer weichen typischerweise 1,5–2 m vom OSM-Grundriss ab. Die Erkennung ist damit **noch kein Ersatz für das Zeichnen von Hand**. Jedes erzeugte Gebäude muss vor dem Hochladen von Hand mit dem Luftbild verglichen und bei Bedarf korrigiert oder gelöscht werden. Bitte keine ungeprüften Ergebnisse nach OpenStreetMap hochladen.

Ein spezialisiertes JOSM-Plugin für die semi-automatische Erfassung von Gebäuden aus Luftbildern, optimiert für Aufgaben im **HOT Tasking Manager**. 

Das Plugin nutzt fortschrittliche Fernerkundungsmethoden (Kantendetektion, Schattenwurf-Analyse, Vegetationsmaskierung) kombiniert mit strengen geodätischen Regeln, um realistische, topologisch korrekte OSM-Gebäude zu generieren.

## ✨ Neue Features (Remote Sensing Edition)

- **Erweiterte Bildverarbeitung (Canny & ExG)**: Robuste Kantendetektion (Canny-Algorithmus) gepaart mit einem Excess Green (ExG) Index, um Baumkronen zuverlässig von Dächern zu unterscheiden.
- **Dachflächen zusammenfassen**: Sonnen- und Schattenseite eines Sattel- oder Walmdachs werden über die Farbe als ein Dach erkannt, statt in einzelne Dachseiten zu zerfallen.
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
   - *Minimale Gebäudefläche*: Standard 80 m². In der Messung an echten Orthofotos waren kleinere Erkennungen zu rund 96 % falsch. Für kleine Gebäude lässt sich der Wert herabsetzen; dann steigt die Zahl der Fehltreffer stark.
5. **Prüfen**: Die generierten Gebäude werden sofort im JOSM selektiert. Prüfe die Geometrie und drücke bei Fehlern einfach `Strg+Z` (Rückgängig).

## 🧠 Technische Pipeline

1. **Spektrale Vorverarbeitung**: Perzentil-Kontrastspreizung (2%–98%) und Vegetationsmaskierung.
2. **Kanten- & Regionsextraktion**: Non-Maximum Suppression und Hysterese (Canny) generieren scharfe Barrieren. Region-Growing detektiert homogene Dachflächen.
3. **Zusammenfassen der Dachflächen**: Teilflächen, die nur durch eine schmale Kantenlinie (First, Grat) getrennt sind, ähnliche Farbanteile haben und gemeinsam ein Rechteck füllen, bilden ein Dach.
4. **OMBB & Orthogonalisierung**: *Rotating Calipers* finden die flächenminimale orientierte Bounding Box (OMBB). Anschließend werden die Eckpunkte in die aktuelle Kartenprojektion überführt und orthogonalisiert.
5. **Scoring & NMS**: Gebäude werden anhand von Kantenstärke, Schattenwurf und Nachbarschaft bewertet; Rechtecke mit einem Seitenverhältnis über 4 : 1 werden verworfen. Bei Überlappung (exakte Schnittfläche der Umrisse) bleibt der Kandidat mit der größeren gestützten Fläche.

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

## 📏 Messung an echten Luftbildern

```bash
ant eval-real                                          # alle Szenen aus eval/scenes.csv
ant eval-real -Deval.args="--only=wien-siedlung"       # einzelne Szenen (kommagetrennt)
ant eval-real -Deval.args="--iou=0.3"                  # andere Trefferschwelle
ant eval-real -Deval.args="--min-area=12"              # andere Mindestfläche der Erkennung (m²)
```

Die synthetischen Tests zeigen, ob die Geometrie stimmt. Ob die Erkennung auf echten Bildern überhaupt Gebäude findet, misst `ant eval-real` (`eval/RealImageryEvaluation.java`):

- **Ablauf**: Für jede Szene wird ein Mosaik aus Orthofoto-Kacheln geladen, die Erkennung mit den Standardparametern ausgeführt und das Ergebnis mit den Gebäuden verglichen, die in OpenStreetMap im selben Ausschnitt vorhanden sind. Eine Erkennung gilt als Treffer, wenn sie sich mit genau einem OSM-Gebäude zu mehr als 50 % überdeckt (IoU > 0,5, Eins-zu-eins-Zuordnung).
- **Szenen**: `eval/scenes.csv` trennt Szenen, an denen die Erkennung eingestellt wurde (`tuning`), von Kontrollszenen (`holdout`), an denen nichts eingestellt wird. Nur die Kontrollszenen sagen etwas darüber aus, wie gut die Erkennung auf unbekannten Bildern arbeitet.
- **Mindestfläche der Referenz**: Gewertet werden OSM-Gebäude ab 12 m², die vollständig im Bild liegen (`--min-ref-area`), unabhängig von den Parametern der Erkennung. So bleiben Läufe mit verschiedenen Einstellungen vergleichbar.
- **Ergebnisse** unter `build-eval/results/`: `summary.csv` (je Szene und gesamt: Präzision und Trefferquote mit 95-%-Wilson-Intervall, F1, Lageabweichungen), `buildings.csv` (je OSM-Gebäude: Treffer oder nicht; bei Treffern Schwerpunktversatz nach Ost und Nord in Metern, Flächen, Richtungsabweichung, Abstand der Umrisse), `detections.csv` (je Erkennung) und ein Überlagerungsbild je Szene.
- **Stand November 2026** (Standardparameter, 0,2 m/px, Treffer ab IoU > 0,5, Intervalle: 95 % nach Wilson):

  | Szenen | OSM-Gebäude | Erkennungen | Treffer | Präzision | Trefferquote |
  |---|---|---|---|---|---|
  | Einstellszenen (3), vorher | 317 | 590 | 41 | 6,9 % [5,2–9,3] | 12,9 % [9,7–17,1] |
  | Einstellszenen (3), jetzt | 317 | 197 | 99 | 50,3 % [43,3–57,2] | 31,2 % [26,4–36,5] |
  | Kontrollszenen (5), vorher | 533 | 1056 | 93 | 8,8 % [7,2–10,7] | 17,4 % [14,5–20,9] |
  | Kontrollszenen (5), jetzt | 533 | 300 | 91 | 30,3 % [25,4–35,8] | 17,1 % [14,1–20,5] |

  „Vorher" ist der Stand vor dem Zusammenfassen der Dachflächen (Commit `c822151`). Auf den Kontrollszenen hat sich die Zahl der Fehltreffer auf rund ein Fünftel verringert, die Trefferquote ist unverändert. Die Einstellszenen fallen deutlich besser aus als die Kontrollszenen; maßgeblich für unbekannte Bilder sind die Kontrollszenen. Zwischen den einzelnen Kontrollszenen schwankt die Präzision von 22 % bis 39 %. Gebäude unter 80 m² (ein Viertel der Referenz) werden mit den Standardwerten nicht gefunden.
- **Daten**: Orthofotos von [basemap.at](https://basemap.at) (CC BY 4.0), Gebäude © [OpenStreetMap](https://www.openstreetmap.org/copyright)-Mitwirkende (ODbL). Beides wird nur unter `build-eval/cache/` zwischengespeichert und nicht mit dem Plugin verteilt. Eine andere Kachelquelle lässt sich mit `--tile-url=…{z}/{x}/{y}…` und `--attribution=…` angeben; bitte die Nutzungsbedingungen der Quelle beachten.
- **Grenzen der Aussage**: OSM ist keine fehlerfreie Referenz, und ein Orthofoto zeigt Dächer, die gegenüber dem Grundriss durch Dachüberstand und Umklappung versetzt sind. Die Lageabweichungen enthalten deshalb Anteile der Referenz und des Bildes. Die OSM-Daten werden beim ersten Lauf geladen; wer später neu lädt, misst gegen einen anderen OSM-Stand.
- Die Messung braucht Netzzugang und ist deshalb nicht Teil von `ant test`.

## 🤝 Mitwirken & Lizenz

Dieses Plugin ist freie Software unter der **GNU General Public License, Version 2 oder (nach eigener Wahl) jeder späteren Version** (`GPL-2.0-or-later`) – derselben Lizenz wie JOSM selbst. Der vollständige Lizenztext steht in der Datei [LICENSE](LICENSE). Copyright (C) 2026 Sero.

Die Datei `lib/josm-custom.jar` ist ein lokaler Build von JOSM (Revision 19440) und steht unter der [Lizenz von JOSM](https://josm.openstreetmap.de/browser/trunk/LICENSE).

Pull Requests, Bug Reports und Feedback sind in der OSM-Community jederzeit willkommen!
