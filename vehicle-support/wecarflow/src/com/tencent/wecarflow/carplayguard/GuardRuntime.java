package com.tencent.wecarflow.carplayguard;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/** Memory-only state lives in the main-process provider, never in a persisted preference. */
public final class GuardRuntime {
    public static final String AUTHORITY = "com.tencent.wecarflow.carplayguard";
    static volatile boolean guarded;
    static volatile Context context;
    static volatile boolean mainProcess;
    private static final ArrayList<WeakReference<Object>> players = new ArrayList<>();
    static volatile IBinder ownerCallback;
    private GuardRuntime() {}
    public static boolean isGuarded() { return guarded; }
    public static boolean forwardMediaCommand(int command) {
        IBinder callback = ownerCallback;
        if (!guarded || callback == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1");
            data.writeInt(command);
            if (!callback.transact(1, data, reply, 0)) return false;
            reply.readException();
            return reply.readInt() != 0;
        } catch (Exception e) { Log.e("CarPlayGuard", "Owner command failed", e); return false; }
        finally { data.recycle(); reply.recycle(); }
    }
    static boolean publishRemote(Context ctx, String title, String artist, String album, long duration, long elapsed, boolean playing) {
        if (!GuardConfig.BRIDGE_AVAILABLE) return false;
        try {
            Class<?> bridge = Class.forName("com.tencent.wecarflow.launcherwidget.UpdateWidgetService");
            return ((Boolean) bridge.getMethod("updateRemote", Context.class, String.class, String.class, String.class, long.class, long.class, boolean.class)
                    .invoke(null, ctx, title, artist, album, duration, elapsed, playing)).booleanValue();
        } catch (Exception e) { Log.e("CarPlayGuard", "Remote bridge unavailable", e); return false; }
    }
    static void restoreLocal() {
        if (!GuardConfig.BRIDGE_AVAILABLE) return;
        try { Class.forName("com.tencent.wecarflow.launcherwidget.UpdateWidgetService").getMethod("restoreLocal").invoke(null); }
        catch (Exception e) { Log.e("CarPlayGuard", "Local display restore failed", e); }
    }

    public static void register(Object player) {
        synchronized (players) {
            for (int i = players.size() - 1; i >= 0; i--) {
                Object current = players.get(i).get();
                if (current == null) players.remove(i);
                else if (current == player) return;
            }
            players.add(new WeakReference<>(player));
        }
    }

    /** Called at command/start boundaries; other processes read the main provider synchronously. */
    public static boolean shouldBlock() {
        if (mainProcess) return guarded;
        Context ctx = context;
        if (ctx == null) {
            Log.e("CarPlayGuard", "No process provider context; refusing playback");
            return true;
        }
        try {
            Bundle result = ctx.getContentResolver().call(Uri.parse("content://" + AUTHORITY), "status", null, null);
            return result == null || result.getBoolean("guarded", true);
        } catch (RuntimeException e) {
            Log.e("CarPlayGuard", "Guard snapshot unavailable; refusing playback", e);
            return true;
        }
    }

    static boolean pauseAllProcesses(Context ctx) {
        if (!pauseHere()) return false;
        android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService("activity");
        java.util.HashSet<String> running = new java.util.HashSet<>();
        if (am == null || am.getRunningAppProcesses() == null) return false;
        for (android.app.ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses())
            if (info.uid == android.os.Process.myUid()) running.add(info.processName);
        for (int i = 0; i < GuardConfig.PEER_AUTHORITIES.length; i++) {
            String process = GuardConfig.PEER_PROCESSES[i];
            if (process.startsWith(":")) process = ctx.getPackageName() + process;
            if (!running.contains(process)) continue;
            String authority = GuardConfig.PEER_AUTHORITIES[i];
            try {
                Bundle result = ctx.getContentResolver().call(Uri.parse("content://" + authority), "pause", null, null);
                if (result == null || !result.getBoolean("paused", false)) return false;
            } catch (RuntimeException e) {
                Log.e("CarPlayGuard", "Peer pause failed: " + authority, e);
                return false;
            }
        }
        return true;
    }

    static boolean pauseHere() {
        if (Looper.myLooper() == Looper.getMainLooper()) return shouldBlock() && pausePlayers();
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] result = {false};
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable pause = new Runnable() {
            @Override public void run() {
                try {
                    // A queued task must not pause native audio after its lease was cleared.
                    // Peer processes read the authoritative main-process state through shouldBlock().
                    if (!cancelled.get() && shouldBlock() && !cancelled.get()) result[0] = pausePlayers();
                } finally { done.countDown(); }
            }
        };
        if (!handler.post(pause)) return false;
        try {
            if (done.await(5, TimeUnit.SECONDS)) return result[0];
            cancelled.set(true);
            handler.removeCallbacks(pause);
            return false;
        } catch (InterruptedException e) {
            cancelled.set(true);
            handler.removeCallbacks(pause);
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean pausePlayers() {
        ArrayList<Object> current = new ArrayList<>();
        synchronized (players) {
            for (WeakReference<Object> ref : players) if (ref.get() != null) current.add(ref.get());
        }
        boolean success = true;
        for (Object player : current) {
            try {
                String name = player.getClass().getName();
                if (name.equals("com.tencent.qqmusicsdk.player.playermanager.AudioPlayerManager")) {
                    player.getClass().getMethod("v0", boolean.class).invoke(player, false);
                } else if (name.equals("com.tencent.qqmusic.mediaplayer.system.AndroidMediaPlayer")) {
                    player.getClass().getMethod("p").invoke(player);
                } else {
                    // Pause the real platform/ThumbPlayer synchronously, rather than an async lambda.
                    String getter = name.endsWith("EdgeThumbPlayer") ? "m" : "l";
                    Object raw = player.getClass().getMethod(getter, player.getClass()).invoke(null, player);
                    if (raw instanceof android.media.MediaPlayer) {
                        android.media.MediaPlayer mp = (android.media.MediaPlayer) raw;
                        if (mp.isPlaying()) mp.pause();
                    } else if (raw != null) {
                        Class<?> contract = Class.forName("com.tencent.thumbplayer.api.player.ITPPlayer");
                        int state = ((Integer) contract.getMethod("getCurrentState").invoke(raw)).intValue();
                        if (state == 4) contract.getMethod("pause").invoke(raw);
                    }
                }
            } catch (Exception e) {
                Log.e("CarPlayGuard", "Pause failed for " + player.getClass().getName(), e);
                success = false;
            }
        }
        return success;
    }
}
