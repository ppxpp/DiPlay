package com.tencent.wecarflow.carplayguard;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GuardRuntime {
    public static final String AUTHORITY = "com.tencent.wecarflow.carplayguard";
    static final String DESCRIPTOR = "com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1";
    static final GuardSession SESSION = new GuardSession();
    static volatile Context context;
    static volatile boolean centralProcess;
    static volatile IBinder mediaCallback;
    private static final PlaybackEpochs playbackEpochs = new PlaybackEpochs();
    private static final ArrayList<WeakReference<Object>> players = new ArrayList<WeakReference<Object>>();
    private GuardRuntime() {}
    public static boolean isGuarded() { return centralProcess ? SESSION.guarded() : shouldBlock(); }
    public static long getRevision() {
        if (centralProcess) return SESSION.revision();
        Bundle state = snapshot(); return state == null ? -1 : state.getLong("revision", -1);
    }
    public static boolean isRevisionCurrent(long revision) { return current(revision); }
    public static boolean forwardMediaCommand(int command) {
        if (!centralProcess) {
            try {
                Bundle args = new Bundle(); args.putInt("command", command);
                Bundle result = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY), "command", null, args);
                return result != null && result.getBoolean("accepted", false);
            } catch (RuntimeException e) { return false; }
        }
        IBinder callback;
        synchronized (SESSION) {
            callback = SESSION.ready() ? mediaCallback : null;
        }
        if (callback == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeInt(command);
            if (!callback.transact(1, data, reply, 0)) return false;
            reply.readException(); return reply.readInt() != 0;
        } catch (Exception e) { Log.e("CarPlayGuard", "Owner command failed", e); return false; }
        finally { data.recycle(); reply.recycle(); }
    }
    public static boolean forwardWheelCommand(int command, String signature) {
        IBinder callback;
        synchronized (SESSION) { callback = SESSION.ready() ? mediaCallback : null; }
        if (callback == null) return false;
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeInt(command); data.writeString(signature);
            if (!callback.transact(2, data, reply, 0)) return false;
            reply.readException(); return reply.readInt() != 0;
        } catch (Exception e) { return false; }
        finally { data.recycle(); reply.recycle(); }
    }
    public static void register(Object player) {
        if (player == null) return;
        synchronized (players) {
            for (int i = players.size() - 1; i >= 0; --i) {
                Object old = players.get(i).get();
                if (old == null) players.remove(i); else if (old == player) return;
            }
            players.add(new WeakReference<Object>(player));
        }
    }
    private static Bundle snapshot() {
        Context ctx = context;
        if (ctx == null) return null;
        try { return ctx.getContentResolver().call(Uri.parse("content://" + AUTHORITY), "status", null, null); }
        catch (RuntimeException e) { Log.e("CarPlayGuard", "Guard status unavailable", e); return null; }
    }
    public static boolean shouldBlock() {
        if (centralProcess) return SESSION.guarded();
        Bundle state = snapshot();
        // A missing provider/context is not permission to start audio.
        return state == null || state.getBoolean("guarded", true);
    }
    /** Stamp a new source or an explicit start accepted after its gate check. Never stamp on pause. */
    public static void markPlayback(Object player) {
        if (centralProcess) {
            synchronized (SESSION) { playbackEpochs.mark(player, SESSION.revision()); }
        } else {
            Bundle state = snapshot();
            playbackEpochs.mark(player, state == null ? -1 : state.getLong("revision", -1));
        }
    }
    /** Prepared callbacks cannot retag an old source after acquire/release changed the session. */
    public static boolean shouldBlockPrepared(Object player) {
        if (centralProcess) {
            synchronized (SESSION) {
                return playbackEpochs.shouldBlockPrepared(player, SESSION.revision(), SESSION.guarded());
            }
        }
        Bundle state = snapshot();
        return state == null || playbackEpochs.shouldBlockPrepared(player,
                state.getLong("revision", -1), state.getBoolean("guarded", true));
    }
    private static boolean current(long revision) {
        if (centralProcess) return SESSION.current(revision);
        Bundle state = snapshot();
        return state != null && state.getBoolean("guarded", false) && state.getLong("revision", -1) == revision;
    }
    private static volatile Bundle remote = new Bundle();
    public static Bundle remoteSnapshot() { return new Bundle(remote); }
    public static void rememberRemote(Bundle value) { remote = new Bundle(value); }
    private static final java.util.concurrent.ConcurrentHashMap<String, IBinder> PEERS = new java.util.concurrent.ConcurrentHashMap<String, IBinder>();
    public static void registerPeer(final String process, final IBinder endpoint) {
        if (process == null || endpoint == null) return;
        if (PEERS.get(process) == endpoint) return;
        try {
            endpoint.linkToDeath(new IBinder.DeathRecipient() { public void binderDied() { PEERS.remove(process, endpoint); } }, 0);
            PEERS.put(process, endpoint);
        } catch (android.os.RemoteException e) { }
    }
    private static boolean peerCall(IBinder peer, int code, Bundle args) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken("com.tencent.wecarflow.carplayguard.Peer.v1"); data.writeBundle(args);
            if (!peer.transact(code, data, reply, 0)) return false;
            reply.readException(); return reply.readInt() != 0;
        } catch (Exception e) { return false; }
        finally { data.recycle(); reply.recycle(); }
    }
    public static void relayMain(String method, Bundle extras) {
        Context ctx = context;
        if (ctx == null || !centralProcess) return;
        IBinder peer = PEERS.get(ctx.getPackageName());
        if (peer == null) return;
        int code = "remote".equals(method) ? 2 : "restore".equals(method) ? 3 : 4;
        peerCall(peer, code, extras); // Existing Binder only: never starts a peer process.
    }
    public static void pullRemote() {
        if (centralProcess || context == null) return;
        try {
            Bundle state = context.getContentResolver().call(Uri.parse("content://" + AUTHORITY), "remoteSnapshot", null, null);
            if (state != null) applyRemote(state);
        } catch (RuntimeException e) { Log.e("CarPlayGuard", "Remote metadata unavailable", e); }
    }
    public static boolean applyRemote(Bundle state) {
        long revision = state.getLong("revision", -1);
        if (!current(revision)) return false;
        return InstrumentBridge.updateRemote(context, state.getString("title", ""), state.getString("artist", ""),
            state.getString("album", ""), state.getLong("duration", 0), state.getLong("elapsed", 0),
            state.getBoolean("playing", false), revision);
    }
    static boolean pauseAllProcesses(Context ctx, long revision) {
        boolean success = pauseHere(revision);
        // A central restart must not acknowledge ready while a live peer has not re-registered.
        ActivityManager am = (ActivityManager)ctx.getSystemService(Context.ACTIVITY_SERVICE);
        for (int attempt = 0; attempt < 20; ++attempt) {
            boolean complete = true;
            List<ActivityManager.RunningAppProcessInfo> infos = am == null ? null : am.getRunningAppProcesses();
            if (infos == null) return false;
            for (ActivityManager.RunningAppProcessInfo info : infos) {
                if (info.uid != android.os.Process.myUid()) continue;
                for (String suffix : GuardConfig.PEER_PROCESSES) {
                    String expected = suffix.length() == 0 || suffix.startsWith(":") ? ctx.getPackageName() + suffix : suffix;
                    if (expected.equals(info.processName) && !PEERS.containsKey(expected)) complete = false;
                }
            }
            if (complete) break;
            if (attempt == 19) return false;
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        Bundle args = new Bundle(); args.putLong("revision", revision);
        for (java.util.Map.Entry<String, IBinder> entry : PEERS.entrySet()) {
            if (!SESSION.current(revision)) return false;
            IBinder endpoint = entry.getValue();
            if (!endpoint.isBinderAlive()) { PEERS.remove(entry.getKey(), endpoint); continue; }
            if (!peerCall(endpoint, 1, args) && endpoint.isBinderAlive()) success = false;
        }
        return success && SESSION.current(revision);
    }
    static boolean pauseHere(final long revision) {
        if (Looper.myLooper() == Looper.getMainLooper()) return current(revision) && pausePlayers();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final boolean[] result = {false};
        Handler handler = new Handler(Looper.getMainLooper());
        Runnable pause = new Runnable() { @Override public void run() {
            try { if (!cancelled.get() && current(revision) && !cancelled.get()) result[0] = pausePlayers(); }
            finally { done.countDown(); }
        }};
        if (!handler.post(pause)) return false;
        try { if (done.await(5, TimeUnit.SECONDS)) return result[0]; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        cancelled.set(true); handler.removeCallbacks(pause); return false;
    }
    private static boolean pausePlayers() {
        // The isolated bridge never constructs the original player just to pause it.
        if (context != null && BridgeBootstrap.isBridgeProcess(context)) return true;
        boolean success = true;
        // Remove queued MSG_PLAY2 explicitly, then pause with d(false) to retain platform focus.
        try {
            Class<?> manager = Class.forName("com.tencent.wecarflow.s0.b.f");
            Object instance = manager.getMethod("E").invoke(null);
            GuardPause.pauseCentral(instance);
        } catch (Exception e) { Log.e("CarPlayGuard", "Central pause failed", e); success = false; }
        ArrayList<Object> live = new ArrayList<Object>();
        synchronized (players) {
            for (WeakReference<Object> ref : players) { Object p = ref.get(); if (p != null) live.add(p); }
        }
        for (Object player : live) {
            try {
                java.lang.reflect.Method isPlaying = player.getClass().getMethod("isPlaying");
                // Exo can be buffering with playWhenReady=true while isPlaying=false.
                boolean playing = ((Boolean) isPlaying.invoke(player)).booleanValue();
                if (playing || player.getClass().getName().equals("com.tencent.wecarflow.s0.c.d.b"))
                    player.getClass().getMethod("pause").invoke(player);
                if (((Boolean) isPlaying.invoke(player)).booleanValue()) success = false;
            } catch (Exception e) { Log.e("CarPlayGuard", "Concrete pause failed: " + player.getClass().getName(), e); success = false; }
        }
        return success;
    }
}
