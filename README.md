# Zurück

Würfelspiel für 2 bis 4 Spieler (Menschen und Computer) an einem Gerät. Reihum würfeln, Figuren ins Ziel bringen, andere zurückschicken. Läuft ohne Internet; Google AdMob zeigt Werbung (Einwilligungsabfrage inklusive).

Paketname: `de.autoknigge.zurueck`

## Bauen
Unter **Actions → Build** läuft der Build bei jedem Push.
- Mit hinterlegten Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`): Artefakt **Zurueck** mit `Zurueck.apk` und `Zurueck.aab` (identischer Stand, signiert).
- Ohne Secrets: Artefakt **Zurueck-Test** mit einer Test-APK.

## Werbung
Die AdMob-IDs stehen in `app/build.gradle.kts` (öffentliche Kennungen) und lassen sich per Secrets `ADMOB_APP_ID`, `ADMOB_BANNER_ID`, `ADMOB_OPEN_ID` überschreiben.

Datenschutzerklärung: https://autoknigge.de/zurueck/datenschutz.html
