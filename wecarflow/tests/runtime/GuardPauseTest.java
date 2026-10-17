package com.tencent.wecarflow.carplayguard;
import java.util.HashSet;
public final class GuardPauseTest {
    public static final class Handler {
        final HashSet<Integer> pending = new HashSet<Integer>();
        public void removeMessages(int what) { pending.remove(what); }
    }
    public static final class Manager {
        private final Handler n = new Handler();
        boolean sharedFocus = true;
        boolean paused;
        public void d(boolean byUser) {
            if (n.pending.contains(2)) throw new AssertionError("Queued play not removed before pause");
            if (byUser) sharedFocus = false;
            paused = true;
        }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Manager manager = new Manager(); manager.n.pending.add(2); manager.n.pending.add(3);
        GuardPause.pauseCentral(manager);
        check(manager.paused && manager.sharedFocus);
        check(!manager.n.pending.contains(2) && manager.n.pending.contains(3));
        GuardPause.pauseCentral(manager); check(manager.sharedFocus);
        System.out.println("GuardPause queued play cancellation tests passed");
    }
}
