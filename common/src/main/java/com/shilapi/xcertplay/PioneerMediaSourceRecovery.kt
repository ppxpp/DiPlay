package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime
import com.shilapi.xcertplay.pioneer.PioneerLwipRuntime
import com.shilapi.xcertplay.pioneer.PioneerUsbPolicy
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Observes native source selection without selecting a source or opening the factory player. */
internal class PioneerMediaSourceRecovery private constructor(
    context: Context,
    private val controller: CarPlayController,
) : Closeable {
    private val app = context.applicationContext
    private val policy = PioneerSourceRecoveryPolicy()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "pioneer-source-recovery").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    private var session: Any? = null
    @Volatile private var lastSource: Int? = null
    private var lastIntentRevision = 0L
    private var departureIntentRevision: Long? = null
    @Volatile private var recoverySink: com.shilapi.xcertplay.media.AndroidMediaSink? = null
    private var failureReported = false
    private var mediaController: Any? = null
    private var sourceReader: java.lang.reflect.Method? = null

    init { worker.scheduleWithFixedDelay(::tick, 500, 500, TimeUnit.MILLISECONDS) }

    private fun readSource(): Int {
        if (sourceReader == null) {
            val cls = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
            mediaController = cls.getMethod("getInstance").invoke(null)
            sourceReader = cls.getMethod("getCurSource", Int::class.javaPrimitiveType)
        }
        return (sourceReader!!.invoke(mediaController, 1) as Int).also { check(it >= 0) { "Invalid source state" } }
    }

    private fun tick() {
        if (closed) return
        try {
            val current = controller.activeSessionIdentity()?.takeIf { PioneerLwipRuntime.active }
            if (current !== session) {
                recoverySink?.cancelVehicleMediaRecovery()
                recoverySink = null
                session = current
                departureIntentRevision = null
                lastIntentRevision = controller.mediaCommandRevision()
                policy.reset()
                lastSource = null
            }
            if (current == null) return
            val background = CarPlayBackgroundSession.snapshot()?.takeIf { it.controller === controller } ?: return
            val source = readSource()
            val playing = controller.nowPlayingSnapshot().playing
            val mediaActive = background.sink.hasMediaAudio()
            val intentRevision = controller.mediaCommandRevision()
            if (lastSource == PioneerSourceRecoveryPolicy.MEDIA_SOURCE && source != lastSource) {
                departureIntentRevision = lastIntentRevision
            }
            if (source != PioneerSourceRecoveryPolicy.MEDIA_SOURCE || !playing || !background.sink.canRecoverVehicleMedia()) {
                recoverySink?.cancelVehicleMediaRecovery()
                recoverySink = null
            }
            if (source != lastSource) {
                log("source=$source previous=$lastSource phonePlaying=$playing mediaActive=$mediaActive")
                lastSource = source
            }
            lastIntentRevision = intentRevision
            val action = policy.sample(source, playing, mediaActive, SystemClock.elapsedRealtime())
            if (departureIntentRevision != null && departureIntentRevision != intentRevision) {
                policy.cancel()
                recoverySink?.cancelVehicleMediaRecovery()
                recoverySink = null
                return
            }
            if (action == null) return
            // A call, user pause, source change or replaced session cancels pending recovery.
            if (closed || controller.activeSessionIdentity() !== current || readSource() != PioneerSourceRecoveryPolicy.MEDIA_SOURCE ||
                !controller.nowPlayingSnapshot().playing || !background.sink.canRecoverVehicleMedia()) {
                policy.cancel()
                return
            }
            val focus = background.sink.recoverVehicleMediaFocus()
            recoverySink = background.sink
            if (closed || controller.activeSessionIdentity() !== current || readSource() != PioneerSourceRecoveryPolicy.MEDIA_SOURCE ||
                !controller.nowPlayingSnapshot().playing || !background.sink.canRecoverVehicleMedia()) {
                background.sink.cancelVehicleMediaRecovery()
                recoverySink = null
                policy.cancel()
                return
            }
            val stillAllowed = {
                !closed && controller.activeSessionIdentity() === current &&
                    controller.mediaCommandRevision() == intentRevision && controller.nowPlayingSnapshot().playing &&
                    lastSource == PioneerSourceRecoveryPolicy.MEDIA_SOURCE && background.sink.canRecoverVehicleMedia()
            }
            if (focus) CarPlayMediaKeys.restoreVehicleSession(controller, current, stillAllowed)
            val play = if (focus && action == PioneerSourceRecoveryPolicy.Action.FOCUS_AND_PLAY && !background.sink.hasMediaAudio())
                controller.recoverMediaPlayback(current, intentRevision) {
                    stillAllowed() && readSource() == PioneerSourceRecoveryPolicy.MEDIA_SOURCE && !background.sink.hasMediaAudio()
                } else false
            log("attempt=$action focusGranted=$focus playQueued=$play mediaActive=${background.sink.hasMediaAudio()}")
        } catch (error: Exception) {
            // A failed read must never look like OFF or manufacture a source-return transition.
            policy.reset()
            recoverySink?.cancelVehicleMediaRecovery()
            recoverySink = null
            lastSource = null
            if (!closed && !failureReported) {
                failureReported = true
                log("unavailable=${error.javaClass.simpleName}")
            }
        }
    }

    private fun log(line: String) = PioneerDebugRuntime.log(app, "Audio source recovery: $line")

    override fun close() {
        closed = true
        worker.shutdownNow()
        recoverySink?.cancelVehicleMediaRecovery()
        recoverySink = null
    }

    companion object {
        fun create(context: Context, controller: CarPlayController): PioneerMediaSourceRecovery? =
            if (PioneerUsbPolicy.matchesDevice() && com.shilapi.xcertplay.vehicle.runtime.VehicleAudioRouting.available)
                PioneerMediaSourceRecovery(context, controller) else null
    }
}
