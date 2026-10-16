#!/bin/bash
# KinoLel Wünsche: Rauchtest im Emulator – installieren, starten (DE), App-Links prüfen, Link auf den HU-Server öffnen.
set -x
mkdir -p rauchtest
APK=$(ls apk/*.apk | head -1)
PAKET=$(grep '^APPLICATION_ID=' marke.properties | cut -d= -f2)
adb install -r "$APK"
adb shell am start -W -n "$PAKET/.MainActivity"
sleep 4;  adb exec-out screencap -p > rauchtest/01-start.png
sleep 20; adb exec-out screencap -p > rauchtest/02-deutsch-24s.png
# App-Links: Android prüft https://<domain>/.well-known/assetlinks.json
adb shell pm verify-app-links --re-verify "$PAKET"
sleep 15
adb shell pm get-app-links "$PAKET" | tee rauchtest/app-links.txt
# Link auf den ungarischen Server (wie aus Jellyfin) -> muss die App öffnen und auf Magyar umschalten
adb shell am force-stop "$PAKET"
adb shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "https://kivansagok.kinolel.de/" | tee rauchtest/link-hu.txt
sleep 20; adb exec-out screencap -p > rauchtest/03-link-ungarisch.png
adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" | tee -a rauchtest/link-hu.txt
# zurück auf den deutschen Server per Link (warmer WebView)
adb shell am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "https://wuensche.kinolel.de/" | tee rauchtest/link-de.txt
sleep 15; adb exec-out screencap -p > rauchtest/04-link-deutsch.png
exit 0
