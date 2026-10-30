package app.silence.messenger;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Обновление приложения «поверх»: скачивает новый APK и открывает системную установку.
 * Пакет и подпись те же, versionCode больше → Android заменяет старую версию на месте,
 * все данные (аккаунт, чаты, настройки) остаются.
 */
@CapacitorPlugin(name = "SilenceUpdate")
public class SilenceUpdatePlugin extends Plugin {
    static final String PREFS = "sil_update";
    private volatile boolean cancelled = false;
    private volatile boolean busy = false;

    private File apkFile() {
        File dir = new File(getContext().getCacheDir(), "update");
        dir.mkdirs();
        return new File(dir, "Silence.apk");
    }

    private HttpURLConnection open(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "Silence-Updater");
        h.setRequestProperty("Cache-Control", "no-cache");
        return h;
    }

    /** Текущая версия установленного приложения. */
    @SuppressWarnings("deprecation")
    @PluginMethod
    public void info(PluginCall call) {
        try {
            Context c = getContext();
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            JSObject o = new JSObject();
            o.put("versionCode", vc);
            o.put("versionName", pi.versionName == null ? "" : pi.versionName);
            call.resolve(o);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Небольшой GET (update.json), с переходами по редиректам GitHub. */
    @PluginMethod
    public void check(final PluginCall call) {
        final String url = call.getString("url", "");
        if (url == null || url.isEmpty()) {
            call.reject("no url");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection h = null;
                try {
                    h = open(url);
                    int code = h.getResponseCode();
                    JSObject o = new JSObject();
                    o.put("status", code);
                    if (code == 200) {
                        InputStream in = h.getInputStream();
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n, total = 0;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            total += n;
                            if (total > 65536) break;
                        }
                        in.close();
                        o.put("body", out.toString("UTF-8"));
                    }
                    call.resolve(o);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                } finally {
                    if (h != null) h.disconnect();
                }
            }
        }).start();
    }

    /** Скачать APK в кэш; прогресс идёт событием updateProgress. */
    @PluginMethod
    public void download(final PluginCall call) {
        final String url = call.getString("url", "");
        final String sha = call.getString("sha256", "");
        final long expect = call.getData().optLong("size", 0);
        if (url == null || url.isEmpty()) {
            call.reject("no url");
            return;
        }
        if (busy) {
            call.reject("busy");
            return;
        }
        busy = true;
        cancelled = false;
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection h = null;
                File part = null;
                try {
                    File out = apkFile();
                    part = new File(out.getParentFile(), "Silence.apk.part");
                    if (part.exists()) part.delete();
                    if (out.exists()) out.delete();
                    h = open(url);
                    int code = h.getResponseCode();
                    if (code != 200) throw new Exception("HTTP " + code);
                    long total = h.getContentLength();
                    if (total <= 0) total = expect;
                    InputStream in = h.getInputStream();
                    FileOutputStream fo = new FileOutputStream(part);
                    MessageDigest md = MessageDigest.getInstance("SHA-256");
                    byte[] buf = new byte[32768];
                    int n;
                    long got = 0, last = 0;
                    while ((n = in.read(buf)) > 0) {
                        if (cancelled) {
                            fo.close();
                            in.close();
                            part.delete();
                            call.reject("cancelled");
                            return;
                        }
                        fo.write(buf, 0, n);
                        md.update(buf, 0, n);
                        got += n;
                        long now = System.currentTimeMillis();
                        if (now - last > 200) {
                            last = now;
                            JSObject p = new JSObject();
                            p.put("loaded", got);
                            p.put("total", total);
                            notifyListeners("updateProgress", p);
                        }
                    }
                    fo.close();
                    in.close();
                    if (total > 0 && got != total) throw new Exception("incomplete");
                    if (sha != null && !sha.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (byte b : md.digest()) sb.append(String.format("%02x", b));
                        if (!sb.toString().equalsIgnoreCase(sha.trim())) throw new Exception("checksum");
                    }
                    if (!part.renameTo(out)) throw new Exception("rename");
                    JSObject o = new JSObject();
                    o.put("ready", true);
                    o.put("size", got);
                    call.resolve(o);
                } catch (Exception e) {
                    if (part != null) part.delete();
                    call.reject(String.valueOf(e.getMessage()));
                } finally {
                    busy = false;
                    if (h != null) h.disconnect();
                }
            }
        }).start();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        cancelled = true;
        call.resolve();
    }

    /** Разрешено ли приложению ставить APK (Android 8+: «Установка неизвестных приложений»). */
    @PluginMethod
    public void canInstall(PluginCall call) {
        boolean ok = true;
        try {
            if (Build.VERSION.SDK_INT >= 26) ok = getContext().getPackageManager().canRequestPackageInstalls();
        } catch (Exception e) { }
        JSObject o = new JSObject();
        o.put("allowed", ok);
        call.resolve(o);
    }

    @PluginMethod
    public void openInstallSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Открыть системную установку скачанного APK (поверх текущей версии). */
    @PluginMethod
    public void install(PluginCall call) {
        try {
            File f = apkFile();
            if (!f.exists() || f.length() == 0) {
                call.reject("no file");
                return;
            }
            Context c = getContext();
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("restart", true)
                    .putLong("t", System.currentTimeMillis())
                    .apply();
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Убрать скачанный APK и пометки об обновлении (при обычном запуске). */
    @PluginMethod
    public void cleanup(PluginCall call) {
        try {
            File d = new File(getContext().getCacheDir(), "update");
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("restart", false).apply();
            ((NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE)).cancel(77);
        } catch (Exception e) { }
        call.resolve();
    }
}
