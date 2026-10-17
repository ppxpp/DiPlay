package com.tencent.wecarflow.carplayguard;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Adapts the original widget service. All wire payloads use its verified Bundle types. */
public final class InstrumentBridge {
    private static final String TAG = "CarPlayInstrument";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int CONNECTED = 1, METADATA = 2, PLAYING = 4;
    private static final LinkedHashMap<IBinder, Client> CLIENTS = new LinkedHashMap<IBinder, Client>();
    private static Context service;
    private static long sequence;
    private static boolean pendingMetadata, pendingPlaying;
    private static Snapshot latest = new Snapshot("", "", "", 0, 0, false);

    private InstrumentBridge() {}

    private static final class Client implements IBinder.DeathRecipient {
        final Messenger messenger;
        final IBinder binder;
        int flags;
        Client(Messenger m) { messenger = m; binder = m.getBinder(); }
        public void binderDied() {
            MAIN.post(new Runnable() { public void run() { remove(binder); } });
        }
    }

    private static final class Snapshot {
        final String title, artist, album;
        final long duration, elapsed, received;
        final boolean playing;
        Snapshot(String t, String a, String al, long d, long e, boolean p) {
            title = clean(t); artist = clean(a); album = clean(al);
            duration = Math.max(0, d); elapsed = duration > 0 ? Math.min(duration, Math.max(0, e)) : Math.max(0, e);
            playing = p; received = SystemClock.elapsedRealtime();
        }
        long position() {
            long value = elapsed;
            if (playing) {
                long delta = Math.max(0, SystemClock.elapsedRealtime() - received);
                value = delta > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + delta;
            }
            return duration > 0 ? Math.min(duration, value) : value;
        }
        boolean hasSong() { return title.length() != 0 || artist.length() != 0; }
        Bundle metadata() {
            Bundle b = new Bundle();
            boolean empty = title.length() == 0 && artist.length() == 0;
            b.putBoolean("media_is_null", empty);
            b.putString("media_type", "song");
            b.putString("media_name", title); b.putString("media_author", artist);
            b.putString("media_group_name", album); b.putString("media_image", "");
            b.putString("media_uuid", "carplay:" + title + ":" + artist + ":" + album);
            // Native metadata duration is seconds; position/progress remain milliseconds.
            b.putLong("media_duration", duration / 1000); b.putLong("media_duration_current", position());
            b.putInt("media_index", -1); b.putInt("media_playmode", -1); b.putInt("key_play_mode", 0);
            b.putBoolean("media_is_favorable", false); b.putBoolean("media_is_favored", false);
            b.putBoolean("media_is_set_playmode", false); b.putBoolean("change_by_play", playing);
            b.putBoolean("is_first", false); b.putBoolean("is_last", false);
            return b;
        }
        Bundle playing() { Bundle b = new Bundle(); b.putBoolean("media_playing", playing); return b; }
        Bundle progress() {
            Bundle b = new Bundle(); b.putLong("media_progress_current", position());
            b.putLong("media_progress_total", duration); b.putString("media_type", "song"); return b;
        }
    }

    private static String clean(String s) { return s == null ? "" : s; }

    public static synchronized void onCreate(Context originalService) {
        service = originalService;
        if (GuardRuntime.isGuarded()) { GuardRuntime.pullRemote(); publishLater(); }
    }
    public static synchronized void onDestroy(Context originalService) {
        if (service != originalService) return;
        service = null; sequence++;
        for (Client c : new ArrayList<Client>(CLIENTS.values())) remove(c.binder);
    }

