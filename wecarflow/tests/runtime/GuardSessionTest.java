package com.tencent.wecarflow.carplayguard;
public final class GuardSessionTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        GuardSession s = new GuardSession(); Object a = new Object(), b = new Object();
        check(!s.guarded() && !s.ready());
        long first = s.acquire(a); check(s.guarded() && !s.ready());
        check(!s.complete(first, false)); check(s.guarded() && !s.ready());
        long retry = s.acquire(a); check(retry > first);
        check(!s.complete(first, true)); check(!s.died(first));
        check(s.complete(retry, true)); check(s.ready());
        long next = s.acquire(b); check(!s.ready());
        check(!s.release(a)); check(!s.died(retry)); check(!s.complete(retry, true));
        check(s.guarded()); check(s.complete(next, true));
        check(s.release(b)); check(!s.guarded() && !s.ready());
        check(!s.complete(next, true)); check(!s.died(next));
        long death = s.acquire(a); check(s.died(death)); check(!s.guarded());
        check(s.revision() > death);
        System.out.println("GuardSession lifecycle tests passed");
    }
}
