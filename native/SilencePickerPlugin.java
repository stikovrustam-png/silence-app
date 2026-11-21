package app.silence.messenger;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.MimeTypeMap;
import android.graphics.Bitmap;
import android.util.Size;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import android.content.ContentUris;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Системное окно выбора: фото / видео / музыка / документы.
 * Файл копируется в кэш приложения, в страницу уходит только путь (без base64).
 */
@CapacitorPlugin(
    name = "SilencePicker",
    permissions = {
        @Permission(strings = { Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO }, alias = "media33"),
        @Permission(strings = { Manifest.permission.READ_MEDIA_AUDIO }, alias = "audio33"),
        @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE }, alias = "media32")
    }
)
public class SilencePickerPlugin extends Plugin {

    @PluginMethod
    public void pick(PluginCall call) {
        String kind = call.getString("kind", "file");
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            if ("image".equals(kind)) {
                intent.setType("image/*");
            } else if ("video".equals(kind)) {
                intent.setType("video/*");
            } else if ("audio".equals(kind)) {
                intent.setType("audio/*");
            } else if ("media".equals(kind)) {
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            } else {
                intent.setType("*/*");
            }
            startActivityForResult(call, intent, "pickResult");
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(3);

    private boolean hasMediaAccess() {
        android.content.Context c = getContext();
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                || (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(c, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED);
        }
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Список последних фото/видео из галереи для своего окна выбора. */
    @PluginMethod
    public void gallery(PluginCall call) {
        if (!hasMediaAccess()) {
            requestPermissionForAlias(Build.VERSION.SDK_INT >= 33 ? "media33" : "media32", call, "galPerm");
            return;
        }
        loadGallery(call);
    }

    @PermissionCallback
    private void galPerm(PluginCall call) {
        if (hasMediaAccess()) {
            loadGallery(call);
        } else {
            JSObject r = new JSObject();
            r.put("denied", true);
            call.resolve(r);
        }
    }

    private void loadGallery(final PluginCall call) {
        final String kind = call.getString("kind", "media");
        Integer lim = call.getInt("limit", 600);
        final int limit = lim == null ? 600 : lim;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Cursor c = null;
                try {
                    ContentResolver cr = getContext().getContentResolver();
                    String sel;
                    if ("image".equals(kind)) sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE;
                    else if ("video".equals(kind)) sel = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                    else sel = MediaStore.Files.FileColumns.MEDIA_TYPE + " IN (" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + "," + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";
                    String[] proj = Build.VERSION.SDK_INT >= 29
                        ? new String[]{ MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, "duration" }
                        : new String[]{ MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE };
                    c = cr.query(MediaStore.Files.getContentUri("external"), proj, sel, null, MediaStore.Files.FileColumns.DATE_ADDED + " DESC");
                    JSArray arr = new JSArray();
                    if (c != null) {
                        int ii = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                        int ti = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
                        int di = c.getColumnIndex("duration");
                        while (c.moveToNext() && arr.length() < limit) {
                            JSObject o = new JSObject();
                            o.put("id", c.getLong(ii));
                            o.put("t", c.getInt(ti) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ? "v" : "i");
                            if (di >= 0 && !c.isNull(di)) o.put("dur", c.getLong(di));
                            arr.put(o);
                        }
                    }
                    JSObject r = new JSObject();
                    r.put("items", arr);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.close();
                }
            }
        });
    }

    private Uri itemUri(long id, String t) {
        Uri base = "v".equals(t) ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                 : "a".equals(t) ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                 : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        return ContentUris.withAppendedId(base, id);
    }

    /** Миниатюра в кэш; в страницу уходит только путь. */
    @PluginMethod
    public void thumb(final PluginCall call) {
        final String t = call.getString("t", "i");
        final long id;
        try { id = Long.parseLong(call.getString("id", "")); } catch (Exception e) { call.reject("bad id"); return; }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(getContext().getCacheDir(), "thumbs");
                    dir.mkdirs();
                    File f = new File(dir, t + id + ".jpg");
                    if (!f.exists() || f.length() == 0) {
                        ContentResolver cr = getContext().getContentResolver();
                        Bitmap bm;
                        if (Build.VERSION.SDK_INT >= 29) {
                            bm = cr.loadThumbnail(itemUri(id, t), new Size(360, 360), null);
                        } else if ("v".equals(t)) {
                            bm = MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null);
                        } else {
                            bm = MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null);
                        }
                        if (bm == null) { call.reject("no thumb"); return; }
                        FileOutputStream fo = new FileOutputStream(f);
                        bm.compress(Bitmap.CompressFormat.JPEG, 82, fo);
                        fo.close();
                    }
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    java.io.FileInputStream fi = new java.io.FileInputStream(f);
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
                    fi.close();
                    JSObject r = new JSObject();
                    r.put("data", android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP));
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    /** Копирует выбранный в галерее файл в кэш и отдаёт путь. */
    @PluginMethod
    public void copy(final PluginCall call) {
        String t = call.getString("t", "i");
        long id;
        try { id = Long.parseLong(call.getString("id", "")); } catch (Exception e) { call.reject("bad id"); return; }
        Double mx = call.getDouble("max", 120.0);
        final double maxMb = mx == null ? 120.0 : mx;
        final Uri uri = itemUri(id, t);
        new Thread(new Runnable() {
            @Override
            public void run() { copyUri(call, uri, maxMb); }
        }).start();
    }

    private boolean granted(String perm) {
        return ContextCompat.checkSelfPermission(getContext(), perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAccess(String kind) {
        if ("files".equals(kind)) {
            if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
            return granted(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if ("audio".equals(kind)) {
            if (Build.VERSION.SDK_INT >= 33) return granted(Manifest.permission.READ_MEDIA_AUDIO);
            return granted(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        return hasMediaAccess();
    }

    /** Есть ли доступ (без показа системных окон). */
    @PluginMethod
    public void access(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", hasAccess(call.getString("kind", "media")));
        call.resolve(r);
    }

    /** Запрос доступа: фото/видео и музыка — системное окно разрешения, файлы — страница настроек. */
    @PluginMethod
    public void request(PluginCall call) {
        String kind = call.getString("kind", "media");
        if (hasAccess(kind)) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
            return;
        }
        if ("files".equals(kind) && Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception e) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                } catch (Exception ignored) { }
            }
            JSObject r = new JSObject();
            r.put("granted", false);
            r.put("settings", true);
            call.resolve(r);
            return;
        }
        String alias;
        if ("audio".equals(kind)) alias = Build.VERSION.SDK_INT >= 33 ? "audio33" : "media32";
        else if ("files".equals(kind)) alias = "media32";
        else alias = Build.VERSION.SDK_INT >= 33 ? "media33" : "media32";
        requestPermissionForAlias(alias, call, "permDone");
    }

    @PermissionCallback
    private void permDone(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", hasAccess(call.getString("kind", "media")));
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }

    /** Список музыки для своего окна выбора. */
    @PluginMethod
    public void audio(final PluginCall call) {
        if (!hasAccess("audio")) {
            JSObject r = new JSObject();
            r.put("denied", true);
            call.resolve(r);
            return;
        }
        Integer lim = call.getInt("limit", 3000);
        final int limit = lim == null ? 3000 : lim;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                Cursor c = null;
                try {
                    ContentResolver cr = getContext().getContentResolver();
                    String[] proj = new String[]{
                        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                        MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME
                    };
                    String sel = MediaStore.Audio.Media.IS_NOTIFICATION + "=0 AND " + MediaStore.Audio.Media.IS_RINGTONE + "=0 AND " + MediaStore.Audio.Media.IS_ALARM + "=0";
                    c = cr.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, sel, null, MediaStore.Audio.Media.DATE_ADDED + " DESC");
                    JSArray arr = new JSArray();
                    if (c != null) {
                        while (c.moveToNext() && arr.length() < limit) {
                            JSObject o = new JSObject();
                            o.put("id", c.getLong(0));
                            o.put("title", c.getString(1));
                            o.put("artist", c.getString(2));
                            o.put("dur", c.getLong(3));
                            o.put("name", c.getString(4));
                            arr.put(o);
                        }
                    }
                    JSObject r = new JSObject();
                    r.put("items", arr);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                } finally {
                    if (c != null) c.close();
                }
            }
        });
    }

    /** Простой файловый обзор для документов (нужен «доступ ко всем файлам»). */
    @PluginMethod
    public void browse(final PluginCall call) {
        if (!hasAccess("files")) {
            JSObject r = new JSObject();
            r.put("denied", true);
            call.resolve(r);
            return;
        }
        final String path = call.getString("path", "");
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File root = Environment.getExternalStorageDirectory();
                    File dir = (path == null || path.isEmpty()) ? root : new File(path);
                    String rc = root.getCanonicalPath();
                    String dc = dir.getCanonicalPath();
                    if (!dc.equals(rc) && !dc.startsWith(rc + "/")) { dir = root; dc = rc; }
                    File[] list = dir.listFiles();
                    JSArray arr = new JSArray();
                    if (list != null) {
                        for (File f : list) {
                            String n = f.getName();
                            if (n.startsWith(".")) continue;
                            JSObject o = new JSObject();
                            o.put("n", n);
                            o.put("d", f.isDirectory());
                            o.put("s", f.isDirectory() ? 0 : f.length());
                            o.put("m", f.lastModified());
                            arr.put(o);
                            if (arr.length() >= 3000) break;
                        }
                    }
                    JSObject r = new JSObject();
                    r.put("path", dc);
                    r.put("root", rc);
                    r.put("items", arr);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    /** Копирует выбранный в обзоре файл в кэш. */
    @PluginMethod
    public void copyFile(final PluginCall call) {
        final String path = call.getString("path", "");
        Double mx = call.getDouble("max", 120.0);
        final double maxMb = mx == null ? 120.0 : mx;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File root = Environment.getExternalStorageDirectory();
                    File f = new File(path);
                    String rc = root.getCanonicalPath();
                    String fc = f.getCanonicalPath();
                    if (!fc.startsWith(rc + "/") || !f.isFile()) { call.reject("bad path"); return; }
                    String name = f.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
                    long size = f.length();
                    if (size > maxMb * 1048576.0) {
                        JSObject r = new JSObject();
                        r.put("tooLarge", true);
                        r.put("name", name);
                        r.put("size", size);
                        call.resolve(r);
                        return;
                    }
                    File dir = new File(getContext().getCacheDir(), "pick");
                    dir.mkdirs();
                    File[] old = dir.listFiles();
                    if (old != null) { for (File o : old) { try { o.delete(); } catch (Exception ignored) { } } }
                    File out = new File(dir, name);
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] buf = new byte[65536];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) { fo.write(buf, 0, n); total += n; }
                    fo.close();
                    in.close();
                    String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(f).toString());
                    String mime = ext == null ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                    JSObject r = new JSObject();
                    r.put("path", out.getAbsolutePath());
                    r.put("name", name);
                    r.put("mime", mime == null ? "" : mime);
                    r.put("size", total);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        }).start();
    }

    @ActivityCallback
    private void pickResult(final PluginCall call, ActivityResult result) {
        if (call == null) return;
        Intent data = result == null ? null : result.getData();
        if (result == null || result.getResultCode() != Activity.RESULT_OK || data == null || data.getData() == null) {
            JSObject r = new JSObject();
            r.put("cancelled", true);
            call.resolve(r);
            return;
        }
        final Uri uri = data.getData();
        Double mx = call.getDouble("max", 120.0);
        final double maxMb = mx == null ? 120.0 : mx;
        new Thread(new Runnable() {
            @Override
            public void run() { copyUri(call, uri, maxMb); }
        }).start();
    }

    private void copyUri(final PluginCall call, final Uri uri, final double maxMb) {
        try {
            ContentResolver cr = getContext().getContentResolver();
            String name = null;
            long size = -1;
            Cursor c = null;
            try {
                c = cr.query(uri, null, null, null, null);
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            String mime = cr.getType(uri);
            if (name == null || name.isEmpty()) name = "file";
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");

            if (size > 0 && size > maxMb * 1048576.0) {
                JSObject r = new JSObject();
                r.put("tooLarge", true);
                r.put("name", name);
                r.put("size", size);
                call.resolve(r);
                return;
            }

            File dir = new File(getContext().getCacheDir(), "pick");
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) {
                for (File f : old) {
                    try { f.delete(); } catch (Exception ignored) { }
                }
            }
            File out = new File(dir, name);
            InputStream in = cr.openInputStream(uri);
            if (in == null) {
                call.reject("cannot open file");
                return;
            }
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                fo.write(buf, 0, n);
                total += n;
            }
            fo.close();
            in.close();

            JSObject r = new JSObject();
            r.put("path", out.getAbsolutePath());
            r.put("name", name);
            r.put("mime", mime == null ? "" : mime);
            r.put("size", total);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(String.valueOf(e.getMessage()));
        }
    }
}
