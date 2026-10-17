package com.shilapi.xcertplay.pioneer

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PioneerLwipPortLeaseTest {
    @After fun stopOwner() = PioneerLwipRuntime.end(PioneerLwipRuntime.sessionId)

    private fun next(): PioneerLwipRuntime.PortCommand {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        while (System.nanoTime() < deadline) {
            PioneerLwipRuntime.pollPortCommand()?.let { return it }
            Thread.yield()
        }
        error("No owner command")
    }

    @Test fun interruptionBeforeAddCancelsThePendingRegistration() {
        PioneerLwipRuntime.begin()
        val restored = AtomicReference<Boolean>()
        val worker = thread {
            try { PioneerLwipRuntime.registerPort(8721, false); fail("Interrupted registration returned") }
            catch (_: InterruptedException) { restored.set(Thread.currentThread().isInterrupted) }
        }
        val add = next()
        worker.interrupt(); worker.join(1000)
        assertFalse(worker.isAlive)
        assertEquals(true, restored.get())
        synchronized(add) { assertTrue(add.cancelled) }
        assertNull(PioneerLwipRuntime.pollPortCommand())
    }

    @Test fun interruptionAfterSuccessfulAddCompensatesEvenWhenAddQueueIsFull() {
        PioneerLwipRuntime.begin()
        val interrupted = CountDownLatch(1)
        val worker = thread {
            try { PioneerLwipRuntime.registerPort(8721, false); fail("Interrupted registration returned") }
            catch (_: InterruptedException) { interrupted.countDown() }
        }
        val add = next()
        synchronized(add) { add.success = true } // Native add succeeded, ack has not arrived.
        repeat(256) { assertTrue(PioneerLwipRuntime.portCommands.offer(
            PioneerLwipRuntime.PortCommand(add.session, 9000, false, true))) }
        worker.interrupt()
        assertTrue(interrupted.await(1, TimeUnit.SECONDS))
        worker.join(1000)
        val remove = next()
        assertFalse(remove.add)
        assertEquals(add.port, remove.port)
        assertEquals(256, PioneerLwipRuntime.portCommands.size)
        assertTrue(next().add) // A second compensation must not precede queued registrations.
    }

    @Test fun fullAddQueueCannotLoseLeaseReleaseAndOldLeaseCannotTouchNewSession() {
        val session = PioneerLwipRuntime.begin()
        val lease = AtomicReference<Closeable>()
        val worker = thread { lease.set(PioneerLwipRuntime.registerPort(8721, false)) }
        val add = next()
        synchronized(add) { add.success = true; add.done.countDown() }
        worker.join(1000)
        assertNotNull(lease.get())
        repeat(256) { assertTrue(PioneerLwipRuntime.portCommands.offer(PioneerLwipRuntime.PortCommand(session, 9000, false, true))) }
        lease.get().close(); lease.get().close()
        val remove = next()
        assertFalse(remove.add)
        assertEquals(8721, remove.port)
        assertEquals(256, PioneerLwipRuntime.portCommands.size)
        PioneerLwipRuntime.end(session)
        PioneerLwipRuntime.begin()
        lease.get().close()
        assertNull(PioneerLwipRuntime.pollPortCommand())
    }
}
