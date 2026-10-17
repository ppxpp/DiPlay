package com.tencent.wecarflow.carplayguard;
public final class PlaybackEpochsTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        PlaybackEpochs epochs = new PlaybackEpochs();
        GuardSession session = new GuardSession();
        Object player = new Object(), other = new Object(), owner = new Object();
        check(epochs.shouldBlockPrepared(player, session.revision(), false));
        epochs.mark(player, session.revision());
        check(!epochs.shouldBlockPrepared(player, session.revision(), false));
        check(epochs.shouldBlockPrepared(other, session.revision(), false));
        long ticket = session.acquire(owner);
        check(epochs.shouldBlockPrepared(player, session.revision(), session.guarded()));
        session.release(owner);
        // Old prepared event stays blocked after connection clears, even though local gate is open.
        check(epochs.shouldBlockPrepared(player, session.revision(), session.guarded()));
        epochs.mark(player, session.revision()); // New manual start/source is accepted.
        check(!epochs.shouldBlockPrepared(player, session.revision(), false));
        session.acquire(owner);
        epochs.mark(other, session.revision());
        check(epochs.shouldBlockPrepared(other, session.revision(), true));
        session.release(owner);
        check(epochs.shouldBlockPrepared(other, session.revision(), false));
        epochs.mark(player, session.revision());
        long newer = session.acquire(owner); session.died(newer);
        check(epochs.shouldBlockPrepared(player, session.revision(), false));
        epochs.mark(player, session.revision()); check(!epochs.shouldBlockPrepared(player, session.revision(), false));
        epochs.mark(player, -1); check(epochs.shouldBlockPrepared(player, session.revision(), false));
        check(epochs.shouldBlockPrepared(null, session.revision(), false));
        check(!session.current(ticket));
        System.out.println("PlaybackEpochs delayed preparation tests passed");
    }
}
