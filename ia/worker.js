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

const SISTEMA = (hoy, contexto) => `Eres el asistente de «Dónde lo Echan», una guía de fútbol español en televisión (LALIGA EA SPORTS, LALIGA HYPERMOTION, Primera Federación, Copa del Rey y Champions).

Hoy es ${hoy} (hora peninsular española).

REGLAS
- Responde SIEMPRE en español, de forma breve y directa (2–6 frases o una lista corta).
- Usa ÚNICAMENTE los datos de la GUÍA de abajo. Si algo no está en la guía (por ejemplo, alineaciones, lesiones, fichajes, partidos de otras ligas), dilo con naturalidad y no lo inventes.
- Para los canales indica el nombre y el dial cuando aparezca (p. ej. «M+ LALIGA, dial 54»). Si el canal es provisional, dilo.
- Las horas de la guía son peninsulares.
- Cuando menciones un partido concreto de la guía, añade justo después su referencia entre dobles corchetes, exactamente así: [[ID]] (el ID de la primera columna). La app la convierte en un botón para abrir la ficha. No muestres los ID de otra forma.
- Al final de CADA respuesta añade una última línea, exactamente con este formato, con 3 preguntas cortas (máximo 9 palabras cada una) que el usuario podría hacerte a continuación, relacionadas con lo que acaba de preguntar (el mismo partido, sus equipos, su clasificación, otros partidos de ese día o de la jornada) y que se puedan responder con la GUÍA:
SUGERENCIAS: pregunta 1 | pregunta 2 | pregunta 3
- Si la GUÍA empieza con «PARTIDO SELECCIONADO», el usuario está mirando ese partido: las preguntas sin más detalle («¿cómo quedó la ida?», «¿quién gana más?», «estadísticas») se refieren a él. Usa el HISTORIAL, los ÚLTIMOS ENFRENTAMIENTOS y la FORMA que vienen debajo; cita fecha y temporada de los partidos que menciones. No inventes datos que no estén (por ejemplo, goleadores o posesión).
- Si te preguntan algo que no tiene que ver con el fútbol o con la guía, responde amablemente que solo puedes ayudar con los partidos, canales, resultados y clasificaciones de la guía.

GUÍA (puede empezar con el PARTIDO SELECCIONADO y su historial; después, una línea por partido: ID | competición | fecha y hora | local – visitante | canales | estado)
${contexto}`;

export default {
  /** Cada hora (wrangler.jsonc → triggers.crons): pide a GitHub que pase el robot de datos.
   *  Necesita el secreto GH_TOKEN (llave de GitHub con permiso «Contents: Read and write» sobre dondeloechan). */
  async scheduled(evento, env, ctx) {
    if (!env.GH_TOKEN) { console.log("Falta el secreto GH_TOKEN: no se puede avisar al robot."); return; }
    const r = await fetch("https://api.github.com/repos/alexample/dondeloechan/dispatches", {
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
    console.log("Aviso al robot de GitHub:", r.status, r.status === 204 ? "OK" : await r.text());
  },

  async fetch(request, env, ctx) {
    const origen = request.headers.get("Origin") || "";
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(origen) });
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
