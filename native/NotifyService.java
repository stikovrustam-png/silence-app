package app.silence.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NotifyService extends Service {
    static final String PREFS = "sil_notify";
    static final String CH_MSG = "messages";
    static final String CH_SVC = "service";
    static final String CH_CALL = "calls2";
    private long shownCall = 0;
    static volatile boolean foreground = false;
    static volatile String pendingOpen = null;
    static volatile boolean running = false;

    private Thread th;
    private volatile boolean run;
    private final Map<String, List<String>> lines = new HashMap<>();

    static void start(Context c) {
        try {
            Intent i = new Intent(c, NotifyService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) { }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, NotifyService.class)); } catch (Exception e) { }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        makeChannels();
        Notification n = new NotificationCompat.Builder(this, CH_SVC)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Silence")
            .setContentText("Приём сообщений в фоне")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openIntent(null, 1))
            .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, 0x40000000);
            else startForeground(1, n);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (th == null || !th.isAlive()) {
            run = true;
            running = true;
            th = new Thread(this::loop, "silence-notify");
            th.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        run = false;
        running = false;
        if (th != null) th.interrupt();
        super.onDestroy();
    }

    private void makeChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel m = new NotificationChannel(CH_MSG, "Сообщения", NotificationManager.IMPORTANCE_HIGH);
        m.enableVibration(true);
        m.setLightColor(0xFF38BDF8);
        nm.createNotificationChannel(m);
        NotificationChannel s = new NotificationChannel(CH_SVC, "Работа в фоне", NotificationManager.IMPORTANCE_MIN);
        s.setShowBadge(false);
        nm.createNotificationChannel(s);
        NotificationChannel c = new NotificationChannel(CH_CALL, "Звонки", NotificationManager.IMPORTANCE_HIGH);
        c.enableVibration(true);
        c.setVibrationPattern(new long[]{0, 600, 400, 600, 400, 600});
        c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        c.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
        nm.createNotificationChannel(c);
    }

    private PendingIntent openIntent(String email, int code) { return openIntent(email, code, false); }

    private PendingIntent openIntent(String email, int code, boolean call) {
        Intent i = new Intent(this, MainActivity.class);
        if (call) i.putExtra("open_call", true);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (email != null) i.putExtra("open_email", email);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, code, i, fl);
    }

    private void loop() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        int n = 0;
        while (run) {
            PowerManager.WakeLock wl = null;
            try {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "silence:poll");
                wl.acquire(20000);
                if (n % 2 == 0) poll(); else pollCallOnly();
            } catch (Exception e) {
            } finally {
                try { if (wl != null && wl.isHeld()) wl.release(); } catch (Exception e) { }
            }
            n++;
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }
    }

    private void pollCallOnly() {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = sp.getString("token", "");
        String api = sp.getString("api", "");
        if (token.isEmpty() || api.isEmpty()) return;
        pollCall(api, token);
    }

    private String post(String api, JSONObject body) throws Exception {
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
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        c.disconnect();
        return bo.toString("UTF-8");
    }

    private void poll() throws Exception {
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String token = sp.getString("token", "");
        String api = sp.getString("api", "");
        String me = sp.getString("me", "");
        if (token.isEmpty() || api.isEmpty() || me.isEmpty()) return;
        long since = sp.getLong("since:" + me, 0);
        long lastId = sp.getLong("lastId:" + me, 0);
        boolean baseline = !sp.contains("since:" + me);

        JSONObject req = new JSONObject();
        req.put("action", "sync");
        req.put("token", token);
        req.put("since", since);
        req.put("bg", 1);
        JSONObject r = new JSONObject(post(api, req));
        if (!r.optBoolean("ok", false)) {
            if ("unauthorized".equals(r.optString("error"))) {
                sp.edit().remove("token").apply();
                stopSelf();
            }
            return;
        }

        JSONArray skipArr = new JSONArray(sp.getString("skip", "[]"));
        JSONObject names = new JSONObject(sp.getString("names", "{}"));
        long maxId = lastId;
        JSONArray msgs = r.optJSONArray("messages");
        List<JSONObject> fresh = new ArrayList<>();
        if (msgs != null) {
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.getJSONObject(i);
                long id = m.optLong("id", 0);
                if (id > maxId) maxId = id;
                if (baseline || foreground || id <= lastId) continue;
                if (m.optBoolean("deleted", false) || m.optBoolean("read", false)) continue;
                String from = m.optString("from", "");
                if (from.isEmpty() || from.equals(me)) continue;
                if (contains(skipArr, from)) continue;
                JSONArray hid = m.optJSONArray("hid");
                if (hid != null && contains(hid, me)) continue;
                fresh.add(m);
            }
        }
        for (JSONObject m : fresh) show(m, names);

        long svLast = sp.getLong("svId:" + me, 0);
        long svMax = svLast;
        JSONArray svc = r.optJSONArray("service");
        if (svc != null) {
            for (int i = 0; i < svc.length(); i++) {
                JSONObject sv = svc.getJSONObject(i);
                long sid = sv.optLong("id", 0);
                if (sid > svMax) svMax = sid;
                if (baseline || foreground || sid <= svLast) continue;
                JSONObject sm = new JSONObject();
                sm.put("from", "support@silence.app");
                sm.put("type", "text");
                sm.put("text", sv.optString("text", ""));
                sm.put("ts", sv.optLong("ts", System.currentTimeMillis()));
                names.put("support@silence.app", "Silence");
                show(sm, names);
            }
        }

        pollCall(api, token);

        SharedPreferences.Editor ed = sp.edit();
        long rev = r.optLong("rev", since);
        ed.putLong("since:" + me, Math.max(rev, since));
        ed.putLong("lastId:" + me, maxId);
        ed.putLong("svId:" + me, svMax);
        ed.apply();
    }

    private void pollCall(String api, String token) {
        try {
            if (foreground) return;
            JSONObject req = new JSONObject();
            req.put("action", "call_poll");
            req.put("token", token);
            req.put("bg", 1);
            JSONObject r = new JSONObject(post(api, req));
            if (!r.optBoolean("ok", false)) return;
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            JSONObject inc = r.optJSONObject("incoming");
            if (inc == null) {
                if (shownCall != 0) { nm.cancel(2); shownCall = 0; }
                return;
            }
            long id = inc.optLong("id", 0);
            if (id == 0 || id == shownCall) return;
            shownCall = id;
            String from = inc.optString("from", "");
            JSONObject names = new JSONObject(getSharedPreferences(PREFS, MODE_PRIVATE).getString("names", "{}"));
            String name = names.optString(from, "");
            if (name.isEmpty()) name = from.contains("@") ? from.substring(0, from.indexOf('@')) : from;
            Notification n = new NotificationCompat.Builder(this, CH_CALL)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle(name)
                .setContentText("Входящий звонок Silence")
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(true)
                .setTimeoutAfter(50000)
                .setColor(0xFF22C55E)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openIntent(null, 2, true))
                .setFullScreenIntent(openIntent(null, 3, true), true)
                .build();
            n.flags |= Notification.FLAG_INSISTENT;
            NotificationManagerCompat.from(this).notify(2, n);
        } catch (SecurityException e) {
        } catch (Exception e) { }
    }

    private static boolean contains(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) if (v.equals(a.optString(i))) return true;
        return false;
    }

    static String describe(String type, String t) {
        if (t == null) t = "";
        if (t.length() > 0 && t.charAt(0) == '\u2063') {
            int e = t.indexOf('\u2063', 1);
            if (e > 0) t = t.substring(e + 1);
        }
        JSONObject meta = null;
        String cap = t;
        if (!"text".equals(type) && !"image".equals(type) && t.length() > 0 && t.charAt(0) == '\u2064') {
            int e = t.indexOf('\u2064', 1);
            if (e > 0) {
                try { meta = new JSONObject(t.substring(1, e)); } catch (Exception x) { }
                cap = t.substring(e + 1);
            }
        }
        String label;
        if ("image".equals(type)) label = "Фото";
        else if ("video".equals(type)) label = "Видео";
        else if ("audio".equals(type)) {
            String nm = meta != null ? meta.optString("n", "") : "";
            if (meta != null && (meta.optInt("v", 0) == 1 || nm.startsWith("voice_"))) label = "Голосовое сообщение";
            else {
                String ti = meta != null ? meta.optString("t", "") : "";
                label = "Музыка" + (ti.isEmpty() ? "" : ": " + ti);
            }
        } else if ("file".equals(type)) {
            String nm = meta != null ? meta.optString("n", "") : "";
            label = "Документ" + (nm.isEmpty() ? "" : ": " + nm);
        } else return cap;
        return cap.trim().isEmpty() ? label : label + " · " + cap.trim();
    }

    private void show(JSONObject m, JSONObject names) {
        try {
            String from = m.optString("from");
            String name = names.optString(from, "");
            if (name.isEmpty()) name = from.contains("@") ? from.substring(0, from.indexOf('@')) : from;
            String text = describe(m.optString("type", "text"), m.optString("text", ""));
            if (text.length() > 300) text = text.substring(0, 300) + "…";
            List<String> l = lines.get(from);
            if (l == null) { l = new ArrayList<>(); lines.put(from, l); }
            l.add(text);
            while (l.size() > 6) l.remove(0);
            int id = 10 + Math.abs(from.hashCode() % 100000);
            NotificationCompat.InboxStyle st = new NotificationCompat.InboxStyle().setBigContentTitle(name);
            for (String s : l) st.addLine(s);
            if (l.size() > 1) st.setSummaryText(l.size() + " новых");
            Notification n = new NotificationCompat.Builder(this, CH_MSG)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(name)
                .setContentText(text)
                .setStyle(st)
                .setNumber(l.size())
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setColor(0xFF38BDF8)
                .setAutoCancel(true)
                .setWhen(m.optLong("ts", System.currentTimeMillis()))
                .setShowWhen(true)
                .setContentIntent(openIntent(from, id))
                .build();
            NotificationManagerCompat.from(this).notify(id, n);
        } catch (SecurityException e) {
        } catch (Exception e) { }
    }

    void clearAll() {
        lines.clear();
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) {
                for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) {
                    if (s.getId() != 1) nm.cancel(s.getId());
                }
            }
        } catch (Exception e) { }
    }
}
