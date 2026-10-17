package com.tencent.wecarflow.carplayguard;

/** Central pause adapter for the verified 2.7.1 manager contract. Keeps shared focus intact. */
public final class GuardPause {
    private GuardPause() {}
    static void pauseCentral(Object manager) throws Exception {
        // d(false) pauses safely but removes MSG_PLAY2 only when byUser=true.
        // Remove the queued play explicitly; d(true) may release shared audio focus.
        java.lang.reflect.Field field = manager.getClass().getDeclaredField("n");
        field.setAccessible(true);
        Object handler = field.get(manager);
        if (handler == null) throw new IllegalStateException("Central playback handler missing");
        handler.getClass().getMethod("removeMessages", int.class).invoke(handler, 2);
        manager.getClass().getMethod("d", boolean.class).invoke(manager, false);
    }
}
