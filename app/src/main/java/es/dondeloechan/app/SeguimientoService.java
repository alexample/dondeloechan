package es.dondeloechan.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sigue en directo los partidos de tus equipos mientras se juegan (servicio en primer plano con una
 * notificación discreta del marcador) y avisa de cada gol y del resultado final, según los ajustes del menú.
 * Lo arranca la alarma programada a la hora de inicio (o la app, si está abierta durante el partido).
 */
public class SeguimientoService extends Service {

    // Notificación obligatoria del servicio: mínima y sin marcadores (los avisos útiles son los de cada gol).
    static final String CANAL_DIRECTO = "seguimiento_silencioso";
    static final String CANAL_GOLES = "goles_y_final";
    static final String API = "https://dondeloechan-ia.alexample.workers.dev/partido";
    private static final int ID_FIJA = 7301;
    private static final long CADA = 40;                         // segundos entre consultas
    private static final long MAX_DURACION = 3L * 3600 * 1000 + 30 * 60 * 1000;

    private static class Seguido {
        JSONObject m;
        int gl = -1, gv = -1, goles = 0;
        long inicio;
        String linea = "";
    }

    private final Map<String, Seguido> seguidos = new java.util.concurrent.ConcurrentHashMap<>();
    private final android.os.Handler principal = new android.os.Handler(android.os.Looper.getMainLooper());
    private ScheduledExecutorService reloj;

    /** Arranca (o suma un partido a) el seguimiento. */
    static void seguir(Context c, JSONObject partido) {
        Intent i = new Intent(c, SeguimientoService.class).putExtra("partido", partido.toString());
        try { ContextCompat.startForegroundService(c, i); } catch (Exception ignored) { }
    }

