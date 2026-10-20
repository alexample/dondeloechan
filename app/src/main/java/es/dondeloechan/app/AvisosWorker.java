package es.dondeloechan.app;

import android.Manifest;
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
    static final String URL_GUIA = "https://raw.githubusercontent.com/Alexample/dondeloechan/main/data/partidos.json";
    private static final String TRABAJO = "revisar-canales";

    public AvisosWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    /** Revisión periódica cada 3 horas (Android puede retrasarla un poco para ahorrar batería). */
    static void programar(Context c) {
        Constraints red = new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(AvisosWorker.class, 3, TimeUnit.HOURS)
                .setConstraints(red).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(TRABAJO, ExistingPeriodicWorkPolicy.KEEP, req);
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
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
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
        if (favs.isEmpty()) return Result.success();

        JSONObject catalogo;
        try { catalogo = new JSONObject(p.getString("canales", "{}")); } catch (Exception e) { catalogo = new JSONObject(); }

        String cuerpo;
        try { cuerpo = descargar(URL_GUIA); } catch (Exception e) { return Result.retry(); }

        JSONArray partidos;
        try { partidos = new JSONObject(cuerpo).getJSONArray("partidos"); } catch (Exception e) { return Result.success(); }

        long ahora = System.currentTimeMillis();
        SharedPreferences.Editor ed = p.edit();
        crearCanal(c);
        int avisados = 0;

        for (int i = 0; i < partidos.length(); i++) {
            JSONObject m = partidos.optJSONObject(i);
            if (m == null) continue;
            String local = m.optString("localId"), visit = m.optString("visitanteId");
            if (!favs.contains(local) && !favs.contains(visit)) continue;
            long inicio = fecha(m.optString("inicio"));
            if (inicio <= ahora) continue;

            JSONArray canales = m.optJSONArray("canales");
            boolean confirmado = !m.optBoolean("provisional", false) && canales != null && canales.length() > 0;
            String firma = canales == null ? "" : canales.toString();
            String clave = "st_" + m.optString("id");
            String antes = p.getString(clave, null);
            String ahoraEstado = (confirmado ? "C|" : "P|") + firma;

            boolean avisar = confirmado && antes != null
                    && (antes.startsWith("P|") || !antes.equals(ahoraEstado));
            if (avisar && notificar(c, m, canales, catalogo, inicio)) avisados++;
            ed.putString(clave, ahoraEstado);
        }
        ed.apply();
        return Result.success();
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

    private static String nombreCanal(Object canal, JSONObject catalogo) {
        if (canal instanceof JSONObject) return ((JSONObject) canal).optString("name", "TV");
        JSONObject k = catalogo.optJSONObject(String.valueOf(canal));
        return k != null ? k.optString("name", String.valueOf(canal)) : String.valueOf(canal);
    }

    private static String describirCanal(Object canal, JSONObject catalogo) {
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
