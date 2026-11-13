# VanyaAC

Anticheat fuer Paper 1.21.11 (VanyaSMP).

## Bauen
Java 21 + Maven installieren, dann im Projektordner:

    mvn clean package

Die fertige Datei liegt in `target/VanyaAC.jar` -> in den `plugins`-Ordner kopieren.

## Befehle
- /vac alerts - Alerts an/aus (Permission vanyaac.alerts)
- /vac info <Spieler> - VL anzeigen
- /vac reset <Spieler> - VL zuruecksetzen
- /vac reload - Config neu laden

## Permissions
- vanyaac.admin, vanyaac.alerts, vanyaac.bypass
