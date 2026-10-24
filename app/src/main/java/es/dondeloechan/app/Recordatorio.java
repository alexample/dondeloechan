package es.dondeloechan.app;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Salta 15 minutos antes de un partido de tus equipos y muestra el aviso con el canal. */
public class Recordatorio extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        String id = in.getStringExtra("id");
        long inicio = in.getLongExtra("inicio", 0);
        if (id == null || inicio == 0) return;
        if ("seguir".equals(in.getStringExtra("tipo"))) {
            // Partido de tus equipos en juego: consulta el marcador, avisa de goles nuevos y programa la siguiente consulta.
            final org.json.JSONObject m;
            try { m = new org.json.JSONObject(in.getStringExtra("partido")); } catch (Exception e) { return; }
            final Context app = c.getApplicationContext();
            final PendingResult pr = goAsync();
            new Thread(() -> {
                try {
                    if (Seguimiento.consultarYAvisar(app, m)) Seguimiento.programar(app, m, System.currentTimeMillis() + Seguimiento.CADA);
                } catch (Exception e) {
                    Seguimiento.diag(app, "Error en el seguimiento: " + e.getClass().getSimpleName());
                    Seguimiento.programar(app, m, System.currentTimeMillis() + Seguimiento.CADA);
                } finally {
                    pr.finish();
                }
            }).start();
            return;
        }
        // Si Android lo entrega muy tarde (el partido ya va por la mitad), no tiene sentido avisar.
        if (System.currentTimeMillis() > inicio + 10 * 60 * 1000L) return;
        c.getSharedPreferences(AvisosWorker.PREFS, Context.MODE_PRIVATE).edit().putLong("rec_" + id, inicio).apply();
        AvisosWorker.crearCanal(c);
        if (!AvisosWorker.puedeNotificar(c)) return;

        long falta = Math.max(0, Math.round((inicio - System.currentTimeMillis()) / 60000.0));
        if ("vuelta".equals(in.getStringExtra("tipo"))) { vuelta(c, in, inicio, falta); return; }
        String cuando = falta <= 0 ? "Empieza ya" : "Empieza en " + falta + " min";
        String canal = in.getStringExtra("canal");
        Intent abrir = new Intent(c, MainActivity.class).putExtra("partido", id)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int n = ("rec_" + id).hashCode();
        PendingIntent pi = PendingIntent.getActivity(c, n, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, AvisosWorker.CANAL_PREVIA)
                .setSmallIcon(R.drawable.ic_aviso)
                .setColor(0xFF1F5E3B)
                .setContentTitle("⏱ " + cuando + ": " + in.getStringExtra("titulo"))
                .setContentText(in.getStringExtra("hora") + " h · " + canal)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(in.getStringExtra("hora") + " h\n📺 " + canal))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setTimeoutAfter(inicio + 30 * 60 * 1000L - System.currentTimeMillis())
                .setContentIntent(pi)
                .setAutoCancel(true);
        try { NotificationManagerCompat.from(c).notify(n, b.build()); } catch (SecurityException ignored) { }
    }

    /** «Vuelve el fútbol»: primer partido (o partidos) tras un parón. */
    private void vuelta(Context c, Intent in, long inicio, long falta) {
        String id = in.getStringExtra("id");
        String cuando = falta <= 0 ? "empieza ya" : "en " + falta + " min";
        Intent abrir = new Intent(c, MainActivity.class).putExtra("partido", in.getStringExtra("partido"))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int n = ("rec_" + id).hashCode();
        PendingIntent pi = PendingIntent.getActivity(c, n, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String cuerpo = in.getStringExtra("cuerpo");
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, AvisosWorker.CANAL_VUELTA)
                .setSmallIcon(R.drawable.ic_aviso)
                .setColor(0xFF1F5E3B)
                .setContentTitle("⚽ Vuelve el fútbol, " + cuando + ": " + in.getStringExtra("titulo"))
                .setContentText(cuerpo)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(cuerpo))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setTimeoutAfter(inicio + 30 * 60 * 1000L - System.currentTimeMillis())
                .setContentIntent(pi)
                .setAutoCancel(true);
        try { NotificationManagerCompat.from(c).notify(n, b.build()); } catch (SecurityException ignored) { }
    }
}
