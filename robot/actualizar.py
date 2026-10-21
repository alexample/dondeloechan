#!/usr/bin/env python3
"""Robot de datos de «Dónde lo Echan».

Se ejecuta en GitHub Actions varias veces al día. Lee webs públicas y actualiza
data/partidos.json, que leen la app de Android y la web:

  * LALIGA EA SPORTS y HYPERMOTION: laliga.com (horarios, resultados, operador y clasificación).
  * Canal exacto con su dial (todas las competiciones): futbolenlatv.es.
  * Primera Federación y Champions: resultados, jornadas y clasificaciones de VAVEL.
  * Copa del Rey: futbolenlatv.es (horarios y canales) y VAVEL (resultados).

Reglas: nunca borra partidos; solo añade partidos nuevos de los próximos 10 días y
actualiza los que ya existen. Si una fuente falla, se deja lo que había.
Uso: python3 robot/actualizar.py [--seco] [--muestras DIR]
"""
import datetime as dt
import html
import json
import os
import re
import sys
import time
import unicodedata
import urllib.request
from zoneinfo import ZoneInfo

MAD = ZoneInfo("Europe/Madrid")
UTC = dt.timezone.utc
RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATOS = os.path.join(RAIZ, "data", "partidos.json")
ALIAS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "alias.json")
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
HORIZONTE_DIAS = 10
SECO = "--seco" in sys.argv
MUESTRAS = sys.argv[sys.argv.index("--muestras") + 1] if "--muestras" in sys.argv else None

LOG = []


def log(msg):
    LOG.append(msg)
    print(msg, flush=True)


# ---------------------------------------------------------------- descarga
def get(url, nombre=None):
    """Descarga una página. Con --muestras lee el archivo local <nombre> si existe (para pruebas)."""
    if MUESTRAS and nombre:
        p = os.path.join(MUESTRAS, nombre)
        if os.path.exists(p):
            return open(p, encoding="utf-8").read()
        return None
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "es-ES,es;q=0.9"})
    err = None
    for intento in range(3):
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:  # noqa: BLE001
            err = e
            time.sleep(3 + intento * 3)
    log(f"⚠ No se pudo descargar {url}: {err}")
    return None


def next_data(texto):
    m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', texto or "", re.S)
    if not m:
        return None
    try:
        return json.loads(m.group(1))["props"]["pageProps"]
    except Exception:  # noqa: BLE001
        return None


# ---------------------------------------------------------------- utilidades
def norm(s):
    s = unicodedata.normalize("NFD", html.unescape(str(s))).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", " ", s).strip()


def slug(s):
    return norm(s).replace(" ", "-")


STOP = {"cd", "ud", "sd", "cf", "fc", "club", "de", "del", "la", "el", "sad", "rc", "rcd", "ca", "ad", "ce", "ue",
        "cp", "sc", "afc", "ac", "as", "sv", "fk", "sk", "1924", "balompie", "futbol", "cda", "ub", "rsd", "uds", "sl"}


EXPANDE = {"at": "atletico", "atl": "atletico", "utd": "united", "g": "gimnastica", "dep": "deportivo", "sp": "sporting",
           "r": "real", "nastic": "gimnastic", "munchen": "munich", "bayern": "bayern"}


def tokens(s):
    return {EXPANDE.get(t, t) for t in norm(s).split() if t not in STOP}


def abreviatura(nombre):
    ws = [w for w in norm(nombre).split() if w not in STOP and len(w) > 2] or norm(nombre).split() or ["xxx"]
    return ws[0][:3].upper()


def ahora():
    return dt.datetime.now(MAD)


def iso_mad(d):
    return d.astimezone(MAD).isoformat(timespec="seconds")


def temporada_txt(d):
    y = d.year if d.month >= 7 else d.year - 1
    return f"{y}-{(y + 1) % 100:02d}"


def jornada_slug(j):
    return slug(str(j).replace("·", " "))


