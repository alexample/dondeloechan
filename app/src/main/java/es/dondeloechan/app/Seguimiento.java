package es.dondeloechan.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Avisos de gol y de resultado final de los partidos de tus equipos, con la app cerrada y SIN notificación fija:
 * una cadena de alarmas exactas (una por minuto mientras dura el partido). En cada una se consulta el marcador
 * (worker → ESPN o FotMob), se avisa de los goles nuevos y se programa la siguiente.
 * Si el móvil está en reposo profundo, Android puede espaciar las alarmas (hasta ~9 min); al encender la
 * pantalla se ponen al día.
 */
public final class Seguimiento {

    static final String CANAL_GOLES = "goles_y_final";
    static final String API = "https://dondeloechan-ia.alexample.workers.dev/partido";
    static final long CADA = 60 * 1000L;
    static final long MAX_DURACION = 3L * 3600 * 1000 + 30 * 60 * 1000;

    private Seguimiento() { }

    static void crearCanal(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.deleteNotificationChannel("partido_en_directo");
            nm.deleteNotificationChannel("seguimiento_silencioso");
            NotificationChannel g = new NotificationChannel(CANAL_GOLES, "Goles y resultado final", NotificationManager.IMPORTANCE_HIGH);
            g.setDescription("Aviso de cada gol y del resultado final de los partidos de tus equipos.");
            nm.createNotificationChannel(g);
        }
    }

    /** Programa la próxima consulta del partido (o la primera, si «en» es 0 = ya). */
    static void programar(Context c, JSONObject partido, long en) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        String id = "seg_" + partido.optString("id");
        Intent in = new Intent(c, Recordatorio.class)
                .setAction("es.dondeloechan.app.RECORDATORIO")
                .setData(android.net.Uri.parse("dondeloechan://recordatorio/" + id))
                .putExtra("id", id).putExtra("tipo", "seguir")
                .putExtra("partido", partido.toString()).putExtra("inicio", partido.optLong("inicio"));
        PendingIntent pi = PendingIntent.getBroadcast(c, id.hashCode(), in, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        long cuando = Math.max(en, System.currentTimeMillis() + 1000);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
            else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, cuando, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, cuando, pi);
        }
    }

    /** Una consulta (la llama Recordatorio en segundo plano). Devuelve true si hay que seguir. */
    static boolean consultarYAvisar(Context c, JSONObject m) { return consultarYAvisar(c, m, false); }

    /** prueba = true: «Probar con un partido real» del menú (avisa de todos sus goles desde 0–0, sin límite de tiempo). */
    static boolean consultarYAvisar(Context c, JSONObject m, boolean prueba) {
        SharedPreferences p = c.getSharedPreferences(AvisosWorker.PREFS, Context.MODE_PRIVATE);
        String id = m.optString("id");
        long inicio = m.optLong("inicio");
        long ahora = System.currentTimeMillis();
        if (!prueba && ahora > inicio + MAX_DURACION) { diag(c, "Fin del seguimiento (tiempo): " + titulo(m)); return false; }
        JSONObject aj;
        try { aj = new JSONObject(p.getString("ajustes", "{}")); } catch (Exception e) { aj = new JSONObject(); }
        // Partido elegido con 🔔: avisa de los goles de los dos equipos y del final.
        boolean elegido = AvisosWorker.leerSet(p, "partidos").contains(id);
        String modoGoles = prueba || elegido ? "todos" : aj.optString("goles", "mios");
        boolean avisoFinal = prueba || elegido || aj.optBoolean("final", true);
        if ("no".equals(modoGoles) && !avisoFinal) return false;

        JSONObject r;
        try { r = consultar(m); } catch (Exception e) { diag(c, "Sin conexión al consultar " + titulo(m) + ": " + e.getClass().getSimpleName()); return true; }
        if (r == null || !r.optBoolean("encontrado")) { diag(c, "Aún sin datos de " + titulo(m)); return true; }
        if (prueba) diag(c, "Prueba: el servidor responde " + titulo(m) + " " + r.optInt("gl") + "–" + r.optInt("gv") + " (" + r.optString("estado") + ")");
        String estado = r.optString("estado");
        if ("pre".equals(estado)) return true;

        Set<String> favs = new HashSet<>();
        try {
            JSONArray a = new JSONArray(p.getString("favs", "[]"));
            for (int i = 0; i < a.length(); i++) favs.add(a.getString(i));
        } catch (Exception ignored) { }

        int gl = r.optInt("gl", 0), gv = r.optInt("gv", 0);
        JSONArray ev = r.optJSONArray("eventos");
        List<JSONObject> goles = new ArrayList<>();
        if (ev != null) for (int i = 0; i < ev.length(); i++) {
            JSONObject e = ev.optJSONObject(i);
            if (e != null && "g".equals(e.optString("t"))) goles.add(e);
        }
        String local = m.optString("local"), visit = m.optString("visitante");
        String marcador = local + " " + gl + "–" + gv + " " + visit;

        // Estado guardado: goles ya avisados de cada lado.
        String clave = (prueba ? "prueba_" : "vivo_") + id;
        if (prueba) p.edit().remove(clave).remove("fin_prueba_" + id).apply();
        int yaL = -1, yaV = -1;
        try { JSONObject st = new JSONObject(p.getString(clave, "{}")); yaL = st.optInt("gl", -1); yaV = st.optInt("gv", -1); } catch (Exception ignored) { }
        if (yaL < 0) {
            // Primera lectura. Si el partido acaba de empezar (≤ 20 min), se avisa también de los goles que ya hubiera.
            boolean reciente = prueba || ahora - inicio < 20 * 60 * 1000L;
            yaL = reciente ? 0 : gl;
            yaV = reciente ? 0 : gv;
            diag(c, "Siguiendo " + titulo(m) + " (" + gl + "–" + gv + ")");
        }
        int nuevosL = Math.max(0, gl - yaL), nuevosV = Math.max(0, gv - yaV);
        if (nuevosL + nuevosV > 0) {
            // Goleadores: los últimos de cada lado (ESPN/FotMob los ordenan por minuto).
            List<JSONObject> deL = new ArrayList<>(), deV = new ArrayList<>();
            for (JSONObject e : goles) ("L".equals(e.optString("lado")) ? deL : deV).add(e);
            for (int k = 0; k < nuevosL + nuevosV; k++) {
                boolean esL = k < nuevosL;
                int n = esL ? yaL + k + 1 : yaV + (k - nuevosL) + 1;          // nº de gol de ese equipo
                List<JSONObject> lista = esL ? deL : deV;
                JSONObject e = n - 1 < lista.size() ? lista.get(n - 1) : null;
                String eqId = esL ? m.optString("localId") : m.optString("visitanteId");
                boolean mio = favs.contains(eqId);
                String quien = esL ? local : visit;
                if ("todos".equals(modoGoles) || ("mios".equals(modoGoles) && mio)) {
                    String det = e == null ? "" : (e.optString("min") + " " + e.optString("j")
                            + (e.optBoolean("pen") ? " (penalti)" : "") + (e.optBoolean("pp") ? " (en propia puerta)" : "")).trim();
                    avisar(c, (prueba ? "p_" : "") + id + "_" + (esL ? "L" : "V") + n, "⚽ ¡Gol " + (mio ? "de tu " : "del ") + quien + "!",
                            marcador + (det.isEmpty() ? "" : "\n" + det), id);
                    diag(c, "Aviso de gol: " + quien + " (" + marcador + ")");
                } else {
                    diag(c, "Gol de " + quien + " sin aviso (no es de tus equipos)");
                }
            }
        }
        try { p.edit().putString(clave, new JSONObject().put("gl", Math.max(gl, yaL)).put("gv", Math.max(gv, yaV)).toString()).apply(); } catch (Exception ignored) { }

        if ("post".equals(estado)) {
            if (avisoFinal && !p.getBoolean((prueba ? "fin_prueba_" : "fin_") + id, false) && !r.optString("detalle").toLowerCase(Locale.ROOT).contains("aplaz")) {
                StringBuilder g = new StringBuilder();
                for (JSONObject e : goles) {
                    if (g.length() > 0) g.append("\n");
                    g.append("L".equals(e.optString("lado")) ? local : visit).append(": ").append(e.optString("j")).append(" ").append(e.optString("min"));
                }
                avisar(c, (prueba ? "p_" : "") + id + "_final", "🏁 Final: " + marcador, g.length() > 0 ? g.toString() : marcador, id);
                if (!prueba) p.edit().putBoolean("fin_" + id, true).apply();
                diag(c, "Aviso de final: " + marcador);
            }
            return false;
        }
        return true;
    }

    private static String titulo(JSONObject m) { return m.optString("local") + " – " + m.optString("visitante"); }

    private static JSONObject consultar(JSONObject m) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Europe/Madrid"));
        String url = API + "?liga=" + m.optInt("liga") + "&fecha=" + f.format(new Date(m.optLong("inicio")))
                + "&local=" + URLEncoder.encode(m.optString("local"), "UTF-8")
                + "&visitante=" + URLEncoder.encode(m.optString("visitante"), "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(7000);
        c.setUseCaches(false);
        c.setRequestProperty("X-App", "dondeloechan");
        try {
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    static void avisar(Context c, String clave, String titulo, String texto, String partido) {
        crearCanal(c);
        if (!AvisosWorker.puedeNotificar(c)) { diag(c, "No se puede notificar: notificaciones desactivadas"); return; }
        Intent abrir = new Intent(c, MainActivity.class).putExtra("partido", partido)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int id = clave.hashCode();
        PendingIntent pi = PendingIntent.getActivity(c, id, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String primera = texto.contains("\n") ? texto.substring(0, texto.indexOf('\n')) : texto;
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CANAL_GOLES)
                .setSmallIcon(R.drawable.ic_aviso)
                .setColor(0xFF1F5E3B)
                .setContentTitle(titulo)
                .setContentText(primera)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setContentIntent(pi)
                .setAutoCancel(true);
        try { NotificationManagerCompat.from(c).notify(id, b.build()); } catch (SecurityException ignored) { }
    }

    /** Registro breve para el menú «Comprobar avisos» (últimas 25 líneas). */
    static synchronized void diag(Context c, String texto) {
        SharedPreferences p = c.getSharedPreferences(AvisosWorker.PREFS, Context.MODE_PRIVATE);
        JSONArray a;
        try { a = new JSONArray(p.getString("diag", "[]")); } catch (Exception e) { a = new JSONArray(); }
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Europe/Madrid"));
        a.put(f.format(new Date()) + " · " + texto);
        JSONArray b = new JSONArray();
        for (int i = Math.max(0, a.length() - 25); i < a.length(); i++) b.put(a.opt(i));
        p.edit().putString("diag", b.toString()).apply();
    }
}
