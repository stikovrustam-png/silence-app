package app.silence.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;

import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Показ экрана: системный запрос → захват экрана → кадры JPEG в страницу. Работает и вне приложения. */
public class ScreenService extends Service {
    static final int NID = 4;
    static final int MAXW = 720;
    private MediaProjection proj;
    private VirtualDisplay vd;
    private ImageReader ir;
    private HandlerThread ht;
    private long last = 0;

    static int[] size(Context c) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float k = Math.min(1f, (float) MAXW / Math.min(dm.widthPixels, dm.heightPixels));
        int w = Math.round(dm.widthPixels * k) & ~1, h = Math.round(dm.heightPixels * k) & ~1;
        return new int[]{w, h, dm.densityDpi};
    }

    static void start(Context c, int code, Intent data) {
        Intent i = new Intent(c, ScreenService.class);
        i.putExtra("code", code).putExtra("data", data);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, ScreenService.class)); } catch (Exception e) { }
        try { ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).cancel(NID); } catch (Exception e) { }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !intent.hasExtra("data")) { stopSelf(); return START_NOT_STICKY; }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CallService.CH, "Текущий звонок", NotificationManager.IMPORTANCE_LOW);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CallService.CH)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Silence")
            .setContentText("Вы показываете экран")
            .setOngoing(true)
            .setShowWhen(false)
            .setColor(0xFF0EA5E9)
            .addAction(0, "Остановить", CallActionReceiver.pi(this, "screenstop", 0, 41))
            .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            else startForeground(NID, n);
        } catch (Exception e) { stopSelf(); CallBridge.screenEnded(); return START_NOT_STICKY; }
        try {
            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data");
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            proj = m.getMediaProjection(code, data);
            ht = new HandlerThread("silence-screen");
            ht.start();
            final Handler h = new Handler(ht.getLooper());
            proj.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { CallBridge.screenEnded(); stopSelf(); }
            }, h);
            final int[] sz = size(this);
            final int w = sz[0], hh = sz[1];
            ir = ImageReader.newInstance(w, hh, PixelFormat.RGBA_8888, 2);
            ir.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) {
                    Image im = null;
                    try {
                        im = r.acquireLatestImage();
                        if (im == null) return;
                        long now = System.currentTimeMillis();
                        if (now - last < 110) return; /* ~9 кадров/с */
                        last = now;
                        Image.Plane p = im.getPlanes()[0];
                        ByteBuffer buf = p.getBuffer();
                        int ps = p.getPixelStride(), rs = p.getRowStride();
                        int pad = rs - ps * w;
                        Bitmap bmp = Bitmap.createBitmap(w + pad / ps, hh, Bitmap.Config.ARGB_8888);
                        bmp.copyPixelsFromBuffer(buf);
                        Bitmap out = pad > 0 ? Bitmap.createBitmap(bmp, 0, 0, w, hh) : bmp;
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        out.compress(Bitmap.CompressFormat.JPEG, 62, bo);
                        CallBridge.frame(Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
                        if (out != bmp) out.recycle();
                        bmp.recycle();
                    } catch (Exception e) {
                    } finally {
                        if (im != null) im.close();
                    }
                }
            }, h);
            vd = proj.createVirtualDisplay("silence-screen", w, hh, sz[2],
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.getSurface(), null, h);
        } catch (Exception e) {
            CallBridge.screenEnded();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        try { if (vd != null) vd.release(); } catch (Exception e) { }
        try { if (ir != null) ir.close(); } catch (Exception e) { }
        try { if (proj != null) proj.stop(); } catch (Exception e) { }
        try { if (ht != null) ht.quitSafely(); } catch (Exception e) { }
        super.onDestroy();
    }
}
