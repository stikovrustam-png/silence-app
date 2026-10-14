package app.silence.messenger;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
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
        @Permission(strings = { Manifest.permission.READ_EXTERNAL_STORAGE }, alias = "media32")
    }
)
public class SilencePickerPlugin extends Plugin {

    @PluginMethod
    public void pick(PluginCall call) {
        String kind = call.getString("kind", "file");
        try {
            Intent intent;
            boolean media = "image".equals(kind) || "video".equals(kind) || "media".equals(kind);
            if (media && Build.VERSION.SDK_INT >= 33) {
                intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
                if ("image".equals(kind)) intent.setType("image/*");
                else if ("video".equals(kind)) intent.setType("video/*");
            } else {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
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
        Uri base = "v".equals(t) ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        return ContentUris.withAppendedId(base, id);
    }

    /** Миниатюра в кэш; в страницу уходит только путь. */
    @PluginMethod
    public void thumb(final PluginCall call) {
        final Long idObj = call.getLong("id");
        final String t = call.getString("t", "i");
        if (idObj == null) { call.reject("no id"); return; }
        final long id = idObj;
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
                            bm = cr.loadThumbnail(itemUri(id, t), new Size(256, 256), null);
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
                    JSObject r = new JSObject();
                    r.put("path", f.getAbsolutePath());
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
        Long idObj = call.getLong("id");
        String t = call.getString("t", "i");
        if (idObj == null) { call.reject("no id"); return; }
        Double mx = call.getDouble("max", 120.0);
        final double maxMb = mx == null ? 120.0 : mx;
        final Uri uri = itemUri(idObj, t);
        new Thread(new Runnable() {
            @Override
            public void run() { copyUri(call, uri, maxMb); }
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
