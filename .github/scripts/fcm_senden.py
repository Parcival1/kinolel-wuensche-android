#!/usr/bin/env python3
"""Firebase Cloud Messaging (HTTP v1) ohne Zusatzbibliotheken: Zugang per Dienstkonto (JWT, mit openssl signiert).

Als Modul: Sender(<dienstkonto.json>).senden(thema, daten) – als Skript (CI-Test):
    fcm_senden.py <dienstkonto.json> <thema> [schluessel=wert ...]
Der Schlüssel wird nie ausgegeben; die Datei bleibt, wo sie ist (nur für openssl kurz als geschützte Datei).
"""
import base64
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request

UMFANG = "https://www.googleapis.com/auth/firebase.messaging"


def _b64(daten: bytes) -> str:
    return base64.urlsafe_b64encode(daten).rstrip(b"=").decode()


class Sender:
    def __init__(self, dienstkonto_pfad):
        with open(dienstkonto_pfad) as f:
            self._konto = json.load(f)
        # Schlüssel für openssl kurz neben die Dienstkonto-Datei (geschützter Ordner), danach sofort löschen
        self._ordner = os.path.dirname(os.path.abspath(dienstkonto_pfad))
        self.projekt = self._konto["project_id"]
        self._zugang = None
        self._gueltig_bis = 0
        self._sperre = threading.Lock()

    def _signieren(self, text: bytes) -> bytes:
        with tempfile.NamedTemporaryFile("w", delete=False, dir=self._ordner, prefix=".fcm-", suffix=".pem") as f:
            os.chmod(f.name, 0o600)
            f.write(self._konto["private_key"])
            pfad = f.name
        try:
            return subprocess.run(["openssl", "dgst", "-sha256", "-sign", pfad], input=text, capture_output=True,
                                  check=True).stdout
        finally:
            os.remove(pfad)

    def zugang(self) -> str:
        with self._sperre:
            jetzt = int(time.time())
            if self._zugang and jetzt < self._gueltig_bis - 300:
                return self._zugang
            kopf = _b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
            inhalt = _b64(json.dumps({"iss": self._konto["client_email"], "scope": UMFANG,
                                      "aud": self._konto["token_uri"], "iat": jetzt, "exp": jetzt + 3600}).encode())
            unterschrift = _b64(self._signieren(f"{kopf}.{inhalt}".encode()))
            daten = urllib.parse.urlencode({"grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                            "assertion": f"{kopf}.{inhalt}.{unterschrift}"}).encode()
            with urllib.request.urlopen(urllib.request.Request(self._konto["token_uri"], data=daten), timeout=20) as r:
                antwort = json.load(r)
            self._zugang = antwort["access_token"]
            self._gueltig_bis = jetzt + int(antwort.get("expires_in", 3600))
            return self._zugang

    def senden(self, thema: str, daten: dict) -> tuple:
        """Inhaltslose Daten-Nachricht mit hoher Priorität an ein Thema; Rückgabe (HTTP-Status, Kurztext)"""
        nachricht = {"message": {"topic": thema, "data": {k: str(v) for k, v in daten.items()},
                                 "android": {"priority": "high", "ttl": "3600s"}}}
        anfrage = urllib.request.Request(
            f"https://fcm.googleapis.com/v1/projects/{self.projekt}/messages:send",
            data=json.dumps(nachricht).encode(),
            headers={"Authorization": "Bearer " + self.zugang(), "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(anfrage, timeout=20) as r:
                return r.status, json.load(r).get("name", "")[-24:]
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors="replace")[:200]


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    s = Sender(sys.argv[1])
    werte = dict(a.split("=", 1) for a in sys.argv[3:]) or {"kinolel": "pruefen"}
    status, text = s.senden(sys.argv[2], werte)
    print(f"FCM an {sys.argv[2]}: {status} {text}")
    sys.exit(0 if status == 200 else 1)
