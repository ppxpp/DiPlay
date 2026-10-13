package com.tencent.wecarflow.carplayguard;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Explicit bound service, raw Binder v1. No platform volume/source changes. */
public final class PlaybackGuardService extends Service {
    private static final String DESCRIPTOR = "com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1";
    private IBinder callback;
    private long callbackRevision;
    private boolean callbackLinked;
    private boolean ready;
    private IBinder.DeathRecipient death;
    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            if (code < 1 || code > 4) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            boolean result;
            synchronized (PlaybackGuardService.this) {
                if (code == 1) result = acquire(data.readStrongBinder());
                else if (code == 2) {
                    data.readStrongBinder(); // Wire compatibility; no owner authorization.
                    clear();
                    result = true;
                } else if (code == 3) result = ready && GuardRuntime.guarded;
                else {
                    data.readStrongBinder(); // Wire compatibility; no owner authorization.
                    String title = data.readString(), artist = data.readString(), album = data.readString();
                    long duration = data.readLong(), elapsed = data.readLong();
                    boolean playing = data.readInt() != 0;
                    result = false;
                    if (ready && GuardRuntime.guarded) {
                        long identity = Binder.clearCallingIdentity();
                        try { result = GuardRuntime.publishRemote(PlaybackGuardService.this, title, artist, album,
                                Math.max(0, duration), Math.max(0, elapsed), playing); }
                        finally { Binder.restoreCallingIdentity(identity); }
                    }
                }
            }
            reply.writeNoException(); reply.writeInt(result ? 1 : 0);
            return true;
        }
    };
    @Override public IBinder onBind(Intent intent) { return endpoint; }

    private boolean acquire(final IBinder target) {
        if (target == null) return false; // A callback is needed for control and process-death cleanup.
        if (callbackLinked && callback != null && death != null) callback.unlinkToDeath(death, 0);
        callbackLinked = false;
        callback = target;
        final long revision = ++callbackRevision;
        death = new IBinder.DeathRecipient() {
            @Override public void binderDied() {
                synchronized (PlaybackGuardService.this) {
                    if (revision == callbackRevision) clear();
                }
            }
        };
        try { target.linkToDeath(death, 0); callbackLinked = true; }
        catch (RemoteException e) { clear(); return false; }
        GuardRuntime.mediaCallback = target;
        if (ready && GuardRuntime.guarded) return true;
        GuardRuntime.guarded = true;
        long identity = Binder.clearCallingIdentity();
        try { ready = GuardRuntime.pauseAllProcesses(this); }
        finally { Binder.restoreCallingIdentity(identity); }
        if (!ready || !target.isBinderAlive()) { clear(); return false; }
        return true;
    }
    private void clear() {
        if (callbackLinked && callback != null && death != null) callback.unlinkToDeath(death, 0);
        callbackLinked = false;
        ++callbackRevision;
        callback = null; death = null; ready = false; GuardRuntime.guarded = false;
        GuardRuntime.mediaCallback = null;
        GuardRuntime.restoreLocal();
    }
    @Override public synchronized void onDestroy() { clear(); super.onDestroy(); }
}
