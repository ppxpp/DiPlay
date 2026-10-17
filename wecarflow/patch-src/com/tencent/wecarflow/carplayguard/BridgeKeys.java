package com.tencent.wecarflow.carplayguard;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.reflect.*;
/** Registers only PAL previous/next input under WeCarFlow's identity; never creates a player. */
public final class BridgeKeys {
    private static boolean started;
    public static volatile String state = "idle";
    private static Object serviceCallback, keyCallback, cabin;
    private BridgeKeys() {}
    public static synchronized void start(final Context context) {
        if (started) return;
        started = true; state = "connecting";
        new Handler(Looper.getMainLooper()).post(new Runnable() { public void run() {
            try {
                final ClassLoader loader = context.getClassLoader();
                final Class<?> manager = loader.loadClass("com.tencent.tai.pal.client.PALServiceManager");
                Class<?> callback = loader.loadClass("com.tencent.tai.pal.client.PALServiceManager$d");
                serviceCallback = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, new InvocationHandler() {
                    public Object invoke(Object p, Method m, Object[] a) throws Exception {
                        if (m.getName().equals("onConnected")) register(loader, manager);
                        if (m.getName().equals("onDisconnected")) { cabin = null; state = "disconnected"; Log.i("CarPlayBridge", "PAL disconnected"); }
                        return objectMethod(p, m, a);
                    }
                });
                manager.getMethod("a", Context.class, callback).invoke(null, context.getApplicationContext(), serviceCallback);
                Log.i("CarPlayBridge", "PAL connection requested; original player not initialized");
            } catch (Exception e) { started = false; state = "failed:" + e.getClass().getSimpleName(); Log.e("CarPlayBridge", "PAL initialization failed", e); }
        }});
    }
    private static synchronized void register(final ClassLoader loader, Class<?> manager) throws Exception {
        Class<?> type = loader.loadClass("com.tencent.tai.pal.client.c");
        Object next = manager.getMethod("a", Class.class).invoke(null, type);
        if (next == null || next == cabin) return;
        Class<?> listener = loader.loadClass("com.tencent.tai.pal.client.c$a");
        keyCallback = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, new InvocationHandler() {
            public Object invoke(Object p, Method m, Object[] a) throws Exception {
                if (m.getName().equals("i")) {
                    Class<?> filterType = loader.loadClass("com.tencent.tai.pal.input.KeyFilter");
                    Object filter = filterType.getConstructor().newInstance();
                    filterType.getMethod("addFilter", int.class).invoke(filter, 8);
                    filterType.getMethod("addFilter", int.class).invoke(filter, 9);
                    return filter;
                }
                if (m.getName().equals("b") && a != null && a.length > 0 && a[0] != null) {
                    Object event = a[0]; Class<?> t = event.getClass();
                    int key = ((Integer)t.getMethod("getKeyCode").invoke(event)).intValue();
                    int action = ((Integer)t.getMethod("getAction").invoke(event)).intValue();
                    if (action == 1 && (key == 8 || key == 9)) {
                        String signature = key + ":" + t.getMethod("getSource").invoke(event) + ":" +
                            t.getMethod("getDownTime").invoke(event) + ":" + t.getMethod("getEventTime").invoke(event);
                        boolean accepted = GuardRuntime.forwardWheelCommand(key == 8 ? 5 : 4, signature);
                        Log.i("CarPlayBridge", "PAL key=" + key + " action=" + action + " accepted=" + accepted);
                    }
                }
                return objectMethod(p, m, a);
            }
        });
        type.getMethod("a", listener).invoke(next, keyCallback); cabin = next; state = "registered";
        Log.i("CarPlayBridge", "PAL previous/next registered; bridge-only process");
    }
    private static Object objectMethod(Object p, Method m, Object[] a) {
        if (m.getName().equals("toString")) return "WeCarFlowDiPlayBridge";
        if (m.getName().equals("hashCode")) return System.identityHashCode(p);
        if (m.getName().equals("equals")) return a != null && a.length > 0 && p == a[0];
        return null;
    }
}
