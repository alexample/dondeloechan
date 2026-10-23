/**
 * Asistente de «Dónde lo Echan» — Cloudflare Worker con Workers AI (modelos abiertos).
 *
 * Instalación (panel de Cloudflare):
 *   1. Workers & Pages → Create → Worker → Deploy (el «Hello World» de ejemplo).
 *   2. Edit code → borra todo, pega este archivo → Deploy.
 *   3. Settings → Bindings → Add → Workers AI → nombre de la variable: AI → Deploy.
 *
 * Se despliega solo desde GitHub (Cloudflare Workers Builds, carpeta ia/, rama main).
 * La app y la web envían: {pregunta, historial:[{rol,texto}], contexto:"…guía en texto…", hoy:"…"}
 * El worker responde: {respuesta:"…"}. No guarda nada.
 */

const MODELOS = ["@cf/meta/llama-3.3-70b-instruct-fp8-fast", "@cf/meta/llama-3.1-8b-instruct-fast"];
const ORIGENES = ["https://alexample.github.io", "https://appassets.androidplatform.net"];
const MAX_PREGUNTA = 500;
// Horas UTC en que el cron también lanza el robot de RunningSpain ([3] = 05:00 en verano). null = cada hora (pruebas).
const RUNNINGSPAIN_HORAS = [3];
// Relé hacia MacroDroid para RunningSpain: el servidor de MacroDroid no entrega al móvil las llamadas
// que salen de GitHub, así que el robot llama aquí y el worker reenvía. Solo reenvía a la URL cuya
// huella SHA-256 coincide (la URL real no está en el código, que es público).
const MACRODROID_HUELLAS = ["62258f969a5362c26f8f632e3f6ffc3d83b78f81d9f14c5f900be5132bf6a178"];
const MAX_CONTEXTO = 60000;
const LIMITE_MIN = 6;      // preguntas por minuto y persona (IP)
const LIMITE_DIA = 60;     // preguntas por día y persona (IP)

// Marcadores en directo (football-data.org, plan gratuito: 10 consultas/minuto por clave).
// La web no puede consultarlo directamente (el navegador lo bloquea), así que pasa por aquí.
// Se guarda 50 s en la caché de Cloudflare: da igual cuánta gente mire, se hace como mucho ~1 consulta por minuto.
const FD_CLAVE = "2e0b52f50c68465084f4da6f3c3c7613";   // la misma que ya va en config.js (pública)
const FD_COMP = new Set(["PD", "CL", "SD", "CDR"]);

async function marcadores(request, env, ctx, origen) {
  const u = new URL(request.url);
  const comp = (u.searchParams.get("competitions") || "").split(",").filter(c => FD_COMP.has(c)).join(",");
  const desde = u.searchParams.get("dateFrom") || "", hasta = u.searchParams.get("dateTo") || "";
  if (!comp || !/^\d{4}-\d{2}-\d{2}$/.test(desde) || !/^\d{4}-\d{2}-\d{2}$/.test(hasta))
    return json({ error: "Parámetros no válidos." }, 400, origen);
  const destino = `https://api.football-data.org/v4/matches?competitions=${comp}&dateFrom=${desde}&dateTo=${hasta}`;
  const clave = new Request("https://cache.dondeloechan/marcadores?" + comp + desde + hasta);
  const cache = caches.default;
  let r = await cache.match(clave);
  if (!r) {
    const fd = await fetch(destino, { headers: { "X-Auth-Token": env.FD_CLAVE || FD_CLAVE } });
    const cuerpo = await fd.text();
    if (!fd.ok) return json({ error: "football-data " + fd.status }, 502, origen);
    r = new Response(cuerpo, { headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "public, max-age=50" } });
    ctx.waitUntil(cache.put(clave, r.clone()));
  }
  return new Response(r.body, { status: 200, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...cors(origen) } });
}

