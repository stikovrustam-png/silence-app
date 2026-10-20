package app.silence.messenger;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/** Размеры системных панелей (в css-пикселях): приложение рисуется на весь экран, а отступы берёт отсюда. */
@CapacitorPlugin(name = "SilenceInsets")
public class SilenceInsetsPlugin extends Plugin {
    static volatile int top = 0, bottom = 0;
    static volatile SilenceInsetsPlugin inst;

    @Override
    public void load() {
        inst = this;
    }

    @PluginMethod
    public void getInsets(PluginCall call) {
        JSObject o = new JSObject();
        o.put("top", top);
        o.put("bottom", bottom);
        call.resolve(o);
    }

    static void push(int t, int b) {
        top = t;
        bottom = b;
        SilenceInsetsPlugin p = inst;
        if (p != null) {
            JSObject o = new JSObject();
            o.put("top", t);
            o.put("bottom", b);
            p.notifyListeners("insets", o, true);
        }
    }
}
