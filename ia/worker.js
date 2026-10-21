/**
 * Asistente de «Dónde lo Echan» — Cloudflare Worker con Workers AI (modelos abiertos).
 *
 * Instalación (panel de Cloudflare):
 *   1. Workers & Pages → Create → Worker → Deploy (el «Hello World» de ejemplo).
 *   2. Edit code → borra todo, pega este archivo → Deploy.
 *   3. Settings → Bindings → Add → Workers AI → nombre de la variable: AI → Deploy.
 *
 * La app y la web envían: {pregunta, historial:[{rol,texto}], contexto:"…guía en texto…", hoy:"…"}
 * El worker responde: {respuesta:"…"}. No guarda nada.
 */

const MODELOS = ["@cf/meta/llama-3.3-70b-instruct-fp8-fast", "@cf/meta/llama-3.1-8b-instruct-fast"];
const ORIGENES = ["https://alexample.github.io", "https://appassets.androidplatform.net"];
const MAX_PREGUNTA = 500;
const MAX_CONTEXTO = 60000;
const LIMITE_MIN = 6;      // preguntas por minuto y persona (IP)
const LIMITE_DIA = 60;     // preguntas por día y persona (IP)

const uso = new Map();     // ip -> {min, tMin, dia, tDia}  (memoria del worker: freno básico contra abusos)

function cors(origen) {
  const ok = ORIGENES.includes(origen);
  return {
    "Access-Control-Allow-Origin": ok ? origen : ORIGENES[0],
    "Access-Control-Allow-Methods": "POST, OPTIONS",
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
- Si te preguntan algo que no tiene que ver con el fútbol o con la guía, responde amablemente que solo puedes ayudar con los partidos, canales, resultados y clasificaciones de la guía.

GUÍA (formato por línea: ID | competición | fecha y hora | local – visitante | canales | estado)
${contexto}`;

export default {
  async fetch(request, env) {
    const origen = request.headers.get("Origin") || "";
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(origen) });
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
