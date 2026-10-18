package app.silence.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/** Держит звонок живым при выходе из приложения + уведомление с кнопкой «Завершить». */
public class CallService extends Service {
    static final String CH = "call_active";
    static final int NID = 3;
    private PowerManager.WakeLock wl;

    static void show(Context c, String state, String name, long id, long t0) {
        try {
            Intent i = new Intent(c, CallService.class);
            i.putExtra("state", state).putExtra("name", name).putExtra("id", id).putExtra("t0", t0);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, CallService.class)); } catch (Exception e) { }
        try { ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).cancel(NID); } catch (Exception e) { }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String state = intent == null ? "active" : intent.getStringExtra("state");
        String name = intent == null ? "Silence" : intent.getStringExtra("name");
        long id = intent == null ? 0 : intent.getLongExtra("id", 0);
        long t0 = intent == null ? 0 : intent.getLongExtra("t0", 0);
        if (state == null) state = "active";
        if (name == null || name.isEmpty()) name = "Silence";

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "Текущий звонок", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        boolean act = "active".equals(state);
        String txt = act ? "Идёт звонок" : "outgoing".equals(state) ? "Вызов…" : "Соединение…";

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openPi = PendingIntent.getActivity(this, 30, open, fl);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(name)
            .setContentText(txt)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setColor(0xFF0EA5E9)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
            .addAction(0, "Завершить", CallActionReceiver.pi(this, "hangup", id, 31));
        if (act && t0 > 0) b.setUsesChronometer(true).setWhen(t0).setShowWhen(true); else b.setShowWhen(false);
        Notification n = b.build();

        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(NID, n);
        } catch (Exception e) {
            try { nm.notify(NID, n); } catch (Exception e2) { }
        }
        try {
            if (wl == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "silence:call");
                wl.setReferenceCounted(false);
            }
            if (!wl.isHeld()) wl.acquire(4 * 60 * 60 * 1000L);
        } catch (Exception e) { }
        CallBridge.active = true;
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        CallBridge.active = false;
        try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception e) { }
        super.onDestroy();
    }
}
