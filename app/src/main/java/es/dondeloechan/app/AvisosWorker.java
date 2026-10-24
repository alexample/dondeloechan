package es.dondeloechan.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Revisa la guía en segundo plano y avisa cuando se confirma (o cambia) el canal
 * de un partido de los equipos favoritos.
 *
 * Estado guardado por partido en "avisos": "P|firma" (provisional o sin canal) o "C|firma" (confirmado).
 * Solo se avisa en el paso P → C o si cambia la firma de un partido ya confirmado.
 */
public class AvisosWorker extends Worker {

    static final String PREFS = "avisos";
    static final String CANAL_AVISOS = "canales_confirmados";
    static final String CANAL_PREVIA = "recordatorio_15min";
    static final String CANAL_VUELTA = "vuelve_el_futbol";
    static final int MIN_DEF = 15;
    static final String URL_GUIA = "https://raw.githubusercontent.com/Alexample/dondeloechan/main/data/partidos.json";
    private static final String TRABAJO = "revisar-canales";

    public AvisosWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    /** Revisión periódica cada hora (Android puede retrasarla un poco para ahorrar batería). */
    static void programar(Context c) {
        Constraints red = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(AvisosWorker.class, 1, TimeUnit.HOURS)
                .setConstraints(red).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(TRABAJO, ExistingPeriodicWorkPolicy.UPDATE, req);
    }

    /** Revisión inmediata (al abrir la app o al cambiar de favoritos). */
    static void revisarYa(Context c) {
        Constraints red = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(AvisosWorker.class).setConstraints(red).build();
        WorkManager.getInstance(c).enqueueUniqueWork(TRABAJO + "-ya", ExistingWorkPolicy.REPLACE, req);
    }

