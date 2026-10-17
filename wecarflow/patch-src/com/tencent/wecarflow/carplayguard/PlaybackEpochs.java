package com.tencent.wecarflow.carplayguard;
import java.util.WeakHashMap;

/** A prepared callback belongs to the session revision of its source/accepted explicit start. */
public final class PlaybackEpochs {
    private final WeakHashMap<Object, Long> origins = new WeakHashMap<Object, Long>();
    public synchronized void mark(Object player, long revision) {
        if (player == null) return;
        if (revision < 0) origins.remove(player);
        else origins.put(player, Long.valueOf(revision));
    }
    public synchronized boolean shouldBlockPrepared(Object player, long revision, boolean guarded) {
        if (guarded || player == null || revision < 0) return true;
        Long origin = origins.get(player);
        return origin == null || origin.longValue() != revision;
    }
}
