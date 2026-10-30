package app.silence.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            String tk = c.getSharedPreferences(NotifyService.PREFS, Context.MODE_PRIVATE).getString("token", "");
            android.content.SharedPreferences sp = c.getSharedPreferences(NotifyService.PREFS, Context.MODE_PRIVATE);
            boolean pushOn = sp.getBoolean("pushPref", true) && sp.getBoolean("pushOk", false);
            if (tk != null && !tk.isEmpty() && !pushOn) NotifyService.start(c);
        } catch (Exception e) { }
    }
}