    static void crearCanal(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CANAL_AVISOS, "Canal confirmado de mis equipos",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Aviso cuando se confirma por qué canal se emite un partido de tus equipos.");
            NotificationChannel pr = new NotificationChannel(CANAL_PREVIA, "Antes del partido",
                    NotificationManager.IMPORTANCE_HIGH);
            pr.setDescription("Recordatorio antes de que empiece un partido de tus equipos, con su canal (los minutos se eligen en el menú de la app).");
            NotificationChannel vu = new NotificationChannel(CANAL_VUELTA, "Vuelve el fútbol",
                    NotificationManager.IMPORTANCE_HIGH);
            vu.setDescription("Aviso antes del primer partido después de unas horas sin fútbol (se configura en el menú de la app).");
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) { nm.createNotificationChannel(ch); nm.createNotificationChannel(pr); nm.createNotificationChannel(vu); }
            Seguimiento.crearCanal(c);
        }
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> favs = new HashSet<>();
        try {
            JSONArray a = new JSONArray(p.getString("favs", "[]"));
            for (int i = 0; i < a.length(); i++) favs.add(a.getString(i));
        } catch (Exception ignored) { }
        Set<String> favsAntes = new HashSet<>();
        try {
            JSONArray a = new JSONArray(p.getString("favs_prev", "[]"));
            for (int i = 0; i < a.length(); i++) favsAntes.add(a.getString(i));
        } catch (Exception ignored) { }
        Set<String> alarmasAntes = new HashSet<>(p.getStringSet("alarmas", new HashSet<>()));

        // Ajustes del menú de la app.
        JSONObject aj;
        try { aj = new JSONObject(p.getString("ajustes", "{}")); } catch (Exception e) { aj = new JSONObject(); }
        boolean avisoCanal = aj.optBoolean("canal", true);
        boolean avisoCambio = aj.optBoolean("cambio", true);
        boolean avisoAntes = aj.optBoolean("antes", true);
        boolean seguirDirecto = !"no".equals(aj.optString("goles", "mios")) || aj.optBoolean("final", true);
        Set<String> segAntes = new HashSet<>(p.getStringSet("alarmas_seg", new HashSet<>()));
        Set<String> seg = new HashSet<>();
        int minAntes = aj.optInt("min", MIN_DEF);
        if (minAntes < 1 || minAntes > 180) minAntes = MIN_DEF;
        final long ANTES = minAntes * 60 * 1000L;

        JSONObject catalogo;
        try { catalogo = new JSONObject(p.getString("canales", "{}")); } catch (Exception e) { catalogo = new JSONObject(); }

        String cuerpo;
        try { cuerpo = descargar(URL_GUIA); } catch (Exception e) { return Result.retry(); }

        JSONArray partidos;
        try { partidos = new JSONObject(cuerpo).getJSONArray("partidos"); } catch (Exception e) { return Result.success(); }

        long ahora = System.currentTimeMillis();
        crearCanal(c);
        revisarVuelta(c, p, aj, partidos, catalogo, ahora);
        SharedPreferences.Editor ed = p.edit();
        int avisados = 0;
        Set<String> alarmas = new HashSet<>();

        for (int i = 0; i < partidos.length(); i++) {
            JSONObject m = partidos.optJSONObject(i);
            if (m == null) continue;
            String local = m.optString("localId"), visit = m.optString("visitanteId");
            if (!favs.contains(local) && !favs.contains(visit)) continue;
            long inicio = fecha(m.optString("inicio"));
            String estado = m.optString("estado", "");
            boolean anulado = estado.equals("aplazado") || estado.equals("suspendido") || estado.equals("final");
            // Partido ya en juego con su cadena de avisos de gol en marcha: no se toca.
            if (inicio <= ahora) {
                if (seguirDirecto && !anulado && ahora < inicio + Seguimiento.MAX_DURACION && segAntes.contains("seg_" + m.optString("id")))
                    seg.add("seg_" + m.optString("id"));
                continue;
            }

            JSONArray canales = m.optJSONArray("canales");

            // Recordatorio 15 minutos antes (si la hora está fijada y no se ha avisado ya para esta hora).
            String mid = m.optString("id");
            // A la hora de inicio arranca el seguimiento en directo (avisos de gol y de resultado final).
            if (seguirDirecto && !anulado && !m.optBoolean("horaPendiente", false)) {
                if (!segAntes.contains("seg_" + mid)) {
                    SimpleDateFormat hf = new SimpleDateFormat("dd/MM HH:mm", Locale.ROOT);
                    hf.setTimeZone(TimeZone.getTimeZone("Europe/Madrid"));
                    Seguimiento.diag(c, "Avisos de gol preparados: " + m.optString("local") + " – " + m.optString("visitante") + " (" + hf.format(new Date(inicio)) + ")");
                }
                programarSeguimiento(c, m, inicio);
                seg.add("seg_" + mid);
            }
            if (avisoAntes && !anulado && !m.optBoolean("horaPendiente", false) && inicio - ANTES > ahora - 60000
                    && p.getLong("rec_" + mid, 0) != inicio) {
                programarRecordatorio(c, m, canales, catalogo, inicio, ANTES);
                alarmas.add(mid);
            }
            if (anulado) continue;
            boolean confirmado = !m.optBoolean("provisional", false) && canales != null && canales.length() > 0;
            String firma = canales == null ? "" : canales.toString();
            String clave = "st_" + m.optString("id");
            String antes = p.getString(clave, null);
            String ahoraEstado = (confirmado ? "C|" : "P|") + firma;

            // Avisa al confirmarse el canal (o si cambia). Un partido nuevo que ya llega confirmado
            // también avisa, salvo que el equipo se acabe de añadir a favoritos (para no llenar de avisos).
            boolean equipoYaSeguido = favsAntes.contains(local) || favsAntes.contains(visit);
            boolean pasoAConfirmado = antes != null ? antes.startsWith("P|") : equipoYaSeguido;
            boolean cambio = antes != null && antes.startsWith("C|") && !antes.equals(ahoraEstado);
            boolean avisar = avisoCanal && confirmado && (pasoAConfirmado || (avisoCambio && cambio));
            if (avisar && notificar(c, m, canales, catalogo, inicio)) avisados++;
            ed.putString(clave, ahoraEstado);
        }
        for (String id : alarmasAntes) if (!alarmas.contains(id)) cancelarRecordatorio(c, id);
        for (String id : segAntes) if (!seg.contains(id)) cancelarRecordatorio(c, id);
        ed.putStringSet("alarmas_seg", seg);
        ed.putStringSet("alarmas", alarmas);
        ed.putString("favs_prev", new JSONArray(favs).toString());
        ed.apply();
        return Result.success();
    }

    /**
     * «Vuelve el fútbol»: tras X horas sin ningún partido (de las competiciones elegidas), avisa N minutos antes
     * del primero que se juegue, con su canal. Si empiezan varios a la vez (±30 min), van en el mismo aviso.
     */
    static void revisarVuelta(Context c, SharedPreferences p, JSONObject aj, JSONArray partidos, JSONObject catalogo, long ahora) {
        Set<String> antes = new HashSet<>(p.getStringSet("alarmas_vuelta", new HashSet<>()));
        Set<String> nuevas = new HashSet<>();
        if (aj.optBoolean("vuelta", false)) {
            int horas = aj.optInt("vhoras", 12);
            if (horas < 2 || horas > 168) horas = 12;
            int min = aj.optInt("vmin", MIN_DEF);
            if (min < 1 || min > 180) min = MIN_DEF;
            Set<Integer> ligas = new HashSet<>();
            JSONArray vl = aj.optJSONArray("vligas");
            if (vl != null) for (int i = 0; i < vl.length(); i++) ligas.add(vl.optInt(i));
            final long DURA = 2 * 3600 * 1000L, PARON = horas * 3600 * 1000L, ANTES = min * 60 * 1000L, JUNTOS = 30 * 60 * 1000L;
            java.util.List<JSONObject> lista = new java.util.ArrayList<>();
            for (int i = 0; i < partidos.length(); i++) {
                JSONObject m = partidos.optJSONObject(i);
                if (m == null) continue;
                if (!ligas.isEmpty() && !ligas.contains(m.optInt("liga"))) continue;
                String est = m.optString("estado", "");
                if (est.equals("aplazado") || est.equals("suspendido") || m.optBoolean("horaPendiente", false)) continue;
                long t = fecha(m.optString("inicio"));
                if (t == 0) continue;
                try { m.put("_t", t); } catch (Exception ignored) { }
                lista.add(m);
            }
            java.util.Collections.sort(lista, (a, b) -> Long.compare(a.optLong("_t"), b.optLong("_t")));
            long finPrevio = 0;   // hora en que acaba (aprox.) el último partido anterior
            for (int i = 0; i < lista.size(); i++) {
                long t = lista.get(i).optLong("_t");
                boolean paron = finPrevio > 0 && t - finPrevio >= PARON;
                if (paron && t - ANTES > ahora - 60000) {
                    java.util.List<JSONObject> grupo = new java.util.ArrayList<>();
                    for (int j = i; j < lista.size() && lista.get(j).optLong("_t") - t <= JUNTOS; j++) grupo.add(lista.get(j));
                    String id = "vuelta_" + t;
                    if (p.getLong("rec_" + id, 0) != t) {
                        programarVuelta(c, id, grupo, catalogo, t, ANTES, horas);
                    }
                    nuevas.add(id);
                }
                finPrevio = Math.max(finPrevio, t + DURA);
            }
        }
        for (String id : antes) if (!nuevas.contains(id)) cancelarRecordatorio(c, id);
        p.edit().putStringSet("alarmas_vuelta", nuevas).apply();
    }

    private static void programarVuelta(Context c, String id, java.util.List<JSONObject> grupo, JSONObject catalogo, long inicio, long ANTES, int horas) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null || grupo.isEmpty()) return;
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Europe/Madrid"));
        StringBuilder cuerpo = new StringBuilder();
        int n = 0;
        for (JSONObject m : grupo) {
            if (n == 4) { cuerpo.append("\n…y ").append(grupo.size() - 4).append(" más"); break; }
            JSONArray canales = m.optJSONArray("canales");
            String canal = m.optBoolean("sinTV", false) ? "sin TV" : canales == null || canales.length() == 0 ? "canal por confirmar"
                    : describirCanal(canales.opt(0), catalogo);
            if (n > 0) cuerpo.append("\n");
            cuerpo.append(f.format(new Date(m.optLong("_t")))).append(" · ").append(m.optString("local")).append(" – ")
                    .append(m.optString("visitante")).append(" · ").append(canal);
            n++;
        }
        Intent datos = new Intent()
                .putExtra("id", id)
                .putExtra("tipo", "vuelta")
                .putExtra("partido", grupo.get(0).optString("id"))
                .putExtra("titulo", grupo.size() == 1 ? grupo.get(0).optString("local") + " – " + grupo.get(0).optString("visitante")
                        : grupo.size() + " partidos")
                .putExtra("cuerpo", cuerpo.toString())
                .putExtra("paron", horas)
                .putExtra("hora", f.format(new Date(inicio)))
                .putExtra("inicio", inicio);
        PendingIntent pi = intentoRecordatorio(c, id, datos, PendingIntent.FLAG_UPDATE_CURRENT);
        long cuando = Math.max(inicio - ANTES, System.currentTimeMillis() + 1000);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
            else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, cuando, pi);
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, cuando, pi);
        }
    }

    /** A la hora de inicio de un partido de tus equipos empieza la cadena de consultas (avisos de gol y final). */
    private static void programarSeguimiento(Context c, JSONObject m, long inicio) {
        JSONObject datos = new JSONObject();
        try {
            for (String k : new String[]{"id", "local", "visitante", "localId", "visitanteId"}) datos.put(k, m.optString(k));
            datos.put("liga", m.optInt("liga"));
            datos.put("inicio", inicio);
        } catch (Exception ignored) { }
        Seguimiento.programar(c, datos, inicio - 60000);
    }

    static void cancelarVuelta(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String id : p.getStringSet("alarmas_vuelta", new HashSet<>())) cancelarRecordatorio(c, id);
        p.edit().putStringSet("alarmas_vuelta", new HashSet<>()).apply();
    }

    private static PendingIntent intentoRecordatorio(Context c, String id, Intent datos, int flags) {
        Intent i = datos != null ? datos : new Intent(c, Recordatorio.class);
        i.setClass(c, Recordatorio.class).setAction("es.dondeloechan.app.RECORDATORIO").setData(
                android.net.Uri.parse("dondeloechan://recordatorio/" + id));
        return PendingIntent.getBroadcast(c, id.hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Programa (o reprograma, si cambió la hora o el canal) la alarma de 15 minutos antes. */
    private static void programarRecordatorio(Context c, JSONObject m, JSONArray canales, JSONObject catalogo, long inicio, long ANTES) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        String canal;
        if (m.optBoolean("sinTV", false)) canal = "Sin emisión por TV";
        else if (canales == null || canales.length() == 0) canal = "Canal aún sin confirmar";
        else {
            canal = describirCanal(canales.opt(0), catalogo);
            if (canales.length() > 1) canal += " · también " + nombreCanal(canales.opt(1), catalogo);
        }
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("Europe/Madrid"));
        Intent datos = new Intent()
                .putExtra("id", m.optString("id"))
                .putExtra("titulo", m.optString("local") + " – " + m.optString("visitante"))
                .putExtra("canal", canal)
                .putExtra("hora", f.format(new Date(inicio)))
                .putExtra("inicio", inicio);
        PendingIntent pi = intentoRecordatorio(c, m.optString("id"), datos, PendingIntent.FLAG_UPDATE_CURRENT);
        long cuando = Math.max(inicio - ANTES, System.currentTimeMillis() + 1000);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cuando, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, cuando, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, cuando, pi);
        }
    }

    /** Al desactivar el aviso previo en el menú: quita ya todas las alarmas programadas, aunque no haya red. */
    static void cancelarTodos(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (String id : p.getStringSet("alarmas", new HashSet<>())) cancelarRecordatorio(c, id);
        p.edit().putStringSet("alarmas", new HashSet<>()).apply();
    }

    private static void cancelarRecordatorio(Context c, String id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = intentoRecordatorio(c, id, null, PendingIntent.FLAG_NO_CREATE);
        if (am != null && pi != null) { am.cancel(pi); pi.cancel(); }
    }

    static boolean puedeNotificar(Context c) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return NotificationManagerCompat.from(c).areNotificationsEnabled();
    }

    private boolean notificar(Context c, JSONObject m, JSONArray canales, JSONObject catalogo, long inicio) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (!NotificationManagerCompat.from(c).areNotificationsEnabled()) return false;

        String titulo = "📺 Canal confirmado: " + m.optString("local") + " – " + m.optString("visitante");
        String canal = describirCanal(canales.opt(0), catalogo);
        if (canales.length() > 1) canal += " · también " + nombreCanal(canales.opt(1), catalogo);
        SimpleDateFormat f = new SimpleDateFormat("EEE d MMM, HH:mm", new Locale("es", "ES"));
        f.setTimeZone(TimeZone.getTimeZone("Europe/Madrid"));
        String cuando = f.format(new Date(inicio)) + " h";

        Intent abrir = new Intent(c, MainActivity.class)
                .putExtra("partido", m.optString("id"))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int id = m.optString("id").hashCode();
        PendingIntent pi = PendingIntent.getActivity(c, id, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder n = new NotificationCompat.Builder(c, CANAL_AVISOS)
                .setSmallIcon(R.drawable.ic_aviso)
                .setColor(0xFF1F5E3B)
                .setContentTitle(titulo)
                .setContentText(canal + " · " + cuando)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(canal + "\n" + cuando))
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(c).notify(id, n.build());
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    static String nombreCanal(Object canal, JSONObject catalogo) {
        if (canal instanceof JSONObject) return ((JSONObject) canal).optString("name", "TV");
        JSONObject k = catalogo.optJSONObject(String.valueOf(canal));
        return k != null ? k.optString("name", String.valueOf(canal)) : String.valueOf(canal);
    }

    static String describirCanal(Object canal, JSONObject catalogo) {
        JSONObject k = canal instanceof JSONObject ? (JSONObject) canal : catalogo.optJSONObject(String.valueOf(canal));
        String nombre = nombreCanal(canal, catalogo);
        if (k == null) return nombre;
        String dial = k.optString("dial", "");
        String orange = k.optString("orange", "");
        if (!dial.isEmpty() && !"TDT".equals(dial)) return nombre + " (dial " + dial + ")";
        if (!orange.isEmpty()) return nombre + " (Orange " + orange + ")";
        return nombre;
    }

    private static long fecha(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT);
            return f.parse(iso).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String descargar(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url + "?t=" + System.currentTimeMillis()).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
