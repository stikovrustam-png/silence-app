package app.silence.messenger;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Папка Download/Silence Cache: всё загруженное из чатов хранится здесь
 * и больше не скачивается, пока пользователь не очистит кэш.
 * Android 10+ — через MediaStore (без лишних разрешений), ниже — обычные файлы.
 */
@CapacitorPlugin(name = "SilenceCache")
public class SilenceCachePlugin extends Plugin {

    private static final String DIR = "Silence Cache";
    private static final String REL = Environment.DIRECTORY_DOWNLOADS + "/" + DIR;

    private static class Slot {
        OutputStream os;
        Uri uri;
        File file;
        String name;
    }

    private final Map<String, Slot> slots = new ConcurrentHashMap<String, Slot>();
    private final ExecutorService ex = Executors.newSingleThreadExecutor();

    private boolean scoped() {
        return Build.VERSION.SDK_INT >= 29;
    }

    private File publicDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR);
        try { d.mkdirs(); } catch (Exception e) { }
        return d;
    }

    private String clean(String n) {
        if (n == null) n = "file";
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (n.length() > 120) n = n.substring(n.length() - 120);
        if (n.isEmpty()) n = "file";
        return n;
    }

    /** Uri файла нашего приложения в папке кэша по имени (или null). */
    private Uri findUri(String name) {
        Cursor c = null;
        try {
            ContentResolver cr = getContext().getContentResolver();
            c = cr.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{ MediaStore.MediaColumns._ID },
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                new String[]{ name, REL + "%" }, null);
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(0);
                return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
            }
        } catch (Exception e) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private String nameOf(Uri u) {
        Cursor c = null;
        try {
            c = getContext().getContentResolver().query(u, new String[]{ MediaStore.MediaColumns.DISPLAY_NAME }, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    @PluginMethod
    public void open(final PluginCall call) {
        final String name = clean(call.getString("name", "file"));
        final String mime = call.getString("mime", "application/octet-stream");
        ex.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Slot s = new Slot();
                    s.name = name;
                    if (scoped()) {
                        Uri old = findUri(name);
                        if (old != null) {
                            try { getContext().getContentResolver().delete(old, null, null); } catch (Exception e) { }
                        }
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime);
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, REL);
                        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                        s.uri = getContext().getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (s.uri == null) { call.reject("insert failed"); return; }
                        s.os = getContext().getContentResolver().openOutputStream(s.uri);
                        try { publicDir(); } catch (Exception e) { }
                    } else {
                        s.file = new File(publicDir(), name);
                        s.os = new FileOutputStream(s.file);
                    }
                    if (s.os == null) { call.reject("no stream"); return; }
                    String token = java.util.UUID.randomUUID().toString();
                    slots.put(token, s);
                    JSObject r = new JSObject();
                    r.put("token", token);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    @PluginMethod
    public void append(final PluginCall call) {
        final String token = call.getString("token", "");
        final String data = call.getString("data", "");
        ex.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Slot s = slots.get(token);
                    if (s == null) { call.reject("no slot"); return; }
                    byte[] b = Base64.decode(data, Base64.DEFAULT);
                    s.os.write(b);
                    call.resolve();
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    @PluginMethod
    public void finish(final PluginCall call) {
        final String token = call.getString("token", "");
        ex.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Slot s = slots.remove(token);
                    if (s == null) { call.reject("no slot"); return; }
                    s.os.flush();
                    s.os.close();
                    String fin = s.name;
                    if (s.uri != null) {
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        getContext().getContentResolver().update(s.uri, v, null, null);
                        String n = nameOf(s.uri);
                        if (n != null) fin = n;
                    }
                    JSObject r = new JSObject();
                    r.put("name", fin);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    @PluginMethod
    public void abort(final PluginCall call) {
        final String token = call.getString("token", "");
        ex.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Slot s = slots.remove(token);
                    if (s != null) {
                        try { s.os.close(); } catch (Exception e) { }
                        if (s.uri != null) getContext().getContentResolver().delete(s.uri, null, null);
                        if (s.file != null) s.file.delete();
                    }
                } catch (Exception e) { }
                call.resolve();
            }
        });
    }

    /** Копирует файл из папки кэша во временный (для чтения страницей) и отдаёт путь. */
    @PluginMethod
    public void read(final PluginCall call) {
        final String name = clean(call.getString("name", ""));
        ex.execute(new Runnable() {
            @Override
            public void run() {
                InputStream in = null;
                OutputStream out = null;
                try {
                    if (scoped()) {
                        Uri u = findUri(name);
                        if (u == null) { call.reject("not found"); return; }
                        in = getContext().getContentResolver().openInputStream(u);
                    } else {
                        File f = new File(publicDir(), name);
                        if (!f.exists()) { call.reject("not found"); return; }
                        in = new FileInputStream(f);
                    }
                    if (in == null) { call.reject("no stream"); return; }
                    File tmpDir = new File(getContext().getCacheDir(), "sc");
                    tmpDir.mkdirs();
                    File t = new File(tmpDir, java.util.UUID.randomUUID().toString().substring(0, 8) + "_" + name);
                    out = new FileOutputStream(t);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.flush();
                    JSObject r = new JSObject();
                    r.put("path", t.getAbsolutePath());
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                } finally {
                    try { if (in != null) in.close(); } catch (Exception e) { }
                    try { if (out != null) out.close(); } catch (Exception e) { }
                }
            }
        });
    }

    /** Удаляет временную копию, сделанную read(). */
    @PluginMethod
    public void release(PluginCall call) {
        try {
            String p = call.getString("path", "");
            File f = new File(p);
            File root = new File(getContext().getCacheDir(), "sc");
            if (f.getAbsolutePath().startsWith(root.getAbsolutePath())) f.delete();
        } catch (Exception e) { }
        call.resolve();
    }

    private List<Object[]> listAll() {
        List<Object[]> res = new ArrayList<Object[]>();
        if (scoped()) {
            Cursor c = null;
            try {
                c = getContext().getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{ MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE },
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?", new String[]{ REL + "%" }, null);
                while (c != null && c.moveToNext()) {
                    res.add(new Object[]{ c.getLong(0), c.getString(1), c.getLong(2) });
                }
            } catch (Exception e) {
            } finally {
                if (c != null) c.close();
            }
        } else {
            File[] fs = publicDir().listFiles();
            if (fs != null) for (File f : fs) if (f.isFile()) res.add(new Object[]{ 0L, f.getName(), f.length() });
        }
        return res;
    }

    @PluginMethod
    public void list(final PluginCall call) {
        ex.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    try { publicDir(); } catch (Exception e) { }
                    JSArray arr = new JSArray();
                    long total = 0;
                    for (Object[] o : listAll()) {
                        JSObject f = new JSObject();
                        f.put("n", (String) o[1]);
                        f.put("s", (Long) o[2]);
                        total += (Long) o[2];
                        arr.put(f);
                    }
                    JSObject r = new JSObject();
                    r.put("files", arr);
                    r.put("size", total);
                    r.put("path", REL);
                    call.resolve(r);
                } catch (Exception e) {
                    call.reject(String.valueOf(e.getMessage()));
                }
            }
        });
    }

    @PluginMethod
    public void remove(final PluginCall call) {
        final JSArray names = call.getArray("names");
        ex.execute(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                try {
                    if (names != null) {
                        for (int i = 0; i < names.length(); i++) {
                            String name = clean(names.getString(i));
                            if (scoped()) {
                                Uri u = findUri(name);
                                if (u != null && getContext().getContentResolver().delete(u, null, null) > 0) n++;
                            } else {
                                if (new File(publicDir(), name).delete()) n++;
                            }
                        }
                    }
                } catch (Exception e) { }
                JSObject r = new JSObject();
                r.put("count", n);
                call.resolve(r);
            }
        });
    }

    @PluginMethod
    public void clear(final PluginCall call) {
        ex.execute(new Runnable() {
            @Override
            public void run() {
                int n = 0;
                try {
                    for (Object[] o : listAll()) {
                        try {
                            if (scoped()) {
                                Uri u = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, (Long) o[0]);
                                if (getContext().getContentResolver().delete(u, null, null) > 0) n++;
                            } else {
                                if (new File(publicDir(), (String) o[1]).delete()) n++;
                            }
                        } catch (Exception e) { }
                    }
                    File tmp = new File(getContext().getCacheDir(), "sc");
                    File[] ts = tmp.listFiles();
                    if (ts != null) for (File f : ts) f.delete();
                } catch (Exception e) { }
                JSObject r = new JSObject();
                r.put("count", n);
                call.resolve(r);
            }
        });
    }
}
