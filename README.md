# KinoLel Wünsche – Android-App

App für die Wunschseiten von KinoLel: **KinoLel Wünsche** (`https://wuensche.kinolel.de`, Deutsch) und
**KinoLel Kívánságok** (`https://kivansagok.kinolel.de`, Ungarisch). Die App zeigt die vollständige Seerr-Oberfläche
(Anmelden mit den KinoLel-Zugangsdaten, Filme/Serien wünschen, Probleme melden …) und hat oben einen Umschalter
**Deutsch / Magyar** – die Wahl wird gemerkt.

**Links öffnen die App:** Wer z. B. in KinoLel (Jellyfin) auf einen Link zu den Wünschen tippt, landet direkt in dieser
App statt im Browser (Android-App-Links; die Domains bestätigen das über `/.well-known/assetlinks.json`).

## Marke und Einstellungen
Eine Stelle: [`marke.properties`](marke.properties) (Name, Kennung `de.kinolel.wuensche`, beide Server, Version, Update-Repo).
Symbole: `marke/wuensche_bilder.py` (Wünsche-Logo wie auf der Seerr-Seite: Leinwand + Wunsch-Stern).

## Updates
Die App prüft beim Start (höchstens alle 6 Stunden) die [Releases](../../releases) dieses Repos, lädt eine neuere APK und
öffnet den Android-Installationsdialog (einmalig „Unbekannte Apps installieren“ erlauben).

## Bauen
GitHub Actions (`.github/workflows/android.yml`): signierte APK (gleicher Schlüssel wie die KinoLel-App; Secrets
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `SIGNING_KEY_PASSWORD`), Rauchtest im Emulator mit App-Link-Prüfung,
Tag `v*` → Vorab-Version unter Releases.

Lizenz: MIT ([LICENSE](LICENSE)).