    public static void beginRemote(Context context) {
        beginRemote(context, GuardRuntime.getRevision());
    }
    public static synchronized void beginRemote(Context context, long revision) {
        if (!GuardRuntime.isGuarded() || revision != GuardRuntime.getRevision()) return;
        latest = new Snapshot("", "", "", 0, 0, false);
        publishLater(revision);
    }
    /** Snapshot retained even if service is not created yet. */
    public static boolean updateRemote(Context context, String title, String artist,
            String album, long duration, long elapsed, boolean playing) {
        return updateRemote(context, title, artist, album, duration, elapsed, playing, GuardRuntime.getRevision());
    }
    public static synchronized boolean updateRemote(Context context, String title, String artist,
            String album, long duration, long elapsed, boolean playing, long revision) {
        if (!GuardRuntime.isGuarded() || revision != GuardRuntime.getRevision()) return false;
        Snapshot next = new Snapshot(title, artist, album, duration, elapsed, playing);
        boolean metadataChanged = !latest.title.equals(next.title) || !latest.artist.equals(next.artist)
                || !latest.album.equals(next.album) || latest.duration != next.duration;
        boolean playingChanged = latest.playing != next.playing;
        latest = next;
        publishLater(revision, metadataChanged, playingChanged);
        return service != null || (GuardRuntime.centralProcess && GuardRuntime.context != null && BridgeBootstrap.isBridgeProcess(GuardRuntime.context));
    }

    private static void publishLater() { publishLater(GuardRuntime.getRevision()); }
    private static void publishLater(final long revision) { publishLater(revision, true, true); }
    private static void publishLater(final long revision, boolean metadata, boolean playing) {
        pendingMetadata |= metadata; pendingPlaying |= playing;
        final long token = ++sequence;
        MAIN.post(new Runnable() {
            public void run() {
                synchronized (InstrumentBridge.class) {
                    if (!current(token, revision)) return;
                    boolean metadata = pendingMetadata, playing = pendingPlaying;
                    pendingMetadata = false; pendingPlaying = false;
                    for (Client c : new ArrayList<Client>(CLIENTS.values())) publish(c, latest, metadata, playing, true);
                    if (latest.playing && latest.hasSong()) tick(token, revision);
                }
            }
        });
    }
    private static boolean current(long token, long revision) {
        return service != null && GuardRuntime.isGuarded() && token == sequence && revision == GuardRuntime.getRevision();
    }
    private static void tick(final long token, final long revision) {
        MAIN.postDelayed(new Runnable() {
            public void run() {
                synchronized (InstrumentBridge.class) {
                    if (!current(token, revision) || !latest.playing || !latest.hasSong()) return;
                    for (Client c : new ArrayList<Client>(CLIENTS.values())) {
                        if ((c.flags & CONNECTED) != 0) send(c, 2006, latest.progress());
                    }
                    tick(token, revision);
                }
            }
        }, 1000);
    }
    private static void publish(Client c, Snapshot s, boolean progress) { publish(c, s, true, true, progress); }
    private static void publish(Client c, Snapshot s, boolean metadata, boolean playing, boolean progress) {
        // One packet per type, even if a binder subscribes using multiple request codes.
        if (metadata && (c.flags & (CONNECTED | METADATA)) != 0) send(c, 2008, s.metadata());
        if (playing && (c.flags & (CONNECTED | PLAYING)) != 0) send(c, 2007, s.playing());
        if (progress && (c.flags & CONNECTED) != 0) send(c, 2006, s.progress());
    }
    private static Client register(Messenger m, int flag) {
        if (m == null) return null;
        IBinder binder = m.getBinder();
        Client c = CLIENTS.get(binder);
        if (c == null) {
            c = new Client(m);
            try { binder.linkToDeath(c, 0); } catch (RemoteException e) { return null; }
            CLIENTS.put(binder, c);
        }
        c.flags |= flag;
        return c;
    }
    private static synchronized void remove(IBinder binder) {
        Client c = CLIENTS.remove(binder);
        if (c != null) c.binder.unlinkToDeath(c, 0);
    }
    private static void send(Client c, int code, Bundle data) {
        try { Message out = Message.obtain(); out.what = code; out.setData(data); c.messenger.send(out); }
        catch (RemoteException e) { remove(c.binder); }
    }
    private static void reply(Messenger m, int code, Bundle data) {
        if (m == null) return;
        try { Message out = Message.obtain(); out.what = code; out.setData(data); m.send(out); }
        catch (RemoteException e) { remove(m.getBinder()); }
    }

