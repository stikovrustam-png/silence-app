package app.silence.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            String tk = c.getSharedPreferences(NotifyService.PREFS, Context.MODE_PRIVATE).getString("token", "");
            if (tk != null && !tk.isEmpty()) NotifyService.start(c);
        } catch (Exception e) { }
    }
}
