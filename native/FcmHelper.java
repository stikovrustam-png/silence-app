package app.silence.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

/** Регистрация push-токена (FCM) на сервере Silence. */
public class FcmHelper {

    static boolean available(Context c) {
        try {
            /* Проверка Google Play Services через reflection: библиотеке play-services-base не нужно быть в зависимостях при компиляции. */
            try {
                Class<?> k = Class.forName("com.google.android.gms.common.GoogleApiAvailability");
                Object inst = k.getMethod("getInstance").invoke(null);
                Object code = k.getMethod("isGooglePlayServicesAvailable", Context.class).invoke(inst, c);
                if (code instanceof Integer && ((Integer) code).intValue() != 0) return false; /* 0 = SUCCESS */
            } catch (Throwable ignore) { /* не смогли проверить — считаем, что можно пробовать */ }
            return !FirebaseApp.getApps(c).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NotifyService.PREFS, Context.MODE_PRIVATE);
    }

    private static String post(String api, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(api).openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setDoOutput(true);
        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();
        InputStream is = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        c.disconnect();
        return bo.toString("UTF-8");
    }

    /** Получает FCM-токен и отдаёт серверу. При успехе постоянная фоновая служба больше не нужна. */
    static void register(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences sp = sp(c);
                    if (!sp.getBoolean("pushPref", true)) return;
                    String token = sp.getString("token", "");
                    String api = sp.getString("api", "");
                    if (token.isEmpty() || api.isEmpty() || !available(c)) return;
                    String fcm = Tasks.await(FirebaseMessaging.getInstance().getToken(), 20, TimeUnit.SECONDS);
                    if (fcm == null || fcm.isEmpty()) return;
                    String key = fcm + "|" + token;
                    if (key.equals(sp.getString("fcmSent", "")) && sp.getBoolean("pushOk", false)) return;
                    JSONObject req = new JSONObject();
                    req.put("action", "push_register");
                    req.put("token", token);
                    req.put("fcm", fcm);
                    JSONObject r = new JSONObject(post(api, req));
                    if (r.optBoolean("ok", false)) {
                        sp.edit().putString("fcmSent", key).putBoolean("pushOk", true).apply();
                        NotifyService.stop(c);
                    }
                } catch (Throwable t) { }
            }
        }, "silence-fcm-reg").start();
    }

    /** Сообщает серверу, что push на этом устройстве больше не нужен. */
    static void unregister(final Context ctx) {
        final Context c = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences sp = sp(c);
                    String token = sp.getString("token", "");
                    String api = sp.getString("api", "");
                    if (token.isEmpty() || api.isEmpty()) return;
                    JSONObject req = new JSONObject();
                    req.put("action", "push_unregister");
                    req.put("token", token);
                    post(api, req);
                } catch (Throwable t) { }
            }
        }, "silence-fcm-unreg").start();
    }

    /** То же, но с токеном/адресом, сохранёнными заранее (когда настройки сразу стираются, например при выключении уведомлений). */
    static void unregisterWith(final Context ctx, final String api, final String token) {
        if (api == null || api.isEmpty() || token == null || token.isEmpty()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "push_unregister");
                    req.put("token", token);
                    post(api, req);
                } catch (Throwable t) { }
            }
        }, "silence-fcm-unreg2").start();
    }
}
