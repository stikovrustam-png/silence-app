package app.silence.messenger;

import android.app.Activity;
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
@CapacitorPlugin(name = "SilencePicker")
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
            public void run() {
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
        }).start();
    }
}
