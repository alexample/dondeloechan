package es.dondeloechan.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android borra las alarmas al reiniciar el móvil o actualizar la app: se vuelven a programar. */
public class Arranque extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        AvisosWorker.programar(c);
        AvisosWorker.revisarYa(c);
    }
}
