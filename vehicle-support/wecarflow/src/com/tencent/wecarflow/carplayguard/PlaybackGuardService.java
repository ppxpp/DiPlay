package com.tencent.wecarflow.carplayguard;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Explicit bound service, raw Binder v1. No platform volume/source changes. */
public final class PlaybackGuardService extends Service {
    private static final String DESCRIPTOR = "com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1";
    private IBinder owner;
    private int ownerUid = -1;
    private boolean ready;
    private IBinder.DeathRecipient death;
    private final Binder endpoint = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(DESCRIPTOR); return true; }
            if (code < 1 || code > 4) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            int uid = Binder.getCallingUid();
            if (!authorized(uid)) throw new SecurityException("Untrusted CarPlay guard caller");
            boolean result;
            synchronized (PlaybackGuardService.this) {
                if (code == 1) result = acquire(uid, data.readStrongBinder());
                else if (code == 2) {
                    IBinder token = data.readStrongBinder();
                    result = owner != null && uid == ownerUid && owner.equals(token);
                    if (result) clear();
                } else if (code == 3) result = ready && GuardRuntime.guarded;
                else {
                    IBinder token = data.readStrongBinder();
                    String title = data.readString(), artist = data.readString(), album = data.readString();
                    long duration = data.readLong(), elapsed = data.readLong();
                    boolean playing = data.readInt() != 0;
                    result = false;
                    if (owner != null && uid == ownerUid && owner.equals(token) && ready) {
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

    private boolean authorized(int uid) {
        try {
            PackageManager pm = getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            boolean found = false;
            if (packages != null) for (String pkg : packages) if (GuardConfig.PACKAGE.equals(pkg)) found = true;
            if (!found) return false;
            return pm.getApplicationInfo(GuardConfig.PACKAGE, 0).uid == uid;
        } catch (Exception e) { return false; }
    }
    private boolean acquire(int uid, final IBinder token) {
        if (token == null || !token.isBinderAlive()) return false;
        if (owner != null) return uid == ownerUid && owner.equals(token) && ready;
        owner = token; ownerUid = uid;
        death = new IBinder.DeathRecipient() {
            @Override public void binderDied() {
                synchronized (PlaybackGuardService.this) { if (token.equals(owner)) clear(); }
            }
        };
        try { token.linkToDeath(death, 0); }
        catch (RemoteException e) { clear(); return false; }
        GuardRuntime.ownerCallback = token;
        GuardRuntime.guarded = true;
        long identity = Binder.clearCallingIdentity();
        try { ready = GuardRuntime.pauseAllProcesses(this); }
        finally { Binder.restoreCallingIdentity(identity); }
        if (!ready || !token.isBinderAlive()) { clear(); return false; }
        return true;
    }
    private void clear() {
        if (owner != null && death != null) owner.unlinkToDeath(death, 0);
        owner = null; ownerUid = -1; death = null; ready = false; GuardRuntime.guarded = false;
        GuardRuntime.ownerCallback = null;
        GuardRuntime.restoreLocal();
    }
    @Override public synchronized void onDestroy() { clear(); super.onDestroy(); }
}
