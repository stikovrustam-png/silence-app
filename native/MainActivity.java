package app.silence.messenger;

import android.content.Intent;
import androidx.activity.OnBackPressedCallback;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(SilenceFilesPlugin.class);
        registerPlugin(SilenceRecorderPlugin.class);
        registerPlugin(SilenceNotifyPlugin.class);
        registerPlugin(SilencePickerPlugin.class);
        registerPlugin(SilenceCachePlugin.class);
        registerPlugin(SilenceMediaPlugin.class);
        registerPlugin(SilenceInsetsPlugin.class);
        super.onCreate(savedInstanceState);
        try {
            getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
        } catch (Exception e) { }
        edgeToEdge();
        grab(getIntent());
    }

    /** Полноэкранный режим: прозрачные статус-бар и навигация, содержимое рисуется под ними. */
    @SuppressWarnings("deprecation")
    private void edgeToEdge() {
        try {
            final Window w = getWindow();
            WindowCompat.setDecorFitsSystemWindows(w, false);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (Build.VERSION.SDK_INT >= 29) {
                w.setNavigationBarContrastEnforced(false);
                w.setStatusBarContrastEnforced(false);
            }
            WindowInsetsControllerCompat ic = WindowCompat.getInsetsController(w, w.getDecorView());
            ic.setAppearanceLightStatusBars(false);
            ic.setAppearanceLightNavigationBars(false);
            final View content = findViewById(android.R.id.content);
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                int kb;
                if (Build.VERSION.SDK_INT >= 30) {
                    kb = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                } else {
                    int sw = insets.getSystemWindowInsetBottom(), st = insets.getStableInsetBottom();
                    kb = sw > st ? sw : 0;
                }
                v.setPadding(0, 0, 0, kb);
                float d = getResources().getDisplayMetrics().density;
                SilenceInsetsPlugin.push(Math.round(sys.top / d), kb > 0 ? 0 : Math.round(sys.bottom / d));
                return WindowInsetsCompat.CONSUMED;
            });
            ViewCompat.requestApplyInsets(content);
        } catch (Exception e) { }
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
        NotifyService.activityResumed = true;
        NotifyService.foreground = true;
    }

    @Override
    public void onPause() {
        NotifyService.activityResumed = false;
        NotifyService.foreground = false;
        super.onPause();
        keepWeb();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWeb();
    }

    /** Во время звонка не даём WebView «заснуть» при выходе из приложения. */
    private void keepWeb() {
        if (!CallBridge.active) return;
        try {
            getBridge().getWebView().onResume();
            getBridge().getWebView().resumeTimers();
        } catch (Exception e) { }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        grab(intent);
    }

    private void grab(Intent i) {
        if (i != null && i.hasExtra("open_email")) NotifyService.pendingOpen = i.getStringExtra("open_email");
        if (i != null && i.hasExtra("call_action")) {
            CallBridge.post(i.getStringExtra("call_action"), i.getLongExtra("call_id", 0));
            try {
                ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(2);
            } catch (Exception e) { }
        }
        if (i != null && i.getBooleanExtra("open_call", false)) {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            }
        }
    }
}
