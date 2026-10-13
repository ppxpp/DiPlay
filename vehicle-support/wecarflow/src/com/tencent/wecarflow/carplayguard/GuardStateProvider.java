package com.tencent.wecarflow.carplayguard;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Non-exported providers give each declared process a context and a synchronous pause barrier. */
public class GuardStateProvider extends ContentProvider {
    @Override public boolean onCreate() {
        GuardRuntime.context = getContext().getApplicationContext();
        GuardRuntime.mainProcess = getClass() == GuardStateProvider.class;
        return true;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle result = new Bundle();
        if ("status".equals(method)) result.putBoolean("guarded", GuardRuntime.guarded);
        else if ("pause".equals(method)) result.putBoolean("paused", GuardRuntime.pauseHere());
        else throw new IllegalArgumentException("Unknown guard operation");
        return result;
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
