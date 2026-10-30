package app.silence.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/** Медиа-уведомление: ник + аватар, снизу мини-иконка приложения, кнопки пауза/закрыть. */
public class SilenceMediaService extends Service {
    static final String ACT_UPDATE = "app.silence.media.UPDATE";
    static final String ACT_STOP = "app.silence.media.STOP";
    static final String ACT_PLAY = "app.silence.media.PLAY";
    static final String ACT_PAUSE = "app.silence.media.PAUSE";
    static final String ACT_CLOSE = "app.silence.media.CLOSE";
    static final String CH = "silence_media";
    static final int NID = 7301;

    static volatile boolean running = false;
    static volatile String title = "", text = "";
    static volatile boolean playing = false;
    static volatile long pos = 0, dur = 0;
    static volatile float speed = 1f;
    static volatile Bitmap avatar = null;

    private MediaSession session;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        makeChannel();
    }

    private void makeChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel c = new NotificationChannel(CH, "Воспроизведение", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            c.setSound(null, null);
            c.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(c);
        } catch (Exception e) { }
    }

    private int statIcon() {
        try {
            int id = getResources().getIdentifier("ic_stat_silence", "drawable", getPackageName());
            if (id != 0) return id;
        } catch (Exception e) { }
        return android.R.drawable.stat_notify_chat;
    }

    private void ensureSession() {
        if (session != null) return;
        session = new MediaSession(this, "SilenceMedia");
        if (Build.VERSION.SDK_INT < 33) {
            try { session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS); } catch (Exception e) { }
        }
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { SilenceMediaPlugin.emit("play", -1); }
            @Override public void onPause() { SilenceMediaPlugin.emit("pause", -1); }
            @Override public void onStop() { SilenceMediaPlugin.emit("stop", -1); }
            @Override public void onSeekTo(long p) { SilenceMediaPlugin.emit("seek", p); }
        });
        session.setActive(true);
    }

    private PendingIntent act(String a, int code) {
        Intent i = new Intent(this, SilenceMediaService.class);
        i.setAction(a);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getService(this, code, i, fl);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(this, 7302, i, fl);
    }

    private Notification build() {
        MediaSession.Token tok = session.getSessionToken();
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setSmallIcon(statIcon());
        b.setContentTitle(title);
        b.setContentText(text);
        Bitmap av = avatar;
        if (av != null) b.setLargeIcon(av);
        b.setContentIntent(openApp());
        b.setDeleteIntent(act(ACT_CLOSE, 7305));
        b.setVisibility(Notification.VISIBILITY_PUBLIC);
        b.setOnlyAlertOnce(true);
        b.setShowWhen(false);
        b.setOngoing(playing);
        b.setColor(0xFF38BDF8);
        int pi = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String pt = playing ? "Пауза" : "Воспроизвести";
        PendingIntent ppi = act(playing ? ACT_PAUSE : ACT_PLAY, playing ? 7304 : 7303);
        int xi = android.R.drawable.ic_menu_close_clear_cancel;
        if (Build.VERSION.SDK_INT >= 23) {
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, pi), pt, ppi).build());
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, xi), "Закрыть", act(ACT_CLOSE, 7305)).build());
        } else {
            b.addAction(pi, pt, ppi);
            b.addAction(xi, "Закрыть", act(ACT_CLOSE, 7305));
        }
        b.setStyle(new Notification.MediaStyle().setMediaSession(tok).setShowActionsInCompactView(0, 1));
        return b.build();
    }

    private void applyState() {
        MediaMetadata.Builder md = new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dur > 0 ? dur : -1);
        Bitmap av = avatar;
        if (av != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, av);
        session.setMetadata(md.build());
        long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
            | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO;
        PlaybackState ps = new PlaybackState.Builder()
            .setActions(acts)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? speed : 0f)
            .build();
        session.setPlaybackState(ps);
    }

    private void goForeground(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NID, n);
        } catch (Exception e) {
            try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NID, n); } catch (Exception e2) { }
        }
    }

    private void shutdown(int startId) {
        try { stopForeground(true); } catch (Exception e) { }
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NID); } catch (Exception e) { }
        if (session != null) {
            try { session.setActive(false); session.release(); } catch (Exception e) { }
            session = null;
        }
        /* stopSelf(startId): если уже пришла новая команда обновления — служба не гасится */
        stopSelf(startId);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? ACT_STOP : intent.getAction();
        if (a == null) a = ACT_UPDATE;
        try {
            if (ACT_UPDATE.equals(a)) {
                ensureSession();
                applyState();
                Notification n = build();
                /* startForeground обязателен после startForegroundService — вызываем всегда */
                goForeground(n);
                if (!playing) {
                    /* на паузе уведомление можно смахнуть */
                    try {
                        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH);
                        else stopForeground(false);
                    } catch (Exception e) { }
                    try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NID, n); } catch (Exception e) { }
                }
            } else if (ACT_PLAY.equals(a)) {
                SilenceMediaPlugin.emit("play", -1);
            } else if (ACT_PAUSE.equals(a)) {
                SilenceMediaPlugin.emit("pause", -1);
            } else if (ACT_CLOSE.equals(a)) {
                SilenceMediaPlugin.emit("stop", -1);
                shutdown(startId);
            } else if (ACT_STOP.equals(a)) {
                shutdown(startId);
            }
        } catch (Exception e) {
            shutdown(startId);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        SilenceMediaPlugin.emit("stop", -1);
        shutdown(-1);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (session != null) {
            try { session.release(); } catch (Exception e) { }
            session = null;
        }
        super.onDestroy();
    }
}