    static void crearCanales(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.deleteNotificationChannel("partido_en_directo");   // la de antes, con marcadores
            NotificationChannel d = new NotificationChannel(CANAL_DIRECTO, "Seguimiento de goles (silencioso)", NotificationManager.IMPORTANCE_MIN);
            d.setDescription("Android exige una notificación mientras la app vigila los partidos de tus equipos para avisarte de los goles. Va plegada abajo, sin sonido ni icono.");
            d.setShowBadge(false);
            NotificationChannel g = new NotificationChannel(CANAL_GOLES, "Goles y resultado final", NotificationManager.IMPORTANCE_HIGH);
            g.setDescription("Aviso de cada gol y del resultado final de los partidos de tus equipos.");
            nm.createNotificationChannel(d);
            nm.createNotificationChannel(g);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanales(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            ServiceCompat.startForeground(this, ID_FIJA, fija(null),
                    Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String json = intent != null ? intent.getStringExtra("partido") : null;
        if (json != null) {
            try {
                JSONObject m = new JSONObject(json);
                String id = m.optString("id");
                if (!id.isEmpty() && !seguidos.containsKey(id)) {
                    Seguido s = new Seguido();
                    s.m = m;
                    s.inicio = m.optLong("inicio");
                    s.linea = m.optString("local") + " – " + m.optString("visitante");
                    seguidos.put(id, s);
                }
            } catch (Exception ignored) { }
        }
        if (seguidos.isEmpty()) { parar(); return START_NOT_STICKY; }
        if (reloj == null) {
            reloj = Executors.newSingleThreadScheduledExecutor();
            reloj.scheduleWithFixedDelay(this::vuelta, 0, CADA, TimeUnit.SECONDS);
        }
        return START_NOT_STICKY;
    }

    private void vuelta() {
        try {
            SharedPreferences p = getSharedPreferences(AvisosWorker.PREFS, Context.MODE_PRIVATE);
            JSONObject aj;
            try { aj = new JSONObject(p.getString("ajustes", "{}")); } catch (Exception e) { aj = new JSONObject(); }
            String modoGoles = aj.optString("goles", "mios");
            boolean avisoFinal = aj.optBoolean("final", true);
            Set<String> favs = new HashSet<>();
            try {
                JSONArray a = new JSONArray(p.getString("favs", "[]"));
                for (int i = 0; i < a.length(); i++) favs.add(a.getString(i));
            } catch (Exception ignored) { }

            long ahora = System.currentTimeMillis();
            List<String> acabados = new ArrayList<>();
            for (Map.Entry<String, Seguido> en : seguidos.entrySet()) {
                Seguido s = en.getValue();
                if (ahora > s.inicio + MAX_DURACION) { acabados.add(en.getKey()); continue; }
                JSONObject r;
                try { r = consultar(s.m); } catch (Exception e) { continue; }
                if (r == null || !r.optBoolean("encontrado")) continue;
                String estado = r.optString("estado");
                if ("pre".equals(estado)) continue;
                int gl = r.optInt("gl", 0), gv = r.optInt("gv", 0);
                JSONArray ev = r.optJSONArray("eventos");
                List<JSONObject> goles = new ArrayList<>();
                if (ev != null) for (int i = 0; i < ev.length(); i++) {
                    JSONObject e = ev.optJSONObject(i);
                    if (e != null && "g".equals(e.optString("t"))) goles.add(e);
                }
                String local = s.m.optString("local"), visit = s.m.optString("visitante");
                String marcador = local + " " + gl + "–" + gv + " " + visit;

                if (s.gl < 0) {
                    // Primera lectura: punto de partida (si ya iba con goles, no se avisa de los anteriores).
                    s.gl = gl; s.gv = gv; s.goles = goles.size();
                } else if (gl + gv > s.gl + s.gv) {
                    // Goles nuevos: se avisa por cada uno, con el goleador si ESPN/FotMob ya lo ha puesto.
                    int nuevosL = Math.max(0, gl - s.gl), nuevosV = Math.max(0, gv - s.gv);
                    List<JSONObject> conNombre = goles.size() > s.goles ? new ArrayList<>(goles.subList(s.goles, goles.size())) : new ArrayList<>();
                    for (int k = 0; k < nuevosL + nuevosV; k++) {
                        String lado = k < nuevosL ? "L" : "V";
                        JSONObject e = null;
                        for (int q = 0; q < conNombre.size(); q++) if (lado.equals(conNombre.get(q).optString("lado"))) { e = conNombre.remove(q); break; }
                        String eqId = "L".equals(lado) ? s.m.optString("localId") : s.m.optString("visitanteId");
                        boolean mio = favs.contains(eqId);
                        if ("todos".equals(modoGoles) || ("mios".equals(modoGoles) && mio)) {
                            String quien = "L".equals(lado) ? local : visit;
                            String detalle = e == null ? "" : e.optString("min") + " " + e.optString("j")
                                    + (e.optBoolean("pen") ? " (penalti)" : "") + (e.optBoolean("pp") ? " (en propia puerta)" : "");
                            avisar(en.getKey() + "_g" + (s.gl + s.gv + k + 1), "⚽ ¡Gol " + (mio ? "de tu " : "del ") + quien + "!",
                                    marcador + (detalle.trim().isEmpty() ? "" : "\n" + detalle.trim()), s.m.optString("id"));
                        }
                    }
                    s.gl = gl; s.gv = gv; s.goles = goles.size();
                } else {
                    s.gl = gl; s.gv = gv; s.goles = Math.max(s.goles, goles.size());
                }

                if ("post".equals(estado)) {
                    if (avisoFinal && !r.optString("detalle").toLowerCase(Locale.ROOT).contains("aplaz")) {
                        StringBuilder g = new StringBuilder();
                        for (JSONObject e : goles) {
                            if (g.length() > 0) g.append("\n");
                            g.append("L".equals(e.optString("lado")) ? local : visit).append(": ")
                                    .append(e.optString("j")).append(" ").append(e.optString("min"));
                        }
                        avisar(en.getKey() + "_final", "🏁 Final: " + marcador, g.length() > 0 ? g.toString() : marcador, s.m.optString("id"));
                    }
                    acabados.add(en.getKey());
                } else {
                    String min = r.optString("reloj");
                    String det = r.optString("detalle");
                    s.linea = marcador + (det.toLowerCase(Locale.ROOT).contains("descanso") || det.contains("HALFTIME") ? " · Descanso"
                            : min.isEmpty() ? "" : " · " + min + (r.optInt("parte") > 0 && r.optInt("parte") < 3 ? " " + r.optInt("parte") + "ª" : ""));
                }
            }
            for (String id : acabados) seguidos.remove(id);
            if (seguidos.isEmpty()) { principal.post(this::parar); return; }
            NotificationManagerCompat.from(this).notify(ID_FIJA, fija(null));
        } catch (Exception ignored) { }
    }

    private JSONObject consultar(JSONObject m) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Europe/Madrid"));
        String url = API + "?liga=" + m.optInt("liga") + "&fecha=" + f.format(new java.util.Date(m.optLong("inicio")))
                + "&local=" + URLEncoder.encode(m.optString("local"), "UTF-8")
                + "&visitante=" + URLEncoder.encode(m.optString("visitante"), "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
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

    private Notification fija(String ignorado) {
        Intent abrir = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, ID_FIJA, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int n = Math.max(1, seguidos.size());
        return new NotificationCompat.Builder(this, CANAL_DIRECTO)
                .setSmallIcon(R.drawable.ic_aviso)
                .setColor(0xFF1F5E3B)
                .setContentTitle("Avisos de gol activos")
                .setContentText(n == 1 ? "Pendiente del partido de tu equipo" : "Pendiente de " + n + " partidos de tus equipos")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setShowWhen(false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(pi)
                .build();
    }

    private void avisar(String clave, String titulo, String texto, String partido) {
        if (!AvisosWorker.puedeNotificar(this)) return;
        Intent abrir = new Intent(this, MainActivity.class).putExtra("partido", partido)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int id = clave.hashCode();
        PendingIntent pi = PendingIntent.getActivity(this, id, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String primera = texto.contains("\n") ? texto.substring(0, texto.indexOf('\n')) : texto;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CANAL_GOLES)
                .setSmallIcon(R.drawable.ic_aviso)
                .setColor(0xFF1F5E3B)
                .setContentTitle(titulo)
                .setContentText(primera)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(texto))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setContentIntent(pi)
                .setAutoCancel(true);
        try { NotificationManagerCompat.from(this).notify(id, b.build()); } catch (SecurityException ignored) { }
    }

    private void parar() {
        if (reloj != null) { reloj.shutdownNow(); reloj = null; }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (reloj != null) reloj.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
