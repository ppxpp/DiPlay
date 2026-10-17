package com.tencent.wecarflow.carplayguard;

/** Android-independent lifecycle. A failed pause keeps the gate closed, but never reports ready. */
public final class GuardSession {
    private Object owner;
    private long revision;
    private boolean ready;
    public synchronized long acquire(Object next) {
        if (next == null) throw new IllegalArgumentException("Missing owner");
        owner = next;
        ready = false;
        return ++revision;
    }
    public synchronized boolean complete(long ticket, boolean paused) {
        if (!current(ticket)) return false;
        ready = paused;
        return ready;
    }
    public synchronized boolean release(Object expected) {
        if (owner == null || !owner.equals(expected)) return false;
        clear();
        return true;
    }
    public synchronized boolean died(long ticket) {
        if (!current(ticket)) return false;
        clear();
        return true;
    }
    private void clear() { owner = null; ready = false; ++revision; }
    public synchronized boolean current(long ticket) { return owner != null && revision == ticket; }
    public synchronized boolean owns(Object expected) { return owner != null && owner.equals(expected); }
    public synchronized boolean guarded() { return owner != null; }
    public synchronized boolean ready() { return owner != null && ready; }
    public synchronized long revision() { return revision; }
}
