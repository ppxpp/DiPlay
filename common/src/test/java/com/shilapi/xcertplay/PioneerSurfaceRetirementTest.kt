package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.view.Surface
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class PioneerSurfaceRetirementTest {
    @Test fun screenshotTimeConsumesTheSameRetirementBudget() {
        val started = 1_000_000_000L
        var now = started
        val deadlines = ArrayList<Pair<Long, Long>>()
        var screenshotFinished = false
        val owner = AndroidMediaSink()
        val surface = Surface(SurfaceTexture(0))
        val retirement = PioneerSurfaceRetirement(nowNanos = { now }, request = { _, _, deadline, grace ->
            assertTrue("capture the last frame before asking its decoder to detach", screenshotFinished)
            deadlines += deadline to grace
            val wait: () -> Boolean = {
                now = maxOf(now, grace)
                false
            }
            wait
        })
        try {
            // PixelCopy may consume 150ms before the first detach can be queued.
            now += 150_000_000L
            screenshotFinished = true
            retirement.begin(surface, listOf(owner), startedNanos = started)
            assertFalse(retirement.await(surface, listOf(owner)))
            assertTrue(deadlines.all { it == (1_120_000_000L to 1_200_000_000L) })
            assertEquals("capture and all decoder waits share the same 200ms", 200_000_000L, now - started)
        } finally {
            owner.close()
            surface.release()
        }
    }

    @Test fun everySinkSharesTheEarlyDeadlineAndFailureStillSettlesTheOthers() {
        var now = 1_000_000_000L
        val deadlines = ArrayList<Pair<Long, Long>>()
        val settled = ArrayList<AndroidMediaSink>()
        val first = AndroidMediaSink()
        val second = AndroidMediaSink()
        val surface = Surface(SurfaceTexture(0))
        val retirement = PioneerSurfaceRetirement(nowNanos = { now }, request = { owner, _, deadline, grace ->
            deadlines += deadline to grace
            val wait: () -> Boolean = {
                settled += owner
                now = grace
                owner !== first
            }
            wait
        })
        try {
            retirement.begin(surface, listOf(first))
            assertTrue("the leave hint must not wait", settled.isEmpty())
            now += 60_000_000L
            assertFalse(retirement.await(surface, listOf(first, second)))
            assertTrue("a failed first sink must not skip later retirement", second in settled)
            assertTrue(deadlines.all { it == (1_120_000_000L to 1_200_000_000L) })
            assertEquals("the pause does not grant a new budget to each sink", 1_200_000_000L, now)

            retirement.begin(surface, listOf(second))
            assertEquals("a later pause gets its own budget", 1_320_000_000L to 1_400_000_000L, deadlines.last())
        } finally {
            first.close()
            second.close()
            surface.release()
        }
    }
}
