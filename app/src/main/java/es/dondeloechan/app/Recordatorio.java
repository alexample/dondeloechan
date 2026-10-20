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
        // Si Android lo entrega muy tarde (el partido ya va por la mitad), no tiene sentido avisar.
        if (System.currentTimeMillis() > inicio + 10 * 60 * 1000L) return;
        c.getSharedPreferences(AvisosWorker.PREFS, Context.MODE_PRIVATE).edit().putLong("rec_" + id, inicio).apply();
        AvisosWorker.crearCanal(c);
        if (!AvisosWorker.puedeNotificar(c)) return;

        long falta = Math.max(0, Math.round((inicio - System.currentTimeMillis()) / 60000.0));
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
}
