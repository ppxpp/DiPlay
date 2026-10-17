package com.tencent.wecarflow.carplayguard;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Private provider initializes each process before its Application and supplies synchronous state. */
public class GuardStateProvider extends ContentProvider {
    private final android.content.ServiceConnection centralConnection = new android.content.ServiceConnection() {
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder service) {
            new Thread(new Runnable() { public void run() { registerWithCentral(); } }, "guard-peer-register").start();
        }
        public void onServiceDisconnected(android.content.ComponentName name) { }
    };
    private void registerWithCentral() {
        Bundle registration = new Bundle(); registration.putString("process", processName()); registration.putBinder("peer", peer);
        try { getContext().getContentResolver().call(Uri.parse("content://" + GuardRuntime.AUTHORITY), "registerPeer", null, registration); }
        catch (RuntimeException e) { android.util.Log.e("CarPlayGuard", "Peer registration failed", e); }
    }
    private final android.os.Binder peer = new android.os.Binder() {
        @Override protected boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (code < 1 || code > 4) return super.onTransact(code, data, reply, flags);
            data.enforceInterface("com.tencent.wecarflow.carplayguard.Peer.v1");
            Bundle args = data.readBundle(getClass().getClassLoader());
            boolean ok = true;
            if (code == 1) ok = GuardRuntime.pauseHere(args == null ? -1 : args.getLong("revision", -1));
            else if (code == 2) { ok = args != null && GuardRuntime.applyRemote(args); GuardNotice.guardChanged(); }
            else if (code == 3) { InstrumentBridge.restoreLocal(GuardRuntime.getRevision()); GuardNotice.guardChanged(); }
            else GuardNotice.guardChanged();
            reply.writeNoException(); reply.writeInt(ok ? 1 : 0); return true;
        }
    };
    @Override public boolean onCreate() {
        GuardRuntime.context = getContext().getApplicationContext();
        GuardRuntime.centralProcess = getClass() == GuardStateProvider.class;
        if (!BridgeBootstrap.isBridgeProcess(GuardRuntime.context) && GuardRuntime.context.getPackageName().equals(processName()) && GuardRuntime.context instanceof android.app.Application)
            GuardNotice.install((android.app.Application) GuardRuntime.context);
        if (!GuardRuntime.centralProcess) {
            registerWithCentral();
            // Android rebinds after central process death; the callback renews our registration.
            try { getContext().bindService(new android.content.Intent().setClassName(getContext().getPackageName(),
                "com.tencent.wecarflow.carplayguard.PlaybackGuardService"), centralConnection, android.content.Context.BIND_AUTO_CREATE); }
            catch (RuntimeException e) { android.util.Log.e("CarPlayGuard", "Central rebind unavailable", e); }
        }
        return true;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if ("registerPeer".equals(method) && GuardRuntime.centralProcess) {
            if (extras != null) GuardRuntime.registerPeer(extras.getString("process"), extras.getBinder("peer"));
        } else if ("status".equals(method)) {
            if (GuardRuntime.centralProcess) {
                synchronized (GuardRuntime.SESSION) {
                    result.putBoolean("guarded", GuardRuntime.SESSION.guarded());
                    result.putLong("revision", GuardRuntime.SESSION.revision());
                }
            } else {
                result.putBoolean("guarded", GuardRuntime.isGuarded());
                result.putLong("revision", GuardRuntime.getRevision());
            }
        } else if ("remoteSnapshot".equals(method) && GuardRuntime.centralProcess) {
            return GuardRuntime.remoteSnapshot();
        } else if ("remote".equals(method) && !GuardRuntime.centralProcess) {
            result.putBoolean("updated", extras != null && GuardRuntime.applyRemote(extras));
            GuardNotice.guardChanged();
        } else if ("restore".equals(method) && !GuardRuntime.centralProcess) {
            InstrumentBridge.restoreLocal(GuardRuntime.getRevision()); GuardNotice.guardChanged();
        } else if ("guardChanged".equals(method) && !GuardRuntime.centralProcess) {
            GuardNotice.guardChanged();
        } else if ("command".equals(method) && GuardRuntime.centralProcess) {
            result.putBoolean("accepted", extras != null && GuardRuntime.forwardMediaCommand(extras.getInt("command", 0)));
        } else if ("pause".equals(method)) {
            result.putBoolean("paused", GuardRuntime.pauseHere(extras == null ? -1 : extras.getLong("revision", -1)));
        } else throw new IllegalArgumentException("Unknown guard operation");
        return result;
    }
    private String processName() {
        android.app.ActivityManager am = (android.app.ActivityManager)getContext().getSystemService(android.content.Context.ACTIVITY_SERVICE);
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes = am == null ? null : am.getRunningAppProcesses();
        if (processes != null) for (android.app.ActivityManager.RunningAppProcessInfo p : processes)
            if (p.pid == android.os.Process.myPid()) return p.processName;
        return "";
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
