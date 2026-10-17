package com.shilapi.xcertplay

import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink

/** Main-thread preparation and bounded confirmation of the Pioneer host's surface retirement. */
internal class PioneerSurfaceRetirement(
    private val nowNanos: () -> Long = System::nanoTime,
    private val request: (AndroidMediaSink, Surface, Long, Long) -> (() -> Boolean) = { owner, surface, deadline, grace ->
        val detach = owner.beginSurfaceDetach(surface, parkMain = false,
            deadlineNanos = deadline, graceDeadlineNanos = grace)
        val wait: () -> Boolean = { detach.await() }
        wait
    },
) {
    private class Pending(val surface: Surface, val deadline: Long, val grace: Long) {
        val waits = ArrayList<() -> Boolean>()
    }
    private var pending: Pending? = null

    /** Queue work after the screenshot; [startedNanos] includes the time spent capturing it. */
    fun begin(surface: Surface, owners: List<AndroidMediaSink>, startedNanos: Long = nowNanos()) {
        val batch = pending?.takeIf { it.surface === surface } ?: run {
            val deadline = startedNanos + DETACH_NANOS
            Pending(surface, deadline, deadline + EXIT_GRACE_NANOS).also { pending = it }
        }
        owners.distinct().forEach { batch.waits += request(it, surface, batch.deadline, batch.grace) }
    }

    fun await(surface: Surface, owners: List<AndroidMediaSink>): Boolean {
        // Include decoders/sinks created since the early hint; all requests retain the same deadline.
        begin(surface, owners)
        val batch = checkNotNull(pending)
        pending = null
        // Evaluate every waiter, even after a failure: each timeout must retire its decoder.
        return batch.waits.map { it() }.all { it }
    }

    private companion object {
        const val DETACH_NANOS = 120_000_000L
        const val EXIT_GRACE_NANOS = 80_000_000L
    }
}
