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
            ed.putString("token", token == null ? "" : token);
            ed.putString("api", call.getString("api", ""));
            ed.putString("me", me == null ? "" : me);
            JSArray skip = call.getArray("skip", new JSArray());
            ed.putString("skip", skip.toString());
            JSObject names = call.getObject("names", new JSObject());
            ed.putString("names", names.toString());
            ed.apply();
            if (token != null && !token.isEmpty() && me != null && !me.isEmpty()) NotifyService.start(getContext());
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        sp().edit().remove("token").apply();
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
}
