package app.silence.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            String tk = c.getSharedPreferences(NotifyService.PREFS, Context.MODE_PRIVATE).getString("token", "");
            android.content.SharedPreferences sp = c.getSharedPreferences(NotifyService.PREFS, Context.MODE_PRIVATE);
            boolean pushOn = sp.getBoolean("pushPref", true) && sp.getBoolean("pushOk", false);
            if (tk != null && !tk.isEmpty() && !pushOn) NotifyService.start(c);
        } catch (Exception e) { }
        try {
            if (i != null && Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
                android.content.SharedPreferences up = c.getSharedPreferences(SilenceUpdatePlugin.PREFS, Context.MODE_PRIVATE);
                if (up.getBoolean("restart", false)) {
                    up.edit().putBoolean("restart", false).apply();
                    afterUpdate(c);
                }
            }
        } catch (Exception e) { }
    }

    /** Приложение обновили из самого приложения: пробуем сразу открыть его, плюс уведомление «Silence обновлён». */
    private void afterUpdate(Context c) {
        Intent open = new Intent(c, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { c.startActivity(open); } catch (Exception e) { }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            String ch = "silence_update";
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(ch, "Обновления", NotificationManager.IMPORTANCE_HIGH));
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
            PendingIntent pi = PendingIntent.getActivity(c, 77, open, flags);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
            int icon = c.getResources().getIdentifier("ic_stat_silence", "drawable", c.getPackageName());
            b.setSmallIcon(icon != 0 ? icon : android.R.drawable.stat_notify_sync)
                    .setContentTitle("Silence обновлён")
                    .setContentText("Нажмите, чтобы открыть приложение")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(77, b.build());
        } catch (Exception e) { }
    }
}
