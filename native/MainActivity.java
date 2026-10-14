package app.silence.messenger;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SilenceFilesPlugin.class);
        registerPlugin(SilenceRecorderPlugin.class);
        registerPlugin(SilenceNotifyPlugin.class);
        registerPlugin(SilencePickerPlugin.class);
        super.onCreate(savedInstanceState);
        try {
            getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
        } catch (Exception e) { }
        grab(getIntent());
    }

    @Override
    public void onResume() {
        super.onResume();
        NotifyService.foreground = true;
    }

    @Override
    public void onPause() {
        NotifyService.foreground = false;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        grab(intent);
    }

    private void grab(Intent i) {
        if (i != null && i.hasExtra("open_email")) NotifyService.pendingOpen = i.getStringExtra("open_email");
        if (i != null && i.getBooleanExtra("open_call", false)) {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            }
        }
    }
}
