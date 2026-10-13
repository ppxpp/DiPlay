package com.shilapi.xcertplay.vehicle.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.vehicle.adapters.pioneer.CeamInstrumentMediaSink
import com.shilapi.xcertplay.vehicle.adapters.pioneer.PalSteeringWheelInputAdapter
import com.shilapi.xcertplay.vehicle.adapters.wecarflow.QQMusicPlaybackGuardClient
import com.shilapi.xcertplay.vehicle.api.LocalPlaybackGuardClient
import com.shilapi.xcertplay.vehicle.api.MediaCommand
import com.shilapi.xcertplay.vehicle.api.NowPlayingSnapshot
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/** Session policy. Vendor Binder and instrument calls never execute under a media-key lock. */
class VehicleMediaRuntime private constructor(
    context: Context,
    private val sendCommand: (MediaCommand) -> Boolean,
    private val report: (String) -> Unit,
) : Closeable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    @Volatile private var session: Any? = null
    @Volatile private var disposed = false
    @Volatile private var ready = CountDownLatch(0)
    @Volatile private var protected = false
    @Volatile private var guard: LocalPlaybackGuardClient? = null
    private var instrument = CeamInstrumentMediaSink(report)
    private data class Retained(val snapshot: NowPlayingSnapshot, val updatedAt: Long)
    @Volatile private var latest = Retained(NowPlayingSnapshot(), SystemClock.elapsedRealtime())
    private var published: NowPlayingSnapshot? = null
    private var progressTask: ScheduledFuture<*>? = null
    private var fallbackResult: Boolean? = null
    @Volatile private var retriedGuard = false

    @Synchronized
    fun sessionChanged(token: Any, active: Boolean) {
        if (disposed || (!active && session !== token) || (active && session === token)) return
        session = if (active) token else null
        val revision = generation.incrementAndGet()
        protected = false
        ready = if (active) CountDownLatch(1) else CountDownLatch(0)
        latest = Retained(NowPlayingSnapshot(), SystemClock.elapsedRealtime())
        main.post {
            if (generation.get() != revision) return@post
            if (active) {
                PalSteeringWheelInputAdapter.configure(context)
                PalSteeringWheelInputAdapter.start({ command -> dispatchCommand(revision, command) }, report)
            } else PalSteeringWheelInputAdapter.stop()
        }
        val latch = ready
        worker.execute {
            progressTask?.cancel(false)
            progressTask = null
            instrument.close()
            instrument = CeamInstrumentMediaSink(report)
            published = null
            fallbackResult = null
            if (!active || generation.get() != revision || disposed) return@execute
            publishLatest()
            progressTask = worker.scheduleAtFixedRate({
                if (generation.get() == revision && session != null && !disposed) publishLatest()
            }, 1, 1, TimeUnit.SECONDS)
        }
        protectionWorker.execute {
            guard?.close()
            guard = null
            retriedGuard = false
            if (!active || generation.get() != revision || disposed) { latch.countDown(); return@execute }
            val next = newGuard(revision)
            guard = next
            try {
                val acquired = next.acquire()
                if (generation.get() != revision || session == null || disposed) {
                    next.close()
                    if (guard === next) guard = null
                    return@execute
                }
                protected = acquired && generation.get() == revision && session != null && !disposed
                if (!protected) {
                    VehicleAudioRouting.selected.pauseLocalPlayer(context, report)
                    report("Vehicle: protection degraded; one pause cannot block subsequent native playback")
                }
            } finally { latch.countDown() }
            refreshPublished(revision)
        }
        if (active) VehiclePlaybackProtection.attach(this)
        else VehiclePlaybackProtection.detach(this)
    }

    private fun newGuard(revision: Long): LocalPlaybackGuardClient =
        QQMusicPlaybackGuardClient(context, report, onLost = {
            if (generation.get() == revision && session != null && !disposed) {
                protected = false
                protectionWorker.execute {
                    if (generation.get() != revision || session == null || disposed) return@execute
                    guard?.close()
                    val replacement = newGuard(revision)
                    guard = replacement
                    protected = replacement.acquire() && generation.get() == revision && session != null && !disposed
                    refreshPublished(revision)
                    report("Vehicle: companion reconnected protection=$protected")
                }
            }
        }, onCommand = { command ->
            generation.get() == revision && session != null && !disposed && sendCommand(command)
        })

    private fun dispatchCommand(revision: Long, command: MediaCommand) {
        worker.execute {
            if (generation.get() == revision && session != null && !disposed) {
                val accepted = sendCommand(command)
                report("Vehicle: CarPlay command=$command queued=$accepted (phone outcome unverified)")
            }
        }
    }

    private fun refreshPublished(revision: Long) {
        worker.execute {
            if (generation.get() == revision && session != null && !disposed) {
                published = null
                publishLatest()
            }
        }
    }

    fun nowPlaying(snapshot: NowPlayingSnapshot) {
        if (disposed || session == null) return
        latest = Retained(snapshot, SystemClock.elapsedRealtime())
        val revision = generation.get()
        worker.execute {
            if (generation.get() == revision && session != null && !disposed) publishLatest()
        }
    }

    private fun publishLatest() {
        val state = latest
        val retained = state.snapshot
        var elapsed = retained.elapsedMillis
        if (retained.positionKnown && retained.playing && retained.title.isNotEmpty()) {
            elapsed += (SystemClock.elapsedRealtime() - state.updatedAt).coerceAtLeast(0)
        }
        if (retained.durationMillis > 0) elapsed = elapsed.coerceAtMost(retained.durationMillis)
        val snapshot = retained.copy(elapsedMillis = elapsed)
        if (snapshot != published) {
            var accepted = instrument.publish(snapshot)
            if (!accepted) {
                accepted = protected && (guard as? QQMusicPlaybackGuardClient)?.publish(snapshot) == true
                if (fallbackResult != accepted) {
                    report("Vehicle: widget instrument fallback accepted=$accepted; display unverified")
                    fallbackResult = accepted
                }
            }
            // Failure must remain retryable even when a paused song has no further updates.
            if (accepted) published = snapshot
        }
    }

    /** Bounded barrier before a source switch that could wake the native music application. */
    internal fun awaitProtection(): Boolean {
        if (disposed || session == null) return false
        if (Looper.myLooper() == Looper.getMainLooper()) return protected
        runCatching { ready.await(1800, TimeUnit.MILLISECONDS) }
        if (!protected && !retriedGuard) {
            synchronized(this) {
                if (!retriedGuard) {
                    retriedGuard = true
                    val revision = generation.get()
                    protectionWorker.execute {
                        if (generation.get() == revision && session != null && !disposed) {
                            guard?.close()
                            guard = newGuard(revision)
                            protected = guard?.acquire() == true && generation.get() == revision && session != null && !disposed
                            refreshPublished(revision)
                        }
                    }
                }
            }
        }
        return protected
    }

    @Synchronized
    override fun close() {
        if (disposed) return
        disposed = true
        session = null
        generation.incrementAndGet()
        ready.countDown()
        VehiclePlaybackProtection.detach(this)
        // Do not stop a newer runtime's PAL consumer while the old one is being disposed.
        main.post { if (!VehiclePlaybackProtection.hasActiveSession) PalSteeringWheelInputAdapter.stop() }
        protectionWorker.execute { guard?.close(); guard = null }
        worker.execute { progressTask?.cancel(false); progressTask = null; instrument.close() }
    }

    companion object {
        private val worker = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "diplay-vehicle-media").apply { isDaemon = true }
        }
        private val protectionWorker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-playback-guard").apply { isDaemon = true }
        }
        fun create(context: Context, sendCommand: (MediaCommand) -> Boolean, report: (String) -> Unit): VehicleMediaRuntime? =
            if (VehicleAudioRouting.available && context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
                    .getBoolean("vehicle_adaptation_enabled", true)) VehicleMediaRuntime(context, sendCommand, report) else null
    }
}

/** Coordination bridge for audio; it contains no vendor identity or protocol. */
internal object VehiclePlaybackProtection {
    @Volatile private var owner: VehicleMediaRuntime? = null
    val hasActiveSession: Boolean get() = owner != null
    @Synchronized fun attach(runtime: VehicleMediaRuntime) { owner = runtime }
    @Synchronized fun detach(runtime: VehicleMediaRuntime) { if (owner === runtime) owner = null }
    fun beforeSourceActivation(report: (String) -> Unit) {
        val active = owner ?: return
        if (!active.awaitProtection()) report("Vehicle: source activation without confirmed playback protection")
    }
}