// Historial y forma para partidos sin CSV (Champions): football-data.org, guardado en la caché de Cloudflare.
async function fdGet(ruta, env, ctx, segundos) {
  const clave = new Request("https://cache.dondeloechan/fd" + ruta);
  const cache = caches.default;
  let r = await cache.match(clave);
  if (r) return r.json();
  const fd = await fetch("https://api.football-data.org/v4" + ruta, { headers: { "X-Auth-Token": env.FD_CLAVE || FD_CLAVE } });
  if (!fd.ok) throw new Error("football-data " + fd.status);
  const texto = await fd.text();
  ctx.waitUntil(cache.put(clave, new Response(texto, { headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=" + segundos } })));
  return JSON.parse(texto);
}
const QUITAR = new Set(["fc", "cf", "cd", "ud", "sd", "rcd", "ad", "ce", "club", "de", "del", "la", "el", "real", "sad", "ac", "as", "afc", "sc", "sv", "vfb", "fk", "ssc", "rc", "bv", "sport", "balompie", "futbol", "calcio", "1."]);
const toks = n => String(n || "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().replace(/[^a-z0-9 ]+/g, " ").split(/\s+/).filter(w => w.length > 2 && !QUITAR.has(w));
function parecido(nuestro, eq) {
  const a = toks(nuestro), b = new Set([...toks(eq.name), ...toks(eq.shortName), ...toks(eq.tla)]);
  if (!a.length) return false;
  return a.some(w => b.has(w) || [...b].some(x => x.length > 3 && (x.startsWith(w) || w.startsWith(x))));
}
const temporadaDe = f => { const y = +f.slice(0, 4), m = +f.slice(5, 7); const i = m >= 7 ? y : y - 1; return `${i}/${String((i + 1) % 100).padStart(2, "0")}`; };
const COMP_NOMBRE = { CL: "Champions", PD: "Primera", SD: "Segunda", CDR: "Copa", EL: "Europa League", ECL: "Conference", BL1: "Bundesliga", PL: "Premier", SA: "Serie A", FL1: "Ligue 1", DED: "Eredivisie", PPL: "Liga Portugal" };

async function historialFD(request, env, ctx, origen) {
  const u = new URL(request.url);
  const comp = (u.searchParams.get("comp") || "CL").toUpperCase(), fecha = u.searchParams.get("fecha") || "";
  const local = (u.searchParams.get("local") || "").slice(0, 60), visitante = (u.searchParams.get("visitante") || "").slice(0, 60);
  if (!FD_COMP.has(comp) || !/^\d{4}-\d{2}-\d{2}$/.test(fecha) || !local || !visitante) return json({ error: "Parámetros no válidos." }, 400, origen);
  const dia = n => { const d = new Date(fecha + "T12:00:00Z"); d.setUTCDate(d.getUTCDate() + n); return d.toISOString().slice(0, 10); };
  const lista = await fdGet(`/matches?competitions=${comp}&dateFrom=${dia(-1)}&dateTo=${dia(1)}`, env, ctx, 3600);
  const m = (lista.matches || []).find(x => parecido(local, x.homeTeam) && parecido(visitante, x.awayTeam));
  if (!m) return json({ error: "Partido no encontrado." }, 404, origen);
  const [h, fl, fv] = await Promise.all([
    fdGet(`/matches/${m.id}/head2head?limit=10`, env, ctx, 43200),
    fdGet(`/teams/${m.homeTeam.id}/matches?status=FINISHED&limit=5`, env, ctx, 10800).catch(() => null),
    fdGet(`/teams/${m.awayTeam.id}/matches?status=FINISHED&limit=5`, env, ctx, 10800).catch(() => null),
  ]);
  const idL = m.homeTeam.id, nom = t => t.id === idL ? local : t.id === m.awayTeam.id ? visitante : (t.shortName || t.name);
  const vs = { pj: 0, g: 0, e: 0, p: 0, gf: 0, gc: 0, ult: [] };
  const jugados = (h.matches || []).filter(x => x.status === "FINISHED" && x.score && x.score.fullTime && x.score.fullTime.home != null)
    .sort((a, b) => b.utcDate.localeCompare(a.utcDate));
  for (const x of jugados) {
    const ft = x.score.fullTime, ht = x.score.halfTime || {}, casa = x.homeTeam.id === idL;
    const gf = casa ? ft.home : ft.away, gc = casa ? ft.away : ft.home;
    vs.pj++; vs.gf += gf; vs.gc += gc; vs[gf > gc ? "g" : gf === gc ? "e" : "p"]++;
    const f = x.utcDate.slice(0, 10);
    vs.ult.push([f, temporadaDe(f), COMP_NOMBRE[x.competition && x.competition.code] || (x.competition && x.competition.name) || "", nom(x.homeTeam), nom(x.awayTeam), ft.home, ft.away, ht.home ?? null, ht.away ?? null, null]);
  }
  const forma = (d, id) => !d ? [] : (d.matches || []).filter(x => x.score && x.score.fullTime && x.score.fullTime.home != null)
    .sort((a, b) => b.utcDate.localeCompare(a.utcDate)).slice(0, 5).map(x => {
      const casa = x.homeTeam.id === id, ft = x.score.fullTime;
      return [x.utcDate.slice(0, 10), casa ? (x.awayTeam.shortName || x.awayTeam.name) : (x.homeTeam.shortName || x.homeTeam.name), casa ? "C" : "F", casa ? ft.home : ft.away, casa ? ft.away : ft.home, COMP_NOMBRE[x.competition && x.competition.code] || ""];
    });
  return new Response(JSON.stringify({ fuente: "football-data.org", vs, formaL: forma(fl, idL), formaV: forma(fv, m.awayTeam.id) }),
    { status: 200, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...cors(origen) } });
}

// Marcadores en tiempo real (marcador, minuto y parte) del marcador público de ESPN. Caché de 20 s para todos.
const ESPN_LIGAS = new Set(["esp.1", "esp.2", "esp.copa_del_rey", "uefa.champions", "uefa.europa.conf"]);
async function vivo(request, env, ctx, origen) {
  const u = new URL(request.url);
  const ligas = (u.searchParams.get("ligas") || "").split(",").filter(l => ESPN_LIGAS.has(l)).slice(0, 5);
  const fechas = (u.searchParams.get("fechas") || "").split(",").filter(f => /^\d{8}$/.test(f)).slice(0, 2);
  if (!ligas.length || !fechas.length) return json({ error: "Parámetros no válidos." }, 400, origen);
  const cache = caches.default, out = [];
  await Promise.all(ligas.flatMap(liga => fechas.map(async fecha => {
    const url = `https://site.api.espn.com/apis/site/v2/sports/soccer/${liga}/scoreboard?dates=${fecha}`;
    const clave = new Request("https://cache.dondeloechan/espn/" + liga + "/" + fecha);
    let r = await cache.match(clave), d;
    if (r) d = await r.json();
    else {
      const x = await fetch(url, { headers: { "User-Agent": "Mozilla/5.0 (DondeLoEchan)" } });
      if (!x.ok) return;
      d = await x.json();
      ctx.waitUntil(cache.put(clave, new Response(JSON.stringify(d), { headers: { "Content-Type": "application/json", "Cache-Control": "public, max-age=20" } })));
    }
    for (const ev of d.events || []) {
      const c = (ev.competitions || [])[0]; if (!c) continue;
      const eq = l => (c.competitors || []).find(t => t.homeAway === l) || {};
      const h = eq("home"), a = eq("away"), st = ev.status || {}, ty = st.type || {};
      const team = t => ({ name: (t.team || {}).displayName || "", shortName: (t.team || {}).shortDisplayName || "", tla: (t.team || {}).abbreviation || "" });
      const idL = String((h.team || {}).id || ""), idV = String((a.team || {}).id || "");
      // Goles y expulsiones: minuto, jugador y equipo (L = local, V = visitante).
      const eventos = (c.details || []).filter(e => !e.shootout && (e.scoringPlay || e.redCard)).map(e => {
        const eqId = String((e.team || {}).id || ""), j = (e.athletesInvolved || [])[0] || {};
        return { t: e.scoringPlay ? "g" : "r", min: (e.clock || {}).displayValue || "", seg: (e.clock || {}).value || 0,
          j: j.displayName || j.shortName || "", lado: eqId === idL ? "L" : eqId === idV ? "V" : "",
          pen: !!e.penaltyKick, pp: !!e.ownGoal };
      }).filter(e => e.lado);
      out.push({ liga, utcDate: ev.date, homeTeam: team(h), awayTeam: team(a),
        gl: h.score != null ? +h.score : null, gv: a.score != null ? +a.score : null,
        estado: ty.state || "", detalle: ty.name || "", reloj: st.displayClock || "", parte: st.period || 0, eventos });
    }
  })));
  return new Response(JSON.stringify({ fuente: "ESPN", partidos: out }), { status: 200, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...cors(origen) } });
}

async function macrodroid(request) {
  const hook = request.headers.get("X-Hook") || "";
  if (!hook.startsWith("https://trigger.macrodroid.com/")) return new Response("Destino no válido", { status: 400 });
  const huella = [...new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(hook)))]
    .map(b => b.toString(16).padStart(2, "0")).join("");
  if (!MACRODROID_HUELLAS.includes(huella)) return new Response("Destino no permitido", { status: 403 });
  const cuerpo = (await request.text()).slice(0, 3800);
  const r = await fetch(hook, { method: "POST", body: cuerpo, headers: { "Content-Type": "text/plain; charset=utf-8" } });
  return new Response(await r.text(), { status: r.status, headers: { "Content-Type": "text/plain; charset=utf-8" } });
}

const uso = new Map();     // ip -> {min, tMin, dia, tDia}  (memoria del worker: freno básico contra abusos)

function cors(origen) {
  const ok = ORIGENES.includes(origen);
  return {
    "Access-Control-Allow-Origin": ok ? origen : ORIGENES[0],
    "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "86400",
    "Vary": "Origin",
  };
}

function json(obj, estado, origen) {
  return new Response(JSON.stringify(obj), {
    status: estado,
    headers: { "Content-Type": "application/json; charset=utf-8", ...cors(origen) },
  });
}

function permitir(ip) {
  const ahora = Date.now();
  const u = uso.get(ip) || { min: 0, tMin: ahora, dia: 0, tDia: ahora };
  if (ahora - u.tMin > 60_000) { u.min = 0; u.tMin = ahora; }
  if (ahora - u.tDia > 86_400_000) { u.dia = 0; u.tDia = ahora; }
  if (u.min >= LIMITE_MIN || u.dia >= LIMITE_DIA) return false;
  u.min++; u.dia++;
  uso.set(ip, u);
  if (uso.size > 5000) uso.clear();
  return true;
}

const SISTEMA = (hoy, contexto) => `Eres el asistente de «Dónde lo Echan», una guía de fútbol español en televisión (LALIGA EA SPORTS, LALIGA HYPERMOTION, Primera Federación, Copa del Rey, Champions y Conference League).

Hoy es ${hoy} (hora peninsular española).

REGLAS
- Responde SIEMPRE en español, de forma breve y directa (2–6 frases o una lista corta).
- Usa ÚNICAMENTE los datos de la GUÍA de abajo. Si algo no está en la guía (por ejemplo, alineaciones, lesiones, fichajes, partidos de otras ligas), dilo con naturalidad y no lo inventes.
- Para los canales indica el nombre y el dial cuando aparezca (p. ej. «M+ LALIGA, dial 54»). Si el canal es provisional, dilo.
- Las horas de la guía son peninsulares.
- Cuando menciones un partido concreto de la guía, añade justo después su referencia entre dobles corchetes, exactamente así: [[ID]] (el ID de la primera columna). La app la convierte en un botón para abrir la ficha. No muestres los ID de otra forma.
- Al final de CADA respuesta añade una última línea, exactamente con este formato, con 3 preguntas cortas (máximo 9 palabras cada una) que el usuario podría hacerte a continuación, relacionadas con lo que acaba de preguntar (el mismo partido, sus equipos, su clasificación, otros partidos de ese día o de la jornada) y que se puedan responder con la GUÍA:
SUGERENCIAS: pregunta 1 | pregunta 2 | pregunta 3
- Si la GUÍA empieza con «PARTIDO SELECCIONADO», el usuario está mirando ese partido: las preguntas sin más detalle («¿cómo quedó la ida?», «¿quién gana más?», «estadísticas») se refieren a él. Usa el HISTORIAL, los ÚLTIMOS ENFRENTAMIENTOS y la FORMA que vienen debajo; cita fecha y temporada de los partidos que menciones. No inventes datos que no estén (por ejemplo, goleadores o posesión) y NUNCA uses tu memoria para resultados o enfrentamientos pasados: si no vienen en la GUÍA, di que no tienes ese dato.
- Si te preguntan algo que no tiene que ver con el fútbol o con la guía, responde amablemente que solo puedes ayudar con los partidos, canales, resultados y clasificaciones de la guía.

GUÍA (puede empezar con el PARTIDO SELECCIONADO y su historial; después, una línea por partido: ID | competición | fecha y hora | local – visitante | canales | estado)
${contexto}`;

export default {
  /** Cada hora (wrangler.jsonc → triggers.crons): pide a GitHub que pase el robot de datos.
   *  Necesita el secreto GH_TOKEN (llave de GitHub con permiso «Contents: Read and write» sobre dondeloechan). */
  async scheduled(evento, env, ctx) {
    if (!env.GH_TOKEN) { console.log("Falta el secreto GH_TOKEN: no se puede avisar al robot."); return; }
    const avisar = async (repo) => {
      const r = await fetch(`https://api.github.com/repos/${repo}/dispatches`, {
        method: "POST",
        headers: {
          "Authorization": "Bearer " + env.GH_TOKEN,
          "Accept": "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28",
          "User-Agent": "dondeloechan-ia",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ event_type: "robot" }),
      });
      console.log(`Aviso al robot de ${repo}:`, r.status, r.status === 204 ? "OK" : await r.text());
    };
    const tareas = [avisar("alexample/dondeloechan")];
    // RunningSpain (calendario de carreras): una vez al día a las 03 UTC.
    // RUNNINGSPAIN_HORAS = null → cada hora (modo pruebas temporal).
    const hora = new Date(evento.scheduledTime).getUTCHours();
    if (RUNNINGSPAIN_HORAS === null || RUNNINGSPAIN_HORAS.includes(hora)) tareas.push(avisar("alexample/RunningSpain"));
    await Promise.all(tareas);
  },

  async fetch(request, env, ctx) {
    const origen = request.headers.get("Origin") || "";
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(origen) });
    if (request.method === "POST" && new URL(request.url).pathname === "/macrodroid") return macrodroid(request);
    if (request.method === "GET" && new URL(request.url).pathname === "/vivo") {
      if (!ORIGENES.includes(origen)) return json({ error: "Origen no permitido." }, 403, origen);
      try { return await vivo(request, env, ctx, origen); }
      catch (e) { return json({ error: "No hay marcadores ahora mismo." }, 502, origen); }
    }
    if (request.method === "GET" && new URL(request.url).pathname === "/historial") {
      if (!ORIGENES.includes(origen)) return json({ error: "Origen no permitido." }, 403, origen);
      try { return await historialFD(request, env, ctx, origen); }
      catch (e) { return json({ error: "No hay historial ahora mismo.", detalle: String(e.message || e).slice(0, 120) }, 502, origen); }
    }
    if (request.method === "GET" && new URL(request.url).pathname === "/marcadores") {
      if (!ORIGENES.includes(origen)) return json({ error: "Origen no permitido." }, 403, origen);
      try { return await marcadores(request, env, ctx, origen); }
      catch (e) { return json({ error: "No hay marcadores ahora mismo." }, 502, origen); }
    }
    if (request.method !== "POST") return json({ error: "Usa POST." }, 405, origen);
    if (!ORIGENES.includes(origen)) return json({ error: "Origen no permitido." }, 403, origen);
    if (!env.AI) return json({ error: "Falta conectar Workers AI (variable AI) en los ajustes del worker." }, 500, origen);

    const ip = request.headers.get("CF-Connecting-IP") || "anon";
    if (!permitir(ip)) return json({ error: "Has hecho muchas preguntas seguidas. Espera un poco y vuelve a intentarlo." }, 429, origen);

    let datos;
    try { datos = await request.json(); } catch { return json({ error: "Petición no válida." }, 400, origen); }
    const pregunta = String(datos.pregunta || "").trim().slice(0, MAX_PREGUNTA);
    const contexto = String(datos.contexto || "").slice(0, MAX_CONTEXTO);
    const hoy = String(datos.hoy || "").slice(0, 80);
    if (!pregunta) return json({ error: "Escribe una pregunta." }, 400, origen);

    const historial = (Array.isArray(datos.historial) ? datos.historial : []).slice(-6)
      .filter(h => h && (h.rol === "usuario" || h.rol === "asistente"))
      .map(h => ({ role: h.rol === "usuario" ? "user" : "assistant", content: String(h.texto || "").slice(0, 1200) }));

    const mensajes = [{ role: "system", content: SISTEMA(hoy, contexto) }, ...historial, { role: "user", content: pregunta }];

    let ultimoError = "";
    for (const modelo of MODELOS) {
      try {
        const r = await env.AI.run(modelo, { messages: mensajes, max_tokens: 600, temperature: 0.2 });
        const texto = (r && (r.response || (r.result && r.result.response))) || "";
        if (texto) return json({ respuesta: texto.trim(), modelo }, 200, origen);
      } catch (e) {
        ultimoError = String(e && e.message || e);
      }
    }
    return json({ error: "La IA no ha podido responder ahora mismo. Inténtalo en un rato.", detalle: ultimoError.slice(0, 200) }, 502, origen);
  },
};
