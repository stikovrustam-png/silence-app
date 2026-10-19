package app.silence.messenger;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Кнопки «Отклонить» / «Завершить» в уведомлении. Работает, даже если страница приложения не отвечает. */
public class CallActionReceiver extends BroadcastReceiver {

    static PendingIntent pi(Context c, String action, long id, int code) {
        Intent i = new Intent(c, CallActionReceiver.class);
        i.setAction("app.silence.messenger.CALL_" + action.toUpperCase() + "_" + code);
        i.putExtra("a", action).putExtra("id", id);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getBroadcast(c, code, i, fl);
    }

    @Override
    public void onReceive(final Context c, Intent i) {
        final String a = i.getStringExtra("a");
        final long id = i.getLongExtra("id", 0);
        if (a == null) return;
        if ("screenstop".equals(a)) { ScreenService.stop(c); CallBridge.screenEnded(); return; }
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.cancel(2);
        } catch (Exception e) { }
        CallBridge.post(a, id);
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(2500);
                    /* страница не забрала действие — завершаем звонок напрямую на сервере */
                    if (a.equals(CallBridge.pending)) {
                        CallBridge.pending = null;
                        httpEnd(c, id, "decline".equals(a) ? "declined" : "ended");
                        CallService.stop(c);
                    }
                } catch (Exception e) {
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }

    static void httpEnd(Context c, long id, String reason) {
        try {
            if (id == 0) return;
            android.content.SharedPreferences sp = c.getSharedPreferences(NotifyService.PREFS, Context.MODE_PRIVATE);
            String api = sp.getString("api", ""), token = sp.getString("token", "");
            if (api.isEmpty() || token.isEmpty()) return;
            JSONObject req = new JSONObject();
            req.put("action", "call_end").put("token", token).put("id", id).put("reason", reason);
            HttpURLConnection h = (HttpURLConnection) new URL(api).openConnection();
            h.setRequestMethod("POST");
            h.setConnectTimeout(6000);
            h.setReadTimeout(6000);
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", "application/json");
            OutputStream o = h.getOutputStream();
            o.write(req.toString().getBytes("UTF-8"));
            o.close();
            h.getResponseCode();
            h.disconnect();
        } catch (Exception e) { }
    }
}
