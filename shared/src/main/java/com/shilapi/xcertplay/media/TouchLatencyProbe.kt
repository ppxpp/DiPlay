package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.pioneer.PioneerDiagnostics
import com.shilapi.xcertplay.pioneer.PioneerLwipRuntime
import java.util.ArrayDeque

/** One current main-screen observer. Samples mean the next frame, not proof of a visual response. */
internal object TouchLatencyProbe {
    @Volatile private var current: VideoInteractionProbe? = null
    @Synchronized fun attach(probe: VideoInteractionProbe) { current = probe }
    @Synchronized fun detach(probe: VideoInteractionProbe) { if (current === probe) current = null }
    fun sent(session: Long, touchId: Long, gestureId: Long, enteredNs: Long, startNs: Long, endNs: Long) {
        current?.takeIf { it.session == session }?.sent(touchId, gestureId, enteredNs, startNs, endNs)
    }
}

/** All maps and pending samples are bounded; the video worker ticks even when no frames arrive. */
internal class VideoInteractionProbe(val session: Long) {
    private data class Touch(val id: Long, val gesture: Long, val entered: Long, val start: Long, val end: Long)
    private data class Frame(val id: Long, val arrival: Long, val touch: Touch?, var input: Long = 0,
        var release: Long = 0)
    private val pending = ArrayDeque<Touch>()
    private val frames = LinkedHashMap<Long, Frame>()
    private val presentations = LinkedHashMap<Long, Frame>()
    private var closed = false
    private var lastReportNs = System.nanoTime()
    private var sent = 0L; private var associated = 0L; private var coalesced = 0L
    private var released = 0L; private var rendered = 0L; private var touchTimeouts = 0L
    private var preCodecTimeouts = 0L; private var renderTimeouts = 0L; private var skippedGaps = 0L
    private var evicted = 0L; private var unmatched = 0L; private var invalidated = 0L
    private var senderNs = 0L; private var arrivalNs = 0L
    private var renderedNs = 0L
    private fun enabled() = !closed && PioneerDiagnostics.enabled && PioneerLwipRuntime.active && PioneerLwipRuntime.sessionId == session
    private fun metric(name: String, ns: Long) { if (ns >= 0) PioneerDiagnostics.record(session, name, ns) }

    @Synchronized fun sent(id: Long, gesture: Long, entered: Long, start: Long, end: Long) {
        if (!enabled()) return
        sent++
        metric("touch-entry-to-send", start-entered)
        metric("touch-send", end-start)
        if (pending.size >= 64) { pending.removeFirst(); evicted++ }
        pending.addLast(Touch(id, gesture, entered, start, end))
    }

    @Synchronized fun received(frameId: Long, sender: Long, arrival: Long, submitted: Long) {
        if (!enabled()) return
        expire(submitted)
        if (sender > 0 && senderNs > 0 && arrivalNs > 0) {
            val sg = sender-senderNs; val ag = arrival-arrivalNs
            if (sg in 1..2_000_000_000L && ag in 1..2_000_000_000L) {
                metric("video-sender-gap", sg)
                metric("video-arrival-gap", ag)
                metric("video-gap-excess", (ag-sg).coerceAtLeast(0))
                if (ag > 150_000_000L) PioneerDiagnostics.slow(session,
                    "stage=video-gap frameId=$frameId senderGapUs=${sg/1000} arrivalGapUs=${ag/1000} signedExcessUs=${(ag-sg)/1000}")
            } else skippedGaps++
        }
        senderNs = sender; arrivalNs = arrival
        var touch: Touch? = null
        // A body that arrived before send completed must not consume the pending touch.
        while (pending.peekFirst()?.let { it.end <= arrival } == true) {
            val next = pending.removeFirst()
            if (touch == null) touch = next else coalesced++
        }
        if (touch != null) {
            associated++
            metric("touch-next-frame-receive", arrival-touch.entered)
            metric("touch-send-to-next-receive", arrival-touch.start)
        }
        metric("video-arrival-to-submit", submitted-arrival)
        if (frames.size >= 128) { frames.remove(frames.keys.first()); evicted++ }
        frames[frameId] = Frame(frameId, arrival, touch)
    }

