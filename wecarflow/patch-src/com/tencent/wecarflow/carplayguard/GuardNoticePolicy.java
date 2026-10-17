package com.tencent.wecarflow.carplayguard;

/** Main-process memory only. This class never acquires or releases playback protection. */
public final class GuardNoticePolicy {
    private boolean suppressed;
    private boolean pending;

    public void enterForeground() { pending = true; }
    public boolean shouldShow(boolean guarded) { return guarded && pending && !suppressed; }
    public void shown() { pending = false; }
    public void setSuppressed(boolean value) { suppressed = value; }
}
