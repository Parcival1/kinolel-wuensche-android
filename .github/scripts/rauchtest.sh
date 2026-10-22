#!/bin/bash
# KinoLel Wünsche: Rauchtest im Emulator – installieren, starten (DE), App-Links prüfen, Link auf den HU-Server öffnen.
set -x
mkdir -p rauchtest
APK=$(ls apk/*.apk | head -1)
PAKET=$(grep '^APPLICATION_ID=' marke.properties | cut -d= -f2)
ui() { adb exec-out uiautomator dump /dev/tty 2>/dev/null; }
# Vorversion (letztes Release) installieren und einmal starten, dann Update: Meldung „Neu in …“ genau einmal
VOR=$(curl -s ${GH_TOKEN:+-H "Authorization: Bearer $GH_TOKEN"} https://api.github.com/repos/Parcival1/kinolel-wuensche-android/releases | python3 -c '
import json,sys
for r in json.load(sys.stdin):
    for a in r.get("assets", []):
        if a["name"].endswith(".apk"): print(a["browser_download_url"]); sys.exit()')
if [ -n "$VOR" ] && curl -sSL -o vorversion.apk "$VOR"; then
  adb install vorversion.apk
  adb shell am start -W -n "$PAKET/.MainActivity"
  sleep 10
  adb shell am force-stop "$PAKET"
fi
adb install -r "$APK"
adb shell am start -W -n "$PAKET/.MainActivity"
sleep 6; adb exec-out screencap -p > rauchtest/00-neuigkeiten.png
if ui | grep -qE "Neu in |New in |Újdonságok"; then echo "NEUIGKEITEN: nach Update gezeigt" | tee rauchtest/neuigkeiten.txt; else if [ -z "${VOR:-}" ]; then echo "NEUIGKEITEN: nicht geprüft – Vorversion nicht gefunden (GitHub-API)" | tee rauchtest/neuigkeiten.txt; else echo "NEUIGKEITEN: FEHLT nach Update" | tee rauchtest/neuigkeiten.txt; fi; fi
adb shell am force-stop "$PAKET"
adb shell am start -W -n "$PAKET/.MainActivity"
sleep 6
if ui | grep -qE "Neu in |New in |Újdonságok"; then echo "NEUIGKEITEN: FEHLER – zweiter Start zeigt sie erneut" | tee -a rauchtest/neuigkeiten.txt; else echo "NEUIGKEITEN: zweiter Start ohne Meldung (richtig)" | tee -a rauchtest/neuigkeiten.txt; fi
adb shell am force-stop "$PAKET"
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
