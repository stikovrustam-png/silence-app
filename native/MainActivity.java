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
        super.onCreate(savedInstanceState);
        grab(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        grab(intent);
    }

    private void grab(Intent i) {
        if (i != null && i.hasExtra("open_email")) NotifyService.pendingOpen = i.getStringExtra("open_email");
    }
}