    /** Called before native dispatch, including before its user-agreement deferred path. */
    public static boolean handle(Message in) {
        if (in == null) return false;
        if (GuardRuntime.isGuarded()) {
            int command = 0;
            switch (in.what) {
                case 1001: command = 4; break;
                case 1002: command = 5; break;
                case 1003: command = 1; break;
                case 1004: case 1005: command = 2; break;
                default: break;
            }
            if (command != 0) {
                // Do not hold the bridge monitor across an external Binder callback.
                boolean accepted = GuardRuntime.forwardMediaCommand(command);
                if (accepted && in.what != 1005) {
                    String key = in.what == 1001 ? "next_result" : in.what == 1002 ? "pre_result"
                            : in.what == 1003 ? "play_result" : "pause_result";
                    int code = in.what == 1001 ? 4016 : in.what == 1002 ? 4015
                            : in.what == 1003 ? 4018 : 4019;
                    Bundle result = new Bundle(); result.putInt(key, 0);
                    reply(in.replyTo, code, result);
                }
                // Integer zero acknowledges queue acceptance, not iPhone execution.
                // Native STOP has no result. Unknown rejection codes are not invented.
                return true;
            }
        }
        return handleState(in);
    }
    private static synchronized boolean handleState(Message in) {
        Client c = null;
        if (in.what == 3001) c = register(in.replyTo, CONNECTED);
        else if (in.what == 1007) c = register(in.replyTo, METADATA);
        else if (in.what == 1006) c = register(in.replyTo, PLAYING);
        else if (in.what == 3002 && in.replyTo != null) remove(in.replyTo.getBinder());
        if (!GuardRuntime.isGuarded()) return false;
        if (in.what == 3001) {
            if (c != null) publish(c, latest, true);
            // Let original connection bookkeeping and unrelated service behavior run.
            return false;
        }
        if (in.what == 1007) { reply(in.replyTo, 2008, latest.metadata()); return true; }
        if (in.what == 1006) { reply(in.replyTo, 2007, latest.playing()); return true; }
        if (in.what == 1019 || in.what == 1020) {
            Bundle b = new Bundle(); b.putBoolean(in.what == 1019 ? "is_first" : "is_last", false);
            reply(in.replyTo, in.what == 1019 ? 2016 : 2017, b); return true;
        }
        return false;
    }

    /** Replaces only native Messenger.send sites; original exception handling is retained. */
    public static void sendNative(Messenger target, Message out) throws RemoteException {
        if (target == null || out == null) return;
        if (GuardRuntime.isGuarded()) {
            switch (out.what) {
                case 2003: case 2004: case 2005: case 2006: case 2007: case 2008:
                case 2015: case 2016: case 2017: case 2023: case 2051: case 6001:
                    return;
                case 2010: // Keep navigation fields while replacing its embedded local metadata.
                    synchronized (InstrumentBridge.class) { out.getData().putAll(latest.metadata()); }
                    break;
                default: break;
            }
        }
        target.send(out);
    }

    /** Queries paused local state; never starts playback. Rejects restores from older sessions. */
    public static void restoreLocal() { restoreLocal(GuardRuntime.getRevision()); }
    public static synchronized void restoreLocal(final long revision) {
        if (GuardRuntime.isGuarded() || revision != GuardRuntime.getRevision()) return;
        final long token = ++sequence;
        MAIN.post(new Runnable() {
            public void run() {
                synchronized (InstrumentBridge.class) {
                    if (service == null || token != sequence || revision != GuardRuntime.getRevision() || GuardRuntime.isGuarded()) return;
                    for (Client c : new ArrayList<Client>(CLIENTS.values())) {
                        if ((c.flags & (CONNECTED | METADATA)) != 0) queryNative("f", c.messenger);
                        if ((c.flags & (CONNECTED | PLAYING)) != 0) queryNative("o", c.messenger);
                    }
                }
            }
        });
    }
    private static void queryNative(String method, Messenger m) {
        try {
            Method query = service.getClass().getDeclaredMethod(method, Messenger.class);
            query.setAccessible(true); query.invoke(service, m);
        } catch (Exception e) { Log.w(TAG, "Local widget query failed: " + method, e); }
    }
}
