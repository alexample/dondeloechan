#!/usr/bin/env python3
"""Historial de enfrentamientos y forma reciente de los equipos de Primera y Segunda.

Fuente: football-data.co.uk (CSV públicos y gratuitos por temporada: SP1 = Primera, SP2 = Segunda),
con resultados desde 2000/01 y, en Primera, estadísticas del partido (tiros, córners, tarjetas).

Escribe data/h2h/<id>.json por equipo:
  {
    "equipo": "bet",
    "forma":   [[fecha, rivalId, "C"|"F", golesFavor, golesContra, "1ª"|"2ª"], ...]   # últimos 10 de liga, el más reciente primero
    "rivales": {"sev": {"pj","g","e","p","gf","gc",
                        "ult": [[fecha, temporada, comp, localId, visitanteId, gl, gv, descansoL, descansoV, stats], ...]}}  # últimos 10
  }
stats = {"t":[tirosL,tirosV], "p":[aPuertaL,aPuertaV], "c":[córnersL,córnersV], "a":[amarillasL,amarillasV], "r":[rojasL,rojasV]} (si los hay)

Uso: python3 robot/historial.py [--seco]
"""
import csv
import io
import json
import os
import sys
import time
import urllib.request
from datetime import datetime, timezone

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SALIDA = os.path.join(RAIZ, "data", "h2h")
SECO = "--seco" in sys.argv
UA = "Mozilla/5.0 (compatible; DondeLoEchan/1.0; +https://alexample.github.io/dondeloechan/)"
DESDE = 2000          # primera temporada (2000/01)
ULTIMOS = 10

# Nombre en football-data.co.uk -> nuestro id (equipos que hoy están en Primera o Segunda).
NOMBRES = {
    "Alaves": "ala", "Ath Bilbao": "ath", "Ath Madrid": "atm", "Barcelona": "bar", "Betis": "bet",
    "Celta": "cel", "La Coruna": "dep", "Elche": "elc", "Espanol": "esp", "Getafe": "get",
    "Levante": "lev", "Malaga": "mal", "Osasuna": "osa", "Santander": "rac", "Racing Santander": "rac",
    "Vallecano": "ray", "Real Madrid": "rma", "Sociedad": "rso", "Sevilla": "sev", "Valencia": "val",
    "Villarreal": "vil",
    "Albacete": "alb", "Almeria": "alm", "Andorra": "and", "Burgos": "bur", "Cadiz": "cad",
    "Castellon": "cas", "Ceuta": "ceu", "Celta B": "clb", "Celta Fortuna": "clb", "Cordoba": "cor",
    "Eibar": "eib", "Eldense": "eld", "Girona": "gir", "Granada": "gra", "Leganes": "leg",
    "Las Palmas": "lpa", "Mallorca": "mll", "Oviedo": "ovi", "Sociedad B": "rsb", "Sabadell": "sab",
    "Sp Gijon": "spo", "Tenerife": "ten", "Valladolid": "vll",
}


def temporadas():
    hoy = datetime.now(timezone.utc)
    ultima = hoy.year if hoy.month >= 7 else hoy.year - 1
    return [(y, f"{y % 100:02d}{(y + 1) % 100:02d}") for y in range(DESDE, ultima + 1)]