# ---------------------------------------------------------------- equipos
class Equipos:
    """Traduce los nombres de cada web a nuestros ids, sin inventar coincidencias dudosas."""

    def __init__(self, partidos):
        self.alias = {k: v for k, v in json.load(open(ALIAS, encoding="utf-8")).items() if not k.startswith("_")}
        self.nombres = {}          # id -> nombre que ya usamos
        self.abbr = {}
        self.por_liga = {}         # liga -> {id}
        for m in partidos:
            for lado in ("local", "visitante"):
                i = m.get(lado + "Id")
                if not i:
                    continue
                self.nombres.setdefault(i, m.get(lado))
                if m.get(lado + "Abbr"):
                    self.abbr.setdefault(i, m.get(lado + "Abbr"))
                self.por_liga.setdefault(m["liga"], set()).add(i)
        self.nuevos = []

    def buscar(self, nombre, liga, crear=True):
        n = norm(nombre)
        if n in self.alias:
            return self.alias[n]
        cand = self.por_liga.get(liga, set()) if liga in (4, 5) else set(self.nombres)
        if liga == 4:  # en Champions también juegan los españoles
            cand = cand | {i for i in self.nombres if len(i) == 3}
        # 1) nombre idéntico
        for i in cand:
            if norm(self.nombres[i]) == n:
                return i
        # 2) parecido por palabras (solo si hay un único candidato claro)
        tk = tokens(nombre)
        if tk:
            puntos = []
            for i in cand:
                to = tokens(self.nombres[i]) | tokens(i.replace("-", " "))
                if not to:
                    continue
                j = len(tk & to) / len(tk | to)
                puntos.append((j, i))
            puntos.sort(reverse=True)
            if puntos and puntos[0][0] >= 0.5 and (len(puntos) == 1 or puntos[1][0] < puntos[0][0]):
                return puntos[0][1]
            # Nombre corto contenido en el nuestro ("Auriense" → "Auriense Cented Academy"), si es el único.
            inc = [i for i in cand if tk and (tk <= (tokens(self.nombres[i]) | tokens(i.replace("-", " ")))
                                                or (tokens(self.nombres[i]) and tokens(self.nombres[i]) <= tk))]
            if len(inc) == 1:
                return inc[0]
        if not crear:
            return None
        i = slug(nombre)
        if i not in self.nombres:
            self.nombres[i] = html.unescape(nombre).strip()
            self.abbr[i] = abreviatura(nombre)
            self.nuevos.append(f"{nombre} → {i}")
        self.por_liga.setdefault(liga, set()).add(i)
        return i


# ---------------------------------------------------------------- canales
DIAL = {"54": "mliga", "57": "mliga2", "55": "daznll", "58": "daznll2", "56": "hyper", "59": "hyper2", "53": "rfef",
        "60": "mlc", "61": "mlc2", "62": "mlc3", "180": "mlcmulti", "181": "mlc5", "182": "mlc6", "183": "mlc7",
        "184": "mlc8", "7": "mplus7"}
PRIORIDAD = ["mliga", "mliga2", "mliga3", "mliga4", "daznll", "daznll2", "hyper", "hyper2", "hyper3", "rfef", "daznrfef",
             "daznhm", "mlc", "mlc2", "mlc3", "mlc5", "mlc6", "mlc7", "mlc8", "mlcmulti", "mlcpend", "mcopa", "la1", "tdp",
             "mplus7", "daznfree", "orange1", "orange2", "rtveplay", "fanplay", "fclub"]
EXACTOS = set(PRIORIDAD) - {"mlcpend", "mcopa", "fanplay", "fclub", "rtveplay"}


def canal_fel(titulo, liga):
    """Convierte el título de canal de futbolenlatv.es en nuestra clave (o un objeto para teles autonómicas)."""
    t = html.unescape(titulo).strip()
    n = norm(t)
    if "por confirmar" in n or "tv bar" in n or "hdr" in n:
        return None
    m = re.search(r"\(M(\d+)", t)
    if m and m.group(1) in DIAL:
        return DIAL[m.group(1)]
    if "fanplay" in n:
        return "fanplay"
    if "footballclub" in n.replace(" ", ""):
        return "fclub"
    if "app gratis" in n or "dazn en abierto" in n:
        return "daznfree"
    if n.startswith("dazn"):
        return {1: "daznll", 2: "daznhm", 5: "daznrfef"}.get(liga)
    if "orange futbol 1" in n:
        return "orange1"
    if "orange futbol 2" in n:
        return "orange2"
    if n.startswith("laliga tv"):  # LALIGA TV Bar / M2…M5: señales para bares, no para casa
        return None
    if n == "teledeporte" or n.startswith("teledeporte "):
        return "tdp"
    if n in ("la 1", "la1") or n.startswith("la 1 "):
        return "la1"
    if n.startswith("rtve play"):
        return "rtveplay"
    if n in ("movistar", "movistar plus") or n.startswith("movistar plus ") and "m7" not in n:
        return "mcopa" if liga == 3 else None
    for clave, obj in AUTONOMICAS:
        if n.startswith(clave):
            return dict(obj)
    # Otras teles autonómicas: "TVG (Galicia)", "Esport3 (Cataluña)", "À Punt (C. Valenciana)"...
    m = re.match(r"^(.+?)\s*\(([^)]+)\)\s*(?::.*)?$", t)
    if m and not re.search(r"\d", m.group(2)):
        nombre, region = m.group(1).strip(), m.group(2).strip()
        return {"name": nombre, "short": abreviatura(nombre) if len(nombre) > 4 else nombre.upper()[:4], "op": "free",
                "dial": "TDT", "how": f"Gratis en la TDT de {region} y en su web."}
    if "play" in n or "youtube" in n or "replay" in n:
        nombre = re.sub(r"\s*\(.*?\)", "", t).strip()
        return {"name": nombre, "short": abreviatura(nombre), "op": "free", "dial": "",
                "how": "Gratis por internet, en su web o aplicación."}
    if n in ("aragon tv", "tv canaria", "tpa7", "ib3", "cmm", "betev", "tvg", "esport3"):
        return {"name": t, "short": t.upper()[:4], "op": "free", "dial": "TDT", "how": "Gratis en la TDT de su comunidad."}
    return None


