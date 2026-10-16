package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.pioneer.PioneerTiming
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService

/** Reuses the existing serial event executor; only unsent adjacent MOVE tasks can merge. */
internal class PioneerTouchQueue(private val executor: ExecutorService, private val log: (String) -> Unit) {
    private class Task(val session: AirPlaySession, var contacts: List<AirPlayContact>, val move: Boolean,
        val id: Long, val gesture: Long, val created: Long) { var latestEnteredNs = created; var started = false; var cancelled = false }
    private val lock = Any()
    private val queued = ArrayDeque<Task>()
    private var id = 0L; private var gesture = 0L; private var merged = 0L; private var rejected = 0L
    private var lastLog = System.nanoTime()
    private var rejectedSession: AirPlaySession? = null
    private var peak = 0
    private val waiting = PioneerTiming("touch-queue")
    fun submit(session: AirPlaySession, contacts: List<AirPlayContact>, move: Boolean, enteredNs: Long = System.nanoTime()): Boolean {
        synchronized(lock) {
            if (session.isClosed || rejectedSession === session) return false
            val tail = queued.peekLast()
            val shape = contacts.map { it.id to it.down }
            if (move && tail != null && tail.move && !tail.started && !tail.cancelled &&
                tail.session === session && tail.gesture == gesture && tail.contacts.map { it.id to it.down } == shape) {
                tail.contacts = contacts.toList(); tail.latestEnteredNs = enteredNs; merged++; return true
            }
            if (!move) gesture++
            if (queued.size >= 64) {
                // No safe way to preserve another transition while an event write is blocked.
                // End the session (resetting its HID state) rather than dropping an UP/CANCEL.
                rejected++; rejectedSession = session; queued.forEach { it.cancelled = true }; queued.clear()
                log("PIONEER_TOUCH_OVERLOAD rejected=$rejected; ending session to release contacts")
                Thread({ session.close() }, "pioneer-touch-overload").apply { isDaemon = true; start() }
                return false
            }
            val task = Task(session, contacts.toList(), move, ++id, gesture, enteredNs)
            queued.addLast(task); peak = maxOf(peak,queued.size)
            try {
                executor.execute {
                    val touch = synchronized(lock) {
                        task.started = true; queued.remove(task)
                        if (task.cancelled || session.isClosed) null else task.contacts to task.latestEnteredNs
                    } ?: return@execute
                    val (contactsToSend, latestEnteredNs) = touch
                    val waitNs = System.nanoTime()-latestEnteredNs
                    waiting.record(waitNs)
                    if (waitNs > 100_000_000L) com.shilapi.xcertplay.pioneer.PioneerDiagnostics.slow(session.lwipSession,
                        "stage=touch-queue touchId=${task.id} gestureId=${task.gesture} queueUs=${waitNs/1000}")
                    session.sendTouch(contactsToSend, latestEnteredNs, task.id, task.gesture)
                    synchronized(lock) {
                        if (System.nanoTime() - lastLog >= 5_000_000_000L) {
                            val age = queued.peekFirst()?.let { (System.nanoTime()-it.created)/1000 } ?: 0
                            log("PIONEER_TOUCH sessionId=${session.lwipSession} touchId=${task.id} gestureId=${task.gesture} depth=${queued.size} peak=$peak oldestUs=$age merged=$merged rejected=$rejected ${waiting.drain()}")
                            peak = queued.size; lastLog = System.nanoTime()
                        }
                    }
                }
            } catch (_: Exception) { queued.remove(task); rejected++; return false }
            return true
        }
    }
    /** Prevent coalescing across other event commands sharing this executor. */
    fun barrier() { synchronized(lock) { gesture++ } }
    fun clear(session: AirPlaySession? = null) { synchronized(lock) {
        val iterator = queued.iterator()
        while (iterator.hasNext()) { val task = iterator.next(); if (session == null || task.session === session) { task.cancelled=true; iterator.remove() } }
        gesture++
    } }
}