def bajar(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for intento in range(3):
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                b = r.read()
            for enc in ("utf-8-sig", "latin-1"):
                try:
                    return b.decode(enc)
                except UnicodeDecodeError:
                    continue
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            time.sleep(3 + 3 * intento)
        except Exception:  # noqa: BLE001
            time.sleep(3 + 3 * intento)
    return None


def fecha(s):
    for f in ("%d/%m/%Y", "%d/%m/%y"):
        try:
            return datetime.strptime(s.strip(), f).strftime("%Y-%m-%d")
        except ValueError:
            continue
    return None


def num(fila, k):
    v = (fila.get(k) or "").strip()
    try:
        return int(float(v))
    except ValueError:
        return None


def leer():
    partidos, desconocidos = [], {}
    for y, cod in temporadas():
        for div, comp in (("SP1", "1ª"), ("SP2", "2ª")):
            txt = bajar(f"https://www.football-data.co.uk/mmz4281/{cod}/{div}.csv")
            if not txt:
                print(f"  {cod} {div}: no disponible")
                continue
            n = 0
            for f in csv.DictReader(io.StringIO(txt)):
                hl, hv = (f.get("HomeTeam") or "").strip(), (f.get("AwayTeam") or "").strip()
                gl, gv, d = num(f, "FTHG"), num(f, "FTAG"), fecha(f.get("Date") or "")
                if not hl or not hv or gl is None or gv is None or not d:
                    continue
                li, vi = NOMBRES.get(hl), NOMBRES.get(hv)
                for nom, i in ((hl, li), (hv, vi)):
                    if not i:
                        desconocidos[nom] = max(desconocidos.get(nom, 0), y)
                st = {}
                for k, (a, b) in {"t": ("HS", "AS"), "p": ("HST", "AST"), "c": ("HC", "AC"),
                                  "a": ("HY", "AY"), "r": ("HR", "AR")}.items():
                    va, vb = num(f, a), num(f, b)
                    if va is not None and vb is not None:
                        st[k] = [va, vb]
                partidos.append({"f": d, "t": f"{y}/{(y + 1) % 100:02d}", "c": comp, "l": li or hl, "v": vi or hv,
                                 "li": li, "vi": vi, "g": [gl, gv], "h": [num(f, "HTHG"), num(f, "HTAG")], "s": st})
                n += 1
            print(f"  {cod} {div}: {n} partidos")
    partidos.sort(key=lambda p: p["f"])
    return partidos, desconocidos


def construir(partidos):
    equipos = sorted(set(NOMBRES.values()))
    out = {}
    for e in equipos:
        mios = [p for p in partidos if p["li"] == e or p["vi"] == e]
        forma = []
        for p in reversed(mios):
            casa = p["li"] == e
            forma.append([p["f"], p["v"] if casa else p["l"], "C" if casa else "F",
                          p["g"][0] if casa else p["g"][1], p["g"][1] if casa else p["g"][0], p["c"]])
            if len(forma) >= ULTIMOS:
                break
        rivales = {}
        for p in mios:
            r = p["vi"] if p["li"] == e else p["li"]
            if not r:
                continue
            x = rivales.setdefault(r, {"pj": 0, "g": 0, "e": 0, "p": 0, "gf": 0, "gc": 0, "ult": []})
            gf, gc = (p["g"][0], p["g"][1]) if p["li"] == e else (p["g"][1], p["g"][0])
            x["pj"] += 1
            x["gf"] += gf
            x["gc"] += gc
            x["g" if gf > gc else "e" if gf == gc else "p"] += 1
            x["ult"].append([p["f"], p["t"], p["c"], p["l"], p["v"], p["g"][0], p["g"][1], p["h"][0], p["h"][1], p["s"] or None])
        for x in rivales.values():
            x["ult"] = list(reversed(x["ult"][-ULTIMOS:]))
        if mios:
            out[e] = {"equipo": e, "desde": mios[0]["f"][:4], "forma": forma, "rivales": rivales}
    return out


def main():
    print("Descargando temporadas de football-data.co.uk…")
    partidos, desconocidos = leer()
    if len(partidos) < 1000:
        print(f"::error::Muy pocos partidos leídos ({len(partidos)}). No se guarda nada.")
        sys.exit(1)
    recientes = sorted(n for n, y in desconocidos.items() if y >= temporadas()[-1][0] - 1)
    if recientes:
        print("::notice::Equipos de las dos últimas temporadas sin id (no salen en el historial): " + ", ".join(recientes))
    datos = construir(partidos)
    os.makedirs(SALIDA, exist_ok=True)
    cambiados = 0
    for e, d in datos.items():
        p = os.path.join(SALIDA, e + ".json")
        nuevo = json.dumps(d, ensure_ascii=False, separators=(",", ":"))
        viejo = open(p, encoding="utf-8").read() if os.path.exists(p) else ""
        if nuevo != viejo:
            cambiados += 1
            if not SECO:
                open(p, "w", encoding="utf-8").write(nuevo)
    ej = datos.get("bet", {}).get("rivales", {}).get("sev", {})
    print(f"::notice::Historial: {len(partidos)} partidos, {len(datos)} equipos, {cambiados} archivos cambiados"
          + (f" · Betis–Sevilla: {ej.get('pj')} PJ, último {ej['ult'][0][:7]}" if ej else ""))


if __name__ == "__main__":
    main()
