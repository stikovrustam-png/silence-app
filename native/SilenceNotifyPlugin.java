package app.silence.messenger;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;
import org.json.JSONObject;

@CapacitorPlugin(
    name = "SilenceNotify",
    permissions = { @Permission(strings = { Manifest.permission.POST_NOTIFICATIONS }, alias = "notif") }
)
public class SilenceNotifyPlugin extends Plugin {

    private SharedPreferences sp() {
        return getContext().getSharedPreferences(NotifyService.PREFS, Context.MODE_PRIVATE);
    }

    @PluginMethod
    public void configure(PluginCall call) {
        try {
            SharedPreferences.Editor ed = sp().edit();
            String token = call.getString("token", "");
            String me = call.getString("me", "");
            String old = sp().getString("me", "");
            if (!old.equals(me)) { ed.remove("since:" + me); ed.remove("lastId:" + me); }
            if (!sp().getString("token", "").equals(token == null ? "" : token)) { ed.putBoolean("pushOk", false); ed.remove("fcmSent"); }
            ed.putString("token", token == null ? "" : token);
            ed.putString("api", call.getString("api", ""));
            ed.putString("me", me == null ? "" : me);
            JSArray skip = call.getArray("skip", new JSArray());
            ed.putString("skip", skip.toString());
            JSObject names = call.getObject("names", new JSObject());
            ed.putString("names", names.toString());
            ed.apply();
            if (token != null && !token.isEmpty() && me != null && !me.isEmpty()) {
                boolean pref = sp().getBoolean("pushPref", true);
                if (!(pref && sp().getBoolean("pushOk", false))) NotifyService.start(getContext());
                if (pref) FcmHelper.register(getContext());
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void pushState(PluginCall call) {
        JSObject r = new JSObject();
        r.put("available", FcmHelper.available(getContext()));
        r.put("on", sp().getBoolean("pushPref", true));
        r.put("ok", sp().getBoolean("pushOk", false));
        call.resolve(r);
    }

    @PluginMethod
    public void setPush(PluginCall call) {
        boolean on = !Boolean.FALSE.equals(call.getBoolean("on", true));
        SharedPreferences.Editor ed = sp().edit();
        ed.putBoolean("pushPref", on);
        if (!on) { ed.putBoolean("pushOk", false); ed.remove("fcmSent"); }
        ed.apply();
        if (on) {
            FcmHelper.register(getContext());
        } else {
            FcmHelper.unregister(getContext());
            if (!sp().getString("token", "").isEmpty()) NotifyService.start(getContext());
        }
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        sp().edit().remove("token").putBoolean("pushOk", false).remove("fcmSent").apply();
        NotifyService.stop(getContext());
        call.resolve();
    }

    @PluginMethod
    public void setForeground(PluginCall call) {
        boolean fg = Boolean.TRUE.equals(call.getBoolean("fg", false));
        NotifyService.foreground = fg;
        if (fg) {
            try {
                NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
                if (Build.VERSION.SDK_INT >= 23) {
                    for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) {
                        if (s.getId() != 1) nm.cancel(s.getId());
                    }
                }
            } catch (Exception e) { }
        }
        call.resolve();
    }

    @PluginMethod
    public void takeOpen(PluginCall call) {
        JSObject r = new JSObject();
        String e = NotifyService.pendingOpen;
        NotifyService.pendingOpen = null;
        r.put("email", e == null ? "" : e);
        call.resolve(r);
    }

    @PluginMethod
    public void askPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notif") == PermissionState.GRANTED) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        requestPermissionForAlias("notif", call, "notifPerm");
    }

    @PermissionCallback
    private void notifPerm(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", getPermissionState("notif") == PermissionState.GRANTED);
        call.resolve(r);
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        JSObject r = new JSObject();
        boolean on = true;
        try {
            on = androidx.core.app.NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
        } catch (Exception e) { }
        r.put("enabled", on);
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        try {
            Context c = getContext();
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            i.putExtra(Settings.EXTRA_APP_PACKAGE, c.getPackageName());
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            call.resolve();
        } catch (Exception e) {
            try {
                Context c = getContext();
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                i.setData(Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                call.resolve();
            } catch (Exception e2) {
                call.reject(String.valueOf(e2.getMessage()));
            }
        }
    }

    private static String sysProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object o = c.getMethod("get", String.class).invoke(null, key);
            return o == null ? "" : String.valueOf(o).trim();
        } catch (Throwable e) { return ""; }
    }

    @PluginMethod
    public void deviceInfo(PluginCall call) {
        JSObject r = new JSObject();
        String name = "";
        String[] keys = {
            "ro.product.marketname", "ro.product.vendor.marketname", "ro.product.odm.marketname",
            "ro.product.system.marketname", "ro.config.marketing_name", "ro.vendor.oplus.market.name",
            "ro.product.model_marketing_name"
        };
        for (String k : keys) {
            name = sysProp(k);
            if (!name.isEmpty()) break;
        }
        if (name.isEmpty()) {
            try {
                String dn = Settings.Global.getString(getContext().getContentResolver(), "device_name");
                if (dn != null && !dn.trim().isEmpty()) name = dn.trim();
            } catch (Throwable e) { }
        }
        String man = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        String model = Build.MODEL == null ? "" : Build.MODEL;
        if (name.isEmpty()) {
            if (!man.isEmpty() && model.toLowerCase().startsWith(man.toLowerCase())) name = model;
            else if (!man.isEmpty()) name = man.substring(0, 1).toUpperCase() + man.substring(1) + " " + model;
            else name = model;
        }
        r.put("name", name);
        r.put("model", model);
        r.put("manufacturer", man);
        r.put("os", "Android " + Build.VERSION.RELEASE);
        call.resolve(r);
    }

    @PluginMethod
    public void batteryOpt(PluginCall call) {
        JSObject r = new JSObject();
        try {
            Context c = getContext();
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            boolean ok = Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(c.getPackageName());
            r.put("ignoring", ok);
            if (!ok && Boolean.TRUE.equals(call.getBoolean("ask", false))) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            }
        } catch (Exception e) {
            r.put("error", String.valueOf(e.getMessage()));
        }
        call.resolve(r);
    }

    @PluginMethod
    public void setSpeaker(PluginCall call) {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
            boolean reset = Boolean.TRUE.equals(call.getBoolean("reset", false));
            if (reset) {
                am.setSpeakerphoneOn(false);
                am.setMode(android.media.AudioManager.MODE_NORMAL);
            } else {
                am.setMode(android.media.AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(on);
            }
        } catch (Exception e) { }
        call.resolve();
    }

    @PluginMethod
    public void fullScreen(PluginCall call) {
        JSObject r = new JSObject();
        boolean allowed = true;
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
                Object o = NotificationManager.class.getMethod("canUseFullScreenIntent").invoke(nm);
                allowed = !(o instanceof Boolean) || ((Boolean) o).booleanValue();
                if (!allowed && Boolean.TRUE.equals(call.getBoolean("ask", false))) {
                    Intent i = new Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT");
                    i.setData(Uri.parse("package:" + getContext().getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                }
            }
        } catch (Exception e) { }
        r.put("allowed", allowed);
        call.resolve(r);
    }
}
