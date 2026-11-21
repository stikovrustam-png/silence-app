package app.silence.messenger;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Мост JS → медиа-уведомление (голосовые и музыка в шторке). */
@CapacitorPlugin(name = "SilenceMedia")
public class SilenceMediaPlugin extends Plugin {
    static SilenceMediaPlugin inst;

    @Override
    public void load() {
        inst = this;
    }

    @PluginMethod
    public void update(PluginCall call) {
        try {
            SilenceMediaService.title = call.getString("title", "");
            SilenceMediaService.text = call.getString("text", "");
            SilenceMediaService.playing = Boolean.TRUE.equals(call.getBoolean("playing", false));
            SilenceMediaService.pos = call.getData().optLong("pos", 0);
            SilenceMediaService.dur = call.getData().optLong("dur", 0);
            SilenceMediaService.speed = (float) call.getData().optDouble("speed", 1.0);
            String av = call.getString("avatar", null);
            if (av != null && !av.isEmpty()) {
                try {
                    byte[] b = Base64.decode(av, Base64.DEFAULT);
                    Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                    if (bm != null) SilenceMediaService.avatar = bm;
                } catch (Exception e) { }
            }
            Context c = getContext();
            Intent i = new Intent(c, SilenceMediaService.class);
            i.setAction(SilenceMediaService.ACT_UPDATE);
            if (Build.VERSION.SDK_INT >= 26 && !SilenceMediaService.running) c.startForegroundService(i);
            else c.startService(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            SilenceMediaService.avatar = null;
            if (SilenceMediaService.running) {
                Intent i = new Intent(getContext(), SilenceMediaService.class);
                i.setAction(SilenceMediaService.ACT_STOP);
                getContext().startService(i);
            }
        } catch (Exception e) { }
        call.resolve();
    }

    /** Нажатие в уведомлении / на гарнитуре → JS. */
    static void emit(String action, long pos) {
        try {
            if (inst == null) return;
            JSObject o = new JSObject();
            o.put("action", action);
            o.put("pos", pos);
            inst.notifyListeners("action", o);
        } catch (Exception e) { }
    }
}