AUTONOMICAS = [
    ("tvg", {"name": "TVG", "short": "TVG", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de Galicia y en la web de la CRTVG."}),
    ("esport3", {"name": "Esport3", "short": "E3", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de Cataluña y en 3Cat."}),
    ("aragon tv", {"name": "Aragón TV", "short": "ARA", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de Aragón y en Aragón Play."}),
    ("aragon play", {"name": "Aragón Play", "short": "ARP", "op": "free", "dial": "Web", "how": "Gratis en la web y la app de Aragón Play (sin emisión en la TDT)."}),
    ("tv canaria", {"name": "Televisión Canaria", "short": "TVC", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de Canarias y en la web de RTVC."}),
    ("television canaria", {"name": "Televisión Canaria", "short": "TVC", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de Canarias y en la web de RTVC."}),
    ("ib3", {"name": "IB3", "short": "IB3", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de las Illes Balears y en ib3.org."}),
    ("tpa", {"name": "TPA", "short": "TPA", "op": "free", "dial": "TDT", "how": "Televisión del Principado de Asturias: gratis en la TDT asturiana y en su web."}),
    ("beteve", {"name": "betevé", "short": "BTV", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de Barcelona y en beteve.cat."}),
    ("a punt", {"name": "À Punt", "short": "APT", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de la Comunitat Valenciana y en apuntmedia.es."}),
    ("cmm", {"name": "CMM", "short": "CMM", "op": "free", "dial": "TDT", "how": "Castilla-La Mancha Media: gratis en la TDT de Castilla-La Mancha y en CMMPlay."}),
    ("la 7 tv", {"name": "La 7 TV", "short": "LA7", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de la Región de Murcia y en su web."}),
    ("telemadrid", {"name": "Telemadrid", "short": "TM", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de la Comunidad de Madrid y en su web."}),
    ("canal sur", {"name": "Canal Sur", "short": "CS", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de Andalucía y en Canal Sur Más."}),
    ("etb", {"name": "ETB", "short": "ETB", "op": "free", "dial": "TDT", "how": "Gratis en la TDT del País Vasco y en eitb.eus."}),
    ("7rm", {"name": "La 7 TV", "short": "LA7", "op": "free", "dial": "TDT", "how": "Gratis en la TDT de la Región de Murcia y en su web."}),
]


def ordenar_canales(lista):
    vistos, salida = set(), []
    for c in lista:
        k = c if isinstance(c, str) else "obj:" + c.get("name", "")
        if c and k not in vistos:
            vistos.add(k)
            salida.append(c)

    def peso(c):
        if isinstance(c, str):
            return (PRIORIDAD.index(c) if c in PRIORIDAD else 50, c)
        # autonómicas: junto a los gratuitos; primero las de TDT; desempate por nombre para que el orden sea estable
        return (PRIORIDAD.index("mplus7") + (0.5 if c.get("dial") == "TDT" else 0.6), norm(c.get("name", "")))
    return sorted(salida, key=peso)


CANAL_LALIGA = {"movistar laliga": "mliga", "dazn": "daznll", "laliga tv hypermotion": "hyper", "dazn en abierto": "daznfree",
                "movistar plus": "mplus7", "orange futbol 1": "orange1", "orange futbol 2": "orange2"}


def canal_laliga(nombre, liga):
    n = norm(nombre)
    if n == "dazn":
        return "daznhm" if liga == 2 else "daznll"
    if n in CANAL_LALIGA:
        return CANAL_LALIGA[n]
    for clave, obj in AUTONOMICAS:
        if n.startswith(clave):
            return dict(obj)
    return None


# ---------------------------------------------------------------- fuentes
def leer_fel(texto, liga):
    """Filas de futbolenlatv.es: fecha UTC, equipos, etiqueta (grupo/ronda) y canales."""
    filas = []
    if not texto:
        return filas
    for tr in re.findall(r"<tr>\s*<td class=\"hora[^\"]*\".*?</tr>", texto, re.S):
        sd = re.search(r'itemprop="startDate" content="([^"]+)"', tr)
        loc = re.search(r'<td class="local">.*?<span title="([^"]+)"', tr, re.S)
        vis = re.search(r'<td class="visitante">.*?<span title="([^"]+)"', tr, re.S)
        if not (sd and loc and vis):
            continue
        sub = re.search(r'<label title="[^"]*">[^<]*</label>\s*<span title="([^"]+)"', tr)
        inicio = dt.datetime.fromisoformat(sd.group(1)).replace(tzinfo=UTC)
        canales = [canal_fel(t, liga) for t in re.findall(r'<li class="[^"]*" title="([^"]+)">', tr)]
        sin_confirmar = 'title="Canal por confirmar"' in tr
        filas.append({"inicio": inicio, "local": html.unescape(loc.group(1)), "visitante": html.unescape(vis.group(1)),
                      "sub": html.unescape(sub.group(1)) if sub else "", "canales": [c for c in canales if c],
                      "por_confirmar": sin_confirmar})
    return filas


MESES = {m: i + 1 for i, m in enumerate(["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
                                          "septiembre", "octubre", "noviembre", "diciembre"])}


MESES = {"enero": 1, "febrero": 2, "marzo": 3, "abril": 4, "mayo": 5, "junio": 6, "julio": 7, "agosto": 8,
         "septiembre": 9, "setiembre": 9, "octubre": 10, "noviembre": 11, "diciembre": 12}


def leer_vavel_partidos(texto, con_fecha=False):
    """Resultados o calendario de VAVEL: [(jornada, local, visitante, marcador|None, estado_txt)] (+ fecha AAAA-MM-DD si con_fecha)."""
    salida = []
    if not texto:
        return salida
    partes = re.split(r'<h2 class="mo">([^<]+)</h2>', texto)
    for k in range(1, len(partes), 2):
        cab = html.unescape(partes[k])
        jm = re.search(r"·\s*(.+)$", cab)
        jornada = jm.group(1).strip() if jm else ""
        fm = re.search(r"(\d{1,2}) de (\w+) de (\d{4})", cab)
        fecha = f"{fm.group(3)}-{MESES.get(norm(fm.group(2)), 0):02d}-{int(fm.group(1)):02d}" if fm and norm(fm.group(2)) in MESES else None
        for tr in re.findall(r'<tr class="fx[^"]*">(.*?)</tr>', partes[k + 1], re.S):
            h = re.search(r'class="h[^"]*"[^>]*>([^<]+)<', tr)
            a = re.search(r'class="a[^"]*"[^>]*>([^<]+)<', tr)
            sc = re.search(r'<span class="sc">\s*(\d+)\s*-\s*(\d+)\s*</span>', tr)
            wt = re.search(r'<span class="wt">([^<]+)</span>', tr)
            if h and a:
                fila = (jornada, html.unescape(h.group(1)).strip(), html.unescape(a.group(1)).strip(),
                        [int(sc.group(1)), int(sc.group(2))] if sc else None, (wt.group(1) if wt else "").strip())
                salida.append(fila + (fecha,) if con_fecha else fila)
    return salida


def leer_vavel_tabla(texto):
    filas = []
    if not texto:
        return filas
    t = re.search(r'<table class="hub-table hub-t-st">(.*?)</table>', texto, re.S)
    if not t:
        return filas
    for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", t.group(1), re.S):
        pos = re.search(r'<td class="pos">(\d+)</td>', tr)
        nom = re.search(r'<td class="t tm">.*?(?:<span class="nl"[^>]*>|<a [^>]*>)([^<]+)<', tr, re.S)
        nums = re.findall(r"<td(?: class=\"pts\")?>(-?\d+)</td>", tr)
        if pos and nom and len(nums) >= 7:
            pj, g, e, p, gf, gc, pts = map(int, nums[:7])
            filas.append((int(pos.group(1)), html.unescape(nom.group(1)).strip(), pj, g, e, p, gf, gc, pts))
    return sorted(filas)


# ---------------------------------------------------------------- tablas
def tabla_valida(filas, n):
    if len(filas) != n:
        return False
    if len({f[0] for f in filas}) != n:
        return False
    for f in filas:
        _, pj, g, e, p, gf, gc, pts = f[:8]
        if pj != g + e + p or abs(pts - (3 * g + e)) > 6:  # margen por sanciones
            return False
    return sum(f[5] for f in filas) == sum(f[6] for f in filas)


# ---------------------------------------------------------------- principal
RFEF = os.path.join(RAIZ, "data", "h2h", "rfef.json")


def guardar_rfef(filas):
    """data/h2h/rfef.json: [[fecha, jornada, grupo, localId, visitanteId, gl, gv], ...] de la temporada en curso."""
    if not filas:
        log("⚠ 1ª RFEF: sin resultados de VAVEL, se deja el historial anterior")
        return
    filas = sorted({tuple(f) for f in filas}, key=lambda f: (f[0], f[2], f[3]))
    viejo = []
    if os.path.exists(RFEF):
        try:
            viejo = json.load(open(RFEF, encoding="utf-8")).get("resultados", [])
        except Exception:  # noqa: BLE001
            viejo = []
    if len(filas) < len(viejo) * 0.8:
        log(f"⚠ 1ª RFEF: VAVEL da {len(filas)} resultados y antes había {len(viejo)}; se deja el anterior")
        return
    nuevo = {"temporada": temporada_txt(ahora()), "resultados": [list(f) for f in filas]}
    if nuevo.get("resultados") != viejo and not SECO:
        os.makedirs(os.path.dirname(RFEF), exist_ok=True)
        json.dump(nuevo, open(RFEF, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))
        log(f"1ª RFEF: {len(filas)} resultados de la temporada guardados")


def main():
    datos = json.load(open(DATOS, encoding="utf-8"))
    partidos = datos["partidos"]
    original = json.dumps({"p": partidos, "c": datos.get("clasificacion")}, sort_keys=True, ensure_ascii=False)
    clas = datos.get("clasificacion") or {}
    eq = Equipos(partidos)
    hoy = ahora()
    limite = hoy + dt.timedelta(days=HORIZONTE_DIAS)
    temp = temporada_txt(hoy)
    cambios = {"nuevos": [], "horas": 0, "canales": 0, "resultados": 0, "tablas": [], "detalle": []}

    items = {"resultados": [], "canales": [], "horas": [], "nuevos": []}

    def apuntar(tipo, m, texto):
        lista = items[tipo]
        if len(lista) < 60 and not any(x["id"] == m["id"] for x in lista):
            lista.append({"id": m["id"], "t": texto})

    def nota_cambio(texto):
        if len(cambios["detalle"]) < 30 and texto not in cambios["detalle"]:
            cambios["detalle"].append(texto)

    por_clave = {}
    for m in partidos:
        por_clave[(m["liga"], m["localId"], m["visitanteId"])] = m

    def nombre_de(i, fallback):
        return eq.nombres.get(i) or fallback

    def obtener(liga, li, vi, jornada, inicio, local, visitante, etiqueta=None):
        """Partido existente (por liga y equipos) o uno nuevo si cae dentro del horizonte."""
        m = por_clave.get((liga, li, vi))
        if m:
            return m
        if inicio is None or not (hoy - dt.timedelta(days=2) <= inicio <= limite) or not jornada:
            return None
        pid = f"2627-{liga}-{jornada_slug(jornada)}-{li}-{vi}"
        m = {"id": pid, "liga": liga, "jornada": str(jornada), "inicio": iso_mad(inicio),
             "local": nombre_de(li, local), "localId": li, "visitante": nombre_de(vi, visitante), "visitanteId": vi,
             "canales": [], "provisional": True}
        if li not in eq.nombres or len(li) > 3:
            m["localAbbr"] = eq.abbr.get(li) or abreviatura(local)
        if vi not in eq.nombres or len(vi) > 3:
            m["visitanteAbbr"] = eq.abbr.get(vi) or abreviatura(visitante)
        if etiqueta:
            m["etiqueta"] = etiqueta
        partidos.append(m)
        por_clave[(liga, li, vi)] = m
        cambios["nuevos"].append(f"{(COMP.get(liga))}: {m['local']} – {m['visitante']}")
        apuntar("nuevos", m, f"{m['local']} – {m['visitante']} ({COMP.get(liga)})")
        return m

    def poner_hora(m, inicio, pendiente=False):
        nuevo = iso_mad(inicio)
        if m.get("estado") == "final":
            return
        if pendiente:
            if not m.get("horaPendiente"):
                m["horaPendiente"] = True
            return
        if m.get("inicio") != nuevo or m.get("horaPendiente"):
            m["inicio"] = nuevo
            m.pop("horaPendiente", None)
            cambios["horas"] += 1
            nota_cambio(f"Nueva hora: {m['local']} – {m['visitante']}, {inicio.astimezone(MAD).strftime('%d/%m %H:%M')}")
            apuntar("horas", m, f"{m['local']} – {m['visitante']}: {inicio.astimezone(MAD).strftime('%d/%m a las %H:%M')}")

    def poner_canales(m, canales, exacto):
        if m.get("estado") == "final" or not canales:
            return
        previos = [c for c in (m.get("canales") or []) if isinstance(c, dict)]
        def igual(c):
            if not isinstance(c, dict):
                return c
            for p in previos:
                if norm(p.get("name", "")) == norm(c.get("name", "")):
                    return p
            for p in previos:
                a, b = norm(p.get("name", "")), norm(c.get("name", ""))
                if a == b or a.startswith(b) or b.startswith(a) or a.split()[:1] == b.split()[:1] and len(a.split()[0]) > 3:
                    return p
            return c
        nuevos = [igual(c) for c in canales]
        # Teles autonómicas que ya teníamos y la fuente no menciona: se conservan.
        for p in previos:
            if not any(isinstance(c, dict) and norm(c.get("name", "")) == norm(p.get("name", "")) for c in nuevos):
                nuevos.append(p)
        # No cambiar una señal concreta (Hypermotion 2/3, M+ LALIGA 2…) por la genérica de la misma familia.
        viejos = [c for c in (m.get("canales") or []) if isinstance(c, str)]
        for c in viejos:  # canales en abierto u Orange ya anunciados: se conservan
            if c in ("mplus7", "daznfree", "orange1", "orange2", "la1", "tdp") and c not in nuevos:
                nuevos.append(c)
        for gen, especificos in (("hyper", ("hyper2", "hyper3")), ("mliga", ("mliga2", "mliga3", "mliga4")),
                                 ("daznll", ("daznll2",)), ("mlc", ("mlc2", "mlc3", "mlc5", "mlc6", "mlc7", "mlc8"))):
            esp = [c for c in viejos if c in especificos]
            if gen in nuevos and esp and not any(c in especificos for c in nuevos):
                nuevos[nuevos.index(gen)] = esp[0]
        canales = ordenar_canales(nuevos)
        # Hasta 5 días antes las webs suelen poner el canal por defecto: no se da por confirmado.
        try:
            lejos = dt.datetime.fromisoformat(m["inicio"]) - ahora() > dt.timedelta(days=5)
        except Exception:  # noqa: BLE001
            lejos = False
        prov = (not exacto) or lejos
        if canales != m.get("canales") or bool(m.get("provisional")) != prov:
            # No empeorar: si ya había un canal exacto y ahora solo hay uno genérico, se deja el exacto.
            if prov and not m.get("provisional") and m.get("canales"):
                return
            antes_conf = m.get("canales") and not m.get("provisional")
            m["canales"] = canales
            m["provisional"] = prov
            if not prov and not antes_conf:
                nota_cambio(f"Canal confirmado: {m['local']} – {m['visitante']}")
            apuntar("canales", m, f"{m['local']} – {m['visitante']}" + (" · confirmado" if not prov and not antes_conf else ""))
            if not prov and m.get("nota", "").startswith("Anunciado en Movistar"):
                m.pop("nota", None)
            cambios["canales"] += 1

    def poner_resultado(m, marcador, estado="final"):
        if estado == "final" and marcador and (m.get("marcador") != marcador or m.get("estado") != "final"):
            m["marcador"] = marcador
            m["estado"] = "final"
            m.pop("minuto", None)
            cambios["resultados"] += 1
            nota_cambio(f"Resultado: {m['local']} {marcador[0]}-{marcador[1]} {m['visitante']}")
            apuntar("resultados", m, f"{m['local']} {marcador[0]}-{marcador[1]} {m['visitante']}")
        elif estado in ("aplazado", "suspendido") and m.get("estado") != estado and not m.get("marcador"):
            m["estado"] = estado
            cambios["resultados"] += 1

    extras_laliga = {}
    # ---- 1. LALIGA (Primera y Segunda) desde laliga.com
    for liga, comp, abrev in ((1, "laliga-easports", "ll1"), (2, "laliga-hypermotion", "ll2")):
        base = f"https://www.laliga.com/{comp}/resultados"
        pp = next_data(get(base, f"{abrev}_res.html"))
        if not pp or not pp.get("gameweek"):
            log(f"⚠ laliga.com {comp}: sin datos")
            continue
        semana = pp["gameweek"].get("week")
        paginas = [pp]
        for w in (semana - 1, semana + 1, semana + 2):
            if w >= 1:
                q = next_data(get(f"{base}/{temp}/jornada-{w}", f"{abrev}_j{w}.html"))
                if q and q.get("matches"):
                    paginas.append(q)
        CODIGO = {"MGA": "mal", "CEF": "clb"}
        for q in paginas:
            jw = (q.get("gameweek") or {}).get("week")
            for x in q.get("matches", []):
                li = CODIGO.get(x["home_team"]["shortname"], x["home_team"]["shortname"].lower())
                vi = CODIGO.get(x["away_team"]["shortname"], x["away_team"]["shortname"].lower())
                inicio = dt.datetime.fromisoformat(x["date"]).astimezone(UTC)
                pendiente = inicio.astimezone(UTC).strftime("%H:%M:%S") == "00:00:00"
                m = obtener(liga, li, vi, jw, inicio, x["home_team"].get("nickname"), x["away_team"].get("nickname"))
                if not m:
                    continue
                st = (x.get("status") or "").lower()
                if st == "fulltime" and x.get("home_score") is not None:
                    poner_resultado(m, [x["home_score"], x["away_score"]])
                elif "postpon" in st or "aplaz" in st:
                    poner_resultado(m, None, "aplazado")
                elif "suspend" in st:
                    poner_resultado(m, None, "suspendido")
                else:
                    poner_hora(m, inicio, pendiente)
                    cs = [c for c in (canal_laliga(c["name"], liga) for c in x.get("channels", [])) if c]
                    extras_laliga[(liga, li, vi)] = [c for c in cs if isinstance(c, dict) or c in ("mplus7", "daznfree", "orange1", "orange2")]
                    if not m.get("canales") or m.get("provisional"):
                        if liga == 2 and cs and "daznhm" not in cs:
                            cs.append("daznhm")
                        poner_canales(m, cs, exacto=False)
        # clasificación
        cp = next_data(get(f"https://www.laliga.com/{comp}/clasificacion", f"{abrev}_clas.html"))
        if cp and cp.get("standings"):
            filas = []
            for r in sorted(cp["standings"], key=lambda r: r["position"]):
                i = CODIGO.get(r["team"]["shortname"], r["team"]["shortname"].lower())
                filas.append([i, r["played"], r["won"], r["drawn"], r["lost"], r["goals_for"], r["goals_against"], r["points"]])
            if tabla_valida([[k] + f[1:] for k, f in enumerate(filas)], 20 if liga == 1 else 22):
                nuevo = {"tras": f"Jornada {max(f[1] for f in filas)}", "filas": filas}
                if clas.get(str(liga)) != nuevo:
                    clas[str(liga)] = nuevo
                    cambios["tablas"].append(COMP[liga])
            else:
                log(f"⚠ Clasificación de {comp} no cuadra: se deja la anterior")

    # ---- 2. Canales exactos y horarios de futbolenlatv.es (todas las competiciones)
    FEL = {1: "la-liga", 2: "segunda-division-espana", 5: "primera-division-rfef", 3: "copa-del-rey", 4: "liga-campeones"}
    vavel_jornadas = {}
    rfef = []   # resultados de la temporada de 1ª RFEF: historial («ida») y forma en la ficha
    for liga, grupo_urls in ((5, ["primera-federacion-grupo-1", "primera-federacion-grupo-2"]), (4, ["champions-league"])):
        for g in grupo_urls:
            for tipo in ("calendario", "resultados"):
                for (jor, h, a, sc, wt, fe) in leer_vavel_partidos(get(f"https://www.vavel.com/es/data/{g}/{tipo}/", f"vv_{g}_{tipo}.html"), con_fecha=True):
                    li, vi = eq.buscar(h, liga, crear=False), eq.buscar(a, liga, crear=False)
                    if li and vi:
                        vavel_jornadas[(liga, li, vi)] = (jor, g, sc, wt)
                        if liga == 5 and tipo == "resultados" and sc and fe and "final" in norm(wt or "final"):
                            rfef.append([fe, re.sub(r"\D", "", jor) or jor, g[-1], li, vi, sc[0], sc[1]])
    guardar_rfef(rfef)
    for liga, ruta in FEL.items():
        filas = leer_fel(get(f"https://www.futbolenlatv.es/competicion/{ruta}", f"fel_{ruta}.html"), liga)
        if not filas:
            log(f"⚠ futbolenlatv {ruta}: sin partidos")
            continue
        for f in filas:
            if f["inicio"] > limite + dt.timedelta(days=1):
                continue
            li, vi = eq.buscar(f["local"], liga), eq.buscar(f["visitante"], liga)
            m = por_clave.get((liga, li, vi))
            if not m and liga in (3, 4, 5):
                jornada, etiqueta = None, None
                if liga == 5:
                    vj = vavel_jornadas.get((liga, li, vi))
                    jornada = re.sub(r"\D", "", vj[0]) if vj else None
                    etiqueta = f["sub"] or None
                elif liga == 4:
                    vj = vavel_jornadas.get((liga, li, vi))
                    n = re.sub(r"\D", "", vj[0]) if vj else ""
                    jornada = f"Fase liga · J{n}" if n else None
                else:
                    sub = norm(f["sub"])
                    jornada = {"1 ronda": "Primera ronda", "2 ronda": "Segunda ronda", "dieciseisavos de final": "Dieciseisavos",
                               "octavos de final": "Octavos", "cuartos de final": "Cuartos", "semifinales": "Semifinal",
                               "final": "Final"}.get(sub, f["sub"] or None)
                m = obtener(liga, li, vi, jornada, f["inicio"], f["local"], f["visitante"], etiqueta)
            if not m:
                continue
            poner_hora(m, f["inicio"])
            canales = list(f["canales"])
            if not canales:
                continue
            if liga in (1, 2):
                canales += [c for c in extras_laliga.get((liga, li, vi), []) if c not in canales]
            exacto = any(isinstance(c, dict) or c in EXACTOS for c in canales)
            if liga == 5:
                if "fanplay" not in canales:
                    canales.append("fanplay")
                if "fclub" not in canales:
                    canales.append("fclub")
                tiene_tv = any(c in ("rfef", "daznrfef") for c in canales)
                autos = [c for c in canales if isinstance(c, dict)]
                auton = next((c["name"] for c in autos if c.get("dial") == "TDT"), autos[0]["name"] if autos else None)
                if not tiene_tv and m.get("estado") != "final":
                    nota = ("No está entre los 10 de Movistar Plus+ y DAZN. En abierto lo emite " + auton +
                            "; completo, en FANPLAY TV y FootballClub.") if auton else \
                        "No está entre los 10 de Movistar Plus+ y DAZN: solo se puede ver en FANPLAY TV o FootballClub (de pago)."
                    if m.get("nota") != nota:
                        m["nota"] = nota
                elif m.get("nota", "").startswith("No está entre los 10"):
                    m.pop("nota", None)
                exacto = True
            if liga == 4 and "mlc" in canales and m.get("inicio", "")[11:16] == "21:00" and "mlcmulti" not in canales:
                canales.append("mlcmulti")
            poner_canales(m, canales, exacto)

    # ---- 3. Resultados de VAVEL (Primera Federación, Champions y Copa)
    for (liga, li, vi), (jor, g, sc, wt) in vavel_jornadas.items():
        m = por_clave.get((liga, li, vi))
        if not m:
            continue
        w = norm(wt)
        if sc and w in ("final", "finalizado", "ft", "fin", "tras prorroga", "penaltis") or (sc and "final" in w):
            poner_resultado(m, sc)
        elif "aplaz" in w or "postp" in w:
            poner_resultado(m, None, "aplazado")
        elif "suspend" in w:
            poner_resultado(m, None, "suspendido")
    for (jor, h, a, sc, wt) in leer_vavel_partidos(get("https://www.vavel.com/es/data/copa-del-rey/resultados/", "vv_copa-del-rey.html")):
        li, vi = eq.buscar(h, 3, crear=False), eq.buscar(a, 3, crear=False)
        m = por_clave.get((3, li, vi)) if li and vi else None
        if m and sc and "final" in norm(wt):
            poner_resultado(m, sc)

    # ---- 4. Clasificaciones de VAVEL (Primera Federación y Champions)
    grupos = {}
    for g, nombre in (("primera-federacion-grupo-1", "Grupo 1"), ("primera-federacion-grupo-2", "Grupo 2")):
        filas = leer_vavel_tabla(get(f"https://www.vavel.com/es/data/{g}/clasificacion/", f"vv_{g}_clasificacion.html"))
        if tabla_valida([[f[0], *f[2:9]] for f in filas], 20):
            jor = max(f[2] for f in filas)
            grupos[nombre] = {"tras": f"Jornada {jor}",
                              "filas": [[eq.buscar(f[1], 5), *f[2:9], eq.nombres.get(eq.buscar(f[1], 5), f[1])] for f in filas]}
        else:
            log(f"⚠ Clasificación de {nombre} (1ª RFEF) incompleta o no cuadra: se deja la anterior")
    if grupos:
        viejo = (clas.get("5") or {}).get("grupos", {})
        nuevo = {"grupos": {**viejo, **grupos}}
        if clas.get("5") != nuevo:
            clas["5"] = nuevo
            cambios["tablas"].append("Primera Federación")
    filas = leer_vavel_tabla(get("https://www.vavel.com/es/data/champions-league/clasificacion/", "vv_champions-league_clasificacion.html"))
    if tabla_valida([[f[0], *f[2:9]] for f in filas], 36):
        nuevo = {"tras": f"Jornada {max(f[2] for f in filas)}",
                 "filas": [[eq.buscar(f[1], 4), *f[2:9], eq.nombres.get(eq.buscar(f[1], 4), f[1])] for f in filas]}
        if clas.get("4") != nuevo:
            clas["4"] = nuevo
            cambios["tablas"].append("Champions")
    elif filas:
        log("⚠ Clasificación de la Champions incompleta o no cuadra: se deja la anterior")

    # ---- guardar
    partidos.sort(key=lambda m: (m["inicio"], m["liga"], m["id"]))
    datos["partidos"] = partidos
    datos["clasificacion"] = clas
    final = json.dumps({"p": partidos, "c": clas}, sort_keys=True, ensure_ascii=False)
    hubo = final != original
    resumen = [f"Robot {iso_mad(hoy)} — {'con cambios' if hubo else 'sin cambios'}",
               f"Partidos nuevos: {len(cambios['nuevos'])}" + (" (" + "; ".join(cambios['nuevos'][:12]) + ")" if cambios['nuevos'] else ""),
               f"Horas cambiadas: {cambios['horas']} · Canales actualizados: {cambios['canales']} · Resultados: {cambios['resultados']}",
               f"Clasificaciones: {', '.join(cambios['tablas']) or 'sin cambios'}"]
    if eq.nuevos:
        resumen.append("Equipos nuevos (revisar si alguno ya existía con otro nombre): " + "; ".join(eq.nuevos))
    for r in resumen:
        log(r)
    # Registro de pasadas del robot (lo muestran la app y la web).
    rb = datos.get("robot") or {}
    hist = []
    for h in rb.get("historial", []):
        try:
            if dt.datetime.fromisoformat(h["hora"]) > hoy - dt.timedelta(days=2):
                hist.append(h)
        except Exception:  # noqa: BLE001
            pass
    entrada = {"hora": iso_mad(hoy), "nuevos": len(cambios["nuevos"]), "horas": cambios["horas"],
               "canales": cambios["canales"], "resultados": cambios["resultados"], "tablas": cambios["tablas"],
               "detalle": [f"Nuevo partido: {x.split(': ', 1)[-1]}" for x in cambios["nuevos"][:10]] + cambios["detalle"][:20],
               "avisos": len([x for x in LOG if x.startswith("⚠")]), "items": items}
    hist.insert(0, entrada)
    datos["robot"] = {"comprobado": iso_mad(hoy), "cambio": iso_mad(hoy) if hubo else rb.get("cambio"), "historial": hist[:24]}
    if hubo:
        datos["actualizado"] = iso_mad(hoy)
    if not SECO:
        with open(DATOS, "w", encoding="utf-8") as fh:
            json.dump(datos, fh, ensure_ascii=False, indent=1)
            fh.write("\n")
    if os.environ.get("GITHUB_ACTIONS"):
        # Una sola anotación con todo el resumen (se puede leer desde la API de GitHub).
        texto = "\n".join(LOG).replace("%", "%25").replace("\r", "").replace("\n", "%0A")
        print(f"::notice title=Resumen del robot::{texto}", flush=True)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write("\n".join("- " + x for x in LOG) + "\n")


COMP = {1: "Primera", 2: "Segunda", 3: "Copa", 4: "Champions", 5: "1ª RFEF"}

if __name__ == "__main__":
    main()
