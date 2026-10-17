package com.tencent.wecarflow.carplayguard;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Existing raw Binder v1 wire format; never hold the lifecycle lock during pause or remote work. */
public final class PlaybackGuardService extends Service {
    private IBinder owner;
    private IBinder.DeathRecipient death;
    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(GuardRuntime.DESCRIPTOR); return true; }
            if (code == 5) {
                data.enforceInterface(GuardRuntime.DESCRIPTOR);
                android.os.Bundle state = new android.os.Bundle();
                state.putBoolean("guarded", GuardRuntime.SESSION.guarded());
                state.putBoolean("ready", GuardRuntime.SESSION.ready());
                state.putString("keys", BridgeKeys.state);
                state.putBoolean("bridgeOnly", BridgeBootstrap.isBridgeProcess(PlaybackGuardService.this));
                android.app.ActivityManager manager = (android.app.ActivityManager)getSystemService(ACTIVITY_SERVICE);
                java.util.ArrayList<String> processes = new java.util.ArrayList<String>();
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> infos = manager.getRunningAppProcesses();
                if (infos != null) for (android.app.ActivityManager.RunningAppProcessInfo info : infos)
                    if (info.uid == android.os.Process.myUid()) processes.add(info.processName);
                state.putStringArrayList("processes", processes);
                reply.writeNoException(); reply.writeBundle(state); return true;
            }
            if (code < 1 || code > 4) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(GuardRuntime.DESCRIPTOR);
            boolean result;
            if (code == 1) result = acquire(data.readStrongBinder());
            else if (code == 2) result = release(data.readStrongBinder());
            else if (code == 3) result = GuardRuntime.SESSION.ready();
            else {
                IBinder target = data.readStrongBinder();
                String title = data.readString(), artist = data.readString(), album = data.readString();
                long duration = data.readLong(), elapsed = data.readLong(); boolean playing = data.readInt() != 0;
                long revision;
                synchronized (GuardRuntime.SESSION) {
                    revision = GuardRuntime.SESSION.ready() && GuardRuntime.SESSION.owns(target)
                            ? GuardRuntime.getRevision() : -1;
                }
                long identity = Binder.clearCallingIdentity();
                try {
                    if (revision >= 0 && GuardRuntime.isRevisionCurrent(revision)) {
                        android.os.Bundle state = new android.os.Bundle(); state.putLong("revision", revision);
                        state.putString("title", title); state.putString("artist", artist); state.putString("album", album);
                        state.putLong("duration", duration); state.putLong("elapsed", elapsed); state.putBoolean("playing", playing);
                        GuardRuntime.rememberRemote(state); GuardRuntime.relayMain("remote", state);
                    }
                    result = revision >= 0 && GuardRuntime.isRevisionCurrent(revision)
                            && InstrumentBridge.updateRemote(PlaybackGuardService.this, title, artist, album,
                                    Math.max(0, duration), Math.max(0, elapsed), playing, revision)
                            && GuardRuntime.isRevisionCurrent(revision);
                } finally { Binder.restoreCallingIdentity(identity); }
            }
            reply.writeNoException(); reply.writeInt(result ? 1 : 0); return true;
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        BridgeKeys.start(this);
        android.util.Log.i("CarPlayBridge", "Guard service started in isolated process; player initialization skipped");
    }
    @Override public IBinder onBind(Intent intent) { return endpoint; }
    private boolean acquire(final IBinder target) {
        if (target == null) return false;
        final long revision;
        boolean linked = true;
        long clearedRevision = -1;
        synchronized (GuardRuntime.SESSION) {
            unlink();
            revision = GuardRuntime.SESSION.acquire(target); // Gate closes before pause.
            owner = target; GuardRuntime.mediaCallback = target;
            death = new IBinder.DeathRecipient() { @Override public void binderDied() { releaseDeath(revision); }};
            try { target.linkToDeath(death, 0); }
            catch (RemoteException e) {
                GuardRuntime.SESSION.died(revision); owner = null; death = null; GuardRuntime.mediaCallback = null;
                linked = false; clearedRevision = GuardRuntime.getRevision();
            }
        }
        if (!linked) { restore(clearedRevision); GuardNotice.guardChanged(); return false; }
        long identity = Binder.clearCallingIdentity();
        boolean paused;
        try {
            if (GuardRuntime.isRevisionCurrent(revision)) InstrumentBridge.beginRemote(this, revision);
            paused = GuardRuntime.pauseAllProcesses(this, revision);
        } finally { Binder.restoreCallingIdentity(identity); }
        synchronized (GuardRuntime.SESSION) {
            if (!target.isBinderAlive()) {
                if (GuardRuntime.SESSION.died(revision)) {
                    unlink(); GuardRuntime.mediaCallback = null; clearedRevision = GuardRuntime.getRevision();
                }
                paused = false;
            } else paused = GuardRuntime.SESSION.complete(revision, paused);
        }
        if (clearedRevision >= 0) restore(clearedRevision);
        GuardNotice.guardChanged(); GuardRuntime.relayMain("guardChanged", null);
        return paused;
    }
    private static void restore(long revision) {
        long identity = Binder.clearCallingIdentity();
        try { InstrumentBridge.restoreLocal(revision); GuardRuntime.relayMain("restore", null); }
        finally { Binder.restoreCallingIdentity(identity); }
    }
    private void unlink() {
        if (owner != null && death != null) owner.unlinkToDeath(death, 0);
        owner = null; death = null;
    }
    private boolean release(IBinder target) {
        boolean released;
        long clearedRevision;
        synchronized (GuardRuntime.SESSION) {
            released = GuardRuntime.SESSION.release(target);
            if (released) { unlink(); GuardRuntime.mediaCallback = null; }
            clearedRevision = GuardRuntime.getRevision();
        }
        if (released) { restore(clearedRevision); GuardNotice.guardChanged(); }
        return released;
    }
    private void releaseDeath(long revision) {
        boolean released;
        long clearedRevision;
        synchronized (GuardRuntime.SESSION) {
            released = GuardRuntime.SESSION.died(revision);
            if (released) { unlink(); GuardRuntime.mediaCallback = null; }
            clearedRevision = GuardRuntime.getRevision();
        }
        if (released) { restore(clearedRevision); GuardNotice.guardChanged(); }
    }
    @Override public void onDestroy() {
        IBinder target;
        synchronized (GuardRuntime.SESSION) { target = owner; }
        if (target != null) release(target);
        super.onDestroy();
    }
}
