#!/usr/bin/env python3
"""KinoLel Wünsche (Android + iOS): App-Symbole aus dem Wünsche-Logo von Seerr (Leinwand + lila Wunsch-Stern,
wie /dokumentation/werkzeuge/seerr_logo.py auf CT 103 – gleiche Zeichnung, damit App und Webseite gleich aussehen).
Braucht Pillow (z. B. auf CT 103). Aufruf: python3 wuensche_bilder.py <android-repo> <ios-repo>"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

ANDROID, IOS = sys.argv[1], sys.argv[2]
WEISS = (255, 255, 255, 255)
LAV1, LAV2 = (154, 164, 255), (108, 114, 238)   # #9aa4ff -> #6c72ee
GRUND = (11, 15, 24, 255)                        # #0b0f18 (App-Grund)
SS = 4


def stern(d, cx, cy, r, farbe, dicke=0.32):
    """Vierzackiger Funkelstern (Wunsch) – wie seerr_logo.py."""
    pts = []
    for i in range(8):
        w = math.pi / 4 * i - math.pi / 2
        rr = r if i % 2 == 0 else r * dicke
        pts.append((cx + rr * math.cos(w), cy + rr * math.sin(w)))
    d.polygon(pts, fill=farbe)


def verlauf(gr, f1, f2):
    v = Image.new('RGBA', gr)
    px = v.load()
    for y in range(gr[1]):
        for x in range(gr[0]):
            t = (x / gr[0] + y / gr[1]) / 2
            px[x, y] = tuple(int(f1[c] + (f2[c] - f1[c]) * t) for c in range(3)) + (255,)
    return v


def zeichen(s, einfarbig=None):
    """Leinwand-Rahmen + Sterne auf transparentem Quadrat s x s (Inhalt füllt ~100 % der Breite)."""
    S = s * SS
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    b, h = S * 0.96, S * 0.96 * 0.70
    x0, y0 = S * 0.02, (S - h) / 2
    w = max(2, int(S * 0.075))
    d.rounded_rectangle((x0, y0, x0 + b, y0 + h), int(S * 0.07), outline=WEISS, width=w)
    m = Image.new('L', (S, S), 0)
    md = ImageDraw.Draw(m)
    stern(md, S / 2, y0 + h / 2, h * 0.36, 255)
    stern(md, x0 + b * 0.80, y0 + h * 0.27, h * 0.11, 255)
    stern(md, x0 + b * 0.22, y0 + h * 0.74, h * 0.07, 255)
    if einfarbig:
        im.paste(Image.new('RGBA', (S, S), einfarbig), (0, 0), m)
    else:
        im.paste(verlauf((S, S), LAV1, LAV2), (0, 0), m)
    return im.resize((s, s), Image.LANCZOS)


def platte(s, rund=False, ecken=0.22):
    """Dunkle Platte + Zeichen (Inhalt 64 %)."""
    S = s * SS
    mk = Image.new('L', (S, S), 0)
    if rund:
        ImageDraw.Draw(mk).ellipse((0, 0, S - 1, S - 1), fill=255)
    else:
        ImageDraw.Draw(mk).rounded_rectangle((0, 0, S - 1, S - 1), int(S * ecken), fill=255)
    mk = mk.resize((s, s), Image.LANCZOS)
    bild = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    bild.paste(Image.new('RGBA', (s, s), GRUND), (0, 0), mk)
    z = zeichen(round(s * (0.60 if rund else 0.66)))
    bild.alpha_composite(z, ((s - z.width) // 2, (s - z.height) // 2))
    return bild


def mittig(s, inhalt_anteil, einfarbig=None):
    bild = Image.new('RGBA', (s, s), (0, 0, 0, 0))
    z = zeichen(round(s * inhalt_anteil), einfarbig)
    bild.alpha_composite(z, ((s - z.width) // 2, (s - z.height) // 2))
    return bild


def speichern(bild, *teile):
    p = os.path.join(*teile)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    bild.save(p)


res = os.path.join(ANDROID, 'app', 'src', 'main', 'res')
for ordner, f in (('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)):
    speichern(platte(round(48 * f)), res, f'mipmap-{ordner}', 'ic_launcher.png')
    speichern(platte(round(48 * f), rund=True), res, f'mipmap-{ordner}', 'ic_launcher_round.png')
    # adaptives Symbol: 108 dp, sichtbarer Kreis 66 dp -> Inhalt 54 % der Fläche
    speichern(mittig(round(108 * f), 0.54), res, f'mipmap-{ordner}', 'ic_launcher_vorne.png')
    speichern(mittig(round(108 * f), 0.54, einfarbig=WEISS), res, f'mipmap-{ordner}', 'ic_launcher_einfarbig.png')
speichern(mittig(432, 0.60), res, 'drawable-nodpi', 'startbild_symbol.png')
speichern(zeichen(144), res, 'drawable-nodpi', 'leiste_logo.png')

ios = os.path.join(IOS, 'KinoLelWuensche', 'Assets.xcassets')
INFO = {'author': 'xcode', 'version': 1}


def js(pfad, daten):
    os.makedirs(os.path.dirname(pfad), exist_ok=True)
    with open(pfad, 'w') as f:
        json.dump(daten, f, indent=2)


js(os.path.join(ios, 'Contents.json'), {'info': INFO})
symbol = Image.new('RGBA', (1024, 1024), GRUND)
z = zeichen(700)
symbol.alpha_composite(z, ((1024 - 700) // 2, (1024 - 700) // 2))
speichern(symbol.convert('RGB'), ios, 'AppIcon.appiconset', 'AppIcon-1024.png')
js(os.path.join(ios, 'AppIcon.appiconset', 'Contents.json'), {
    'images': [{'filename': 'AppIcon-1024.png', 'idiom': 'universal', 'platform': 'ios', 'size': '1024x1024'}], 'info': INFO})
bilder = []
for f in (1, 2, 3):
    speichern(zeichen(120 * f), ios, 'StartLogo.imageset', f'StartLogo@{f}x.png')
    bilder.append({'filename': f'StartLogo@{f}x.png', 'idiom': 'universal', 'scale': f'{f}x'})
js(os.path.join(ios, 'StartLogo.imageset', 'Contents.json'), {'images': bilder, 'info': INFO})
for name, (r, g, b) in (('Startgrund', GRUND[:3]), ('AccentColor', LAV1)):
    js(os.path.join(ios, f'{name}.colorset', 'Contents.json'), {'colors': [{'idiom': 'universal', 'color': {
        'color-space': 'srgb', 'components': {'red': f'{r / 255:.3f}', 'green': f'{g / 255:.3f}', 'blue': f'{b / 255:.3f}',
                                              'alpha': '1.000'}}}], 'info': INFO})
vorschau = Image.new('RGBA', (700, 180), (243, 243, 243, 255))
vorschau.paste(Image.new('RGBA', (350, 180), (32, 32, 32, 255)), (350, 0))
x = 10
for bild in (platte(144), platte(144, rund=True), symbol.resize((144, 144))):
    vorschau.alpha_composite(bild, (x, 18)); x += 170
vorschau.alpha_composite(zeichen(144), (x, 18))
speichern(vorschau.convert('RGB'), ANDROID, 'marke', 'vorschau.png')
print('fertig')
