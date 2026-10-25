#!/bin/bash
# KinoLel Wünsche: Rauchtest im Emulator – installieren, starten (DE), App-Links prüfen, Link auf den HU-Server öffnen.
set -x
mkdir -p rauchtest
APK=$(ls apk/*.apk | head -1)
PAKET=$(grep '^APPLICATION_ID=' marke.properties | cut -d= -f2)
ui() { adb exec-out uiautomator dump /dev/tty 2>/dev/null; }
# Text antippen (Mitte seines Rahmens laut uiautomator)
tippe() {
  punkt=$(ui | python3 -c 'import re,sys; m=re.search(r"text=\"%s\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"" % re.escape(sys.argv[1]), sys.stdin.read()); print("%d %d" % ((int(m[1])+int(m[3]))//2, (int(m[2])+int(m[4]))//2) if m else "")' "$1")
  [ -n "$punkt" ] && adb shell input tap $punkt
}
# Emulator-Systemmeldung „… isn't responding“ (z. B. Pixel Launcher nach dem Update) verdeckt sonst die Meldung der App
adb shell settings put global hide_error_dialogs 1 || true
systemdialog_weg() {
  for _ in 1 2 3; do
    ui | grep -qE "isn't responding|reagiert nicht" || return 0
    tippe "Wait"; tippe "Warten"; sleep 2
  done
}
# Vorversion (letztes Release) installieren und einmal starten, dann Update: Meldung „Neu in …“ genau einmal
# Vorversion = neuestes Release, das NICHT die gerade gebaute Version ist (beim Tag-Lauf ist sie schon veröffentlicht).
# Über gh mit Build-Token (die GitHub-API ohne Anmeldung liefert im Runner oft nichts).
AKTUELL=$(sed -n "s/^VERSION=//p" marke.properties)
VOR=$(gh api "repos/Parcival1/kinolel-wuensche-android/releases?per_page=20" --jq "[.[] | select(.draft | not) | select(.tag_name != \"v$AKTUELL\") | .assets[] | select(.name | endswith(\".apk\")) | .browser_download_url][0] // empty" 2>rauchtest/gh-fehler.txt || true)
if [ -n "$VOR" ] && curl -sSL -o vorversion.apk "$VOR"; then
  adb install vorversion.apk
  adb shell am start -W -n "$PAKET/.MainActivity"
  sleep 10
  # Lel 25.10.: in der App „Magyar“ gewählt (KinoLel Kívánságok), das Gerät auf Deutsch -> „Was ist neu“ ungarisch
  tippe "Magyar"; sleep 8
  adb shell am force-stop "$PAKET"
fi
adb shell cmd locale set-app-locales "$PAKET" --locales de-DE || true
adb install -r "$APK"
adb shell am start -W -n "$PAKET/.MainActivity"
# Die Meldung wartet bis zu 8 s auf die Sprache der Wunschseite; den Stand auswerten, in dem sie zu sehen war
: > rauchtest/neuigkeiten-ui.xml
for i in $(seq 1 8); do
  sleep 3; systemdialog_weg; xml=$(ui)
  if echo "$xml" | grep -qE "Neu in |New in |Újdonságok"; then echo "$xml" > rauchtest/neuigkeiten-ui.xml; break; fi
done
adb exec-out screencap -p > rauchtest/00-neuigkeiten.png
if grep -q "Újdonságok" rauchtest/neuigkeiten-ui.xml; then echo "SPRACHE: Was ist neu ungarisch (Gerät Deutsch, App auf Magyar)" | tee rauchtest/sprache.txt; else echo "SPRACHE: Was ist neu NICHT ungarisch" | tee rauchtest/sprache.txt; fi
if grep -qE "Neu in |New in |Újdonságok" rauchtest/neuigkeiten-ui.xml; then echo "NEUIGKEITEN: nach Update gezeigt" | tee rauchtest/neuigkeiten.txt; else if [ -z "${VOR:-}" ]; then echo "NEUIGKEITEN: nicht geprüft – Vorversion nicht gefunden (GitHub-API)" | tee rauchtest/neuigkeiten.txt; else echo "NEUIGKEITEN: FEHLT nach Update" | tee rauchtest/neuigkeiten.txt; fi; fi
adb shell am force-stop "$PAKET"
adb shell am start -W -n "$PAKET/.MainActivity"
sleep 6
if ui | grep -qE "Neu in |New in |Újdonságok"; then echo "NEUIGKEITEN: FEHLER – zweiter Start zeigt sie erneut" | tee -a rauchtest/neuigkeiten.txt; else echo "NEUIGKEITEN: zweiter Start ohne Meldung (richtig)" | tee -a rauchtest/neuigkeiten.txt; fi
tippe "Deutsch"; sleep 3
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
