package app.silence.messenger;

import android.content.Intent;
import androidx.activity.OnBackPressedCallback;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SilenceFilesPlugin.class);
        registerPlugin(SilenceRecorderPlugin.class);
        registerPlugin(SilenceNotifyPlugin.class);
        registerPlugin(SilencePickerPlugin.class);
        registerPlugin(SilenceCachePlugin.class);
        super.onCreate(savedInstanceState);
        try {
            getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
        } catch (Exception e) { }
        grab(getIntent());
    }

    private boolean backSet = false;

    @Override
    public void onResume() {
        super.onResume();
        if (!backSet) {
            backSet = true;
            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    try {
                        getBridge().getWebView().evaluateJavascript(
                            "(function(){try{return window.__silenceBack?window.__silenceBack():false}catch(e){return false}})()",
                            new android.webkit.ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String v) {
                                    if (!"true".equals(v)) moveTaskToBack(true);
                                }
                            });
                    } catch (Exception e) {
                        moveTaskToBack(true);
                    }
                }
            });
        }
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