    @Synchronized fun input(frameId: Long, ptsUs: Long, now: Long) {
        if (!enabled()) return
        val frame = frames.remove(frameId) ?: return
        frame.input = now
        metric("video-arrival-to-codec-input", now-frame.arrival)
        if (presentations.size >= 128) { presentations.remove(presentations.keys.first()); evicted++ }
        if (presentations.put(ptsUs, frame) != null) unmatched++
    }

    @Synchronized fun output(ptsUs: Long, now: Long, shown: Boolean) {
        if (!enabled()) return
        val frame = presentations[ptsUs] ?: run { unmatched++; return }
        if (!shown) { presentations.remove(ptsUs); invalidated++; return }
        frame.release = now
        released++
        metric("video-arrival-to-release", now-frame.arrival)
        metric("video-codec-input-to-release", now-frame.input)
        frame.touch?.let {
            metric("touch-next-frame-release", now-it.entered)
            if (now-it.entered > 150_000_000L) PioneerDiagnostics.slow(session,
                "stage=touch-next-frame-release touchId=${it.id} gestureId=${it.gesture} frameId=${frame.id} elapsedUs=${(now-it.entered)/1000}")
        }
    }

    @Synchronized fun rendered(ptsUs: Long, now: Long): Boolean {
        if (!enabled()) return false
        val frame = presentations.remove(ptsUs) ?: run { unmatched++; return false }
        if (frame.release == 0L || now < frame.release || now > System.nanoTime()+100_000_000L) { unmatched++; return false }
        rendered++
        if (renderedNs > 0 && now > renderedNs) metric("video-render-gap", now-renderedNs)
        renderedNs = now
        metric("video-arrival-to-render-callback", now-frame.arrival)
        metric("video-release-to-render-callback", now-frame.release)
        frame.touch?.let { metric("touch-next-frame-render-callback", now-it.entered) }
        return true
    }

    private fun expire(now: Long) {
        while (pending.peekFirst()?.let { now-it.entered > 2_000_000_000L } == true) { pending.removeFirst(); touchTimeouts++ }
        val f = frames.values.iterator()
        while (f.hasNext()) if (now-f.next().arrival > 2_000_000_000L) { f.remove(); preCodecTimeouts++ }
        val p = presentations.values.iterator()
        while (p.hasNext()) if (now-p.next().arrival > 2_000_000_000L) { p.remove(); renderTimeouts++ }
    }
    @Synchronized fun summary(): String? {
        if (!enabled()) return null
        val now = System.nanoTime()
        if (now-lastReportNs < 5_000_000_000L) return null
        expire(now); lastReportNs = now
        return "PIONEER_INTERACTION sessionId=$session sent=$sent associated=$associated coalesced=$coalesced " +
            "released=$released renderCallbacks=$rendered touchTimeouts=$touchTimeouts preCodecTimeouts=$preCodecTimeouts renderTimeouts=$renderTimeouts skippedGaps=$skippedGaps evicted=$evicted unmatched=$unmatched invalidated=$invalidated " +
            "pending=${pending.size} pendingOldestMs=${pending.peekFirst()?.let { (now-it.entered)/1_000_000 } ?: 0} " +
            "preCodec=${frames.size} awaitingRender=${presentations.size} semantics=next-frame-not-visual-response"
    }
    @Synchronized fun resetCodec() {
        invalidated += frames.size + presentations.size
        frames.clear(); presentations.clear(); renderedNs = 0
    }
    fun close() {
        synchronized(this) { closed = true; pending.clear(); frames.clear(); presentations.clear() }
        TouchLatencyProbe.detach(this)
    }
}
