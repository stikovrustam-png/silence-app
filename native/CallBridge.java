package app.silence.messenger;

/** Общее состояние звонка между службой, уведомлениями и плагином. */
final class CallBridge {
    static volatile boolean active = false;
    static volatile String pending = null;
    static volatile long pendingId = 0;
    static volatile SilenceNotifyPlugin plugin = null;

    static void post(String action, long id) {
        pending = action;
        pendingId = id;
        SilenceNotifyPlugin p = plugin;
        if (p != null) { try { p.fireCallAction(action, id); } catch (Exception e) { } }
    }
}
