package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime
import com.shilapi.xcertplay.pioneer.PioneerUsbPolicy
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Local integration kept out of the upstream settings/controller implementation. */
internal object PioneerDebugIntegration {
    private val main = Handler(Looper.getMainLooper())
    private var host = WeakReference<Activity>(null)
    private var retry: (() -> Unit)? = null

    fun ensure(context: Context) {
        val app = context.applicationContext
        PioneerDebugRuntime.redact = { DiagnosticRedactor.redact(it) }
        PioneerDebugRuntime.videoSettings = { request ->
            val flags = listOf("lowLatencyDecoder", "smoothVideo", "directVideoOutput")
            // Validate the whole request before persisting any field.
            flags.filter(request::has).forEach { require(request.get(it) is Boolean) { "$it must be boolean" } }
            if (request.has("fps")) require(request.get("fps") is Int && request.getInt("fps") in listOf(30, 60)) {
                "fps must be 30 or 60"
            }
            if (request.has("resolutionPercent")) require(request.get("resolutionPercent") is Int &&
                request.getInt("resolutionPercent") in 30..160) { "resolutionPercent must be 30..160" }
            if (request.has("lowLatencyDecoder")) AirPlayPersistence.saveLowLatencyDecoder(app, request.getBoolean("lowLatencyDecoder"))
            if (request.has("smoothVideo")) AirPlayPersistence.saveSmoothVideo(app, request.getBoolean("smoothVideo"))
            if (request.has("directVideoOutput")) AirPlayPersistence.saveDirectVideoOutput(app, request.getBoolean("directVideoOutput"))
            if (request.has("fps")) AirPlayPersistence.saveFps(app, request.getInt("fps"))
            if (request.has("resolutionPercent")) AirPlayPersistence.saveDisplayScalePercent(app, request.getInt("resolutionPercent"))
            JSONObject().put("fps", AirPlayPersistence.loadFps(app))
                .put("resolutionPercent", AirPlayPersistence.loadDisplayScalePercent(app))
                .put("lowLatencyDecoder", AirPlayPersistence.loadLowLatencyDecoder(app))
                .put("smoothVideo", AirPlayPersistence.loadSmoothVideo(app))
                .put("directVideoOutput", AirPlayPersistence.loadDirectVideoOutput(app))
        }
        PioneerDebugRuntime.control = { command ->
            when (command) {
                "stop" -> stopHost()
                "start-wired" -> startWired(app)
                "retry-vpn" -> if (host.get()?.isFinishing == false && retry != null) retry?.invoke() else startWired(app)
                else -> error("Unsupported DiPlay command")
            }
        }
        if (PioneerDebugRuntime.hostState == null) PioneerDebugRuntime.hostState = ::idleState
        if (PioneerUsbPolicy.matchesDevice() && !AirPlayPersistence.loadWirelessEnabled(app)) {
            runCatching { PioneerDebugRuntime.start(app) }
                .onFailure { PioneerDebugRuntime.log(app, "TCP debug start failed: ${it.message}") }
        }
    }
    fun attachHost(activity: Activity, state: () -> JSONObject, retryVpn: () -> Unit,
                   videoGesture: () -> JSONObject) {
        ensure(activity)
        host = WeakReference(activity)
        retry = retryVpn
        PioneerDebugRuntime.hostState = state
        PioneerDebugRuntime.videoGesture = videoGesture
    }
    fun detachHost(activity: Activity) {
        if (host.get() === activity) {
            host.clear(); retry = null
            PioneerDebugRuntime.hostState = ::idleState
            PioneerDebugRuntime.videoGesture = null
        }
    }
    private fun idleState() = JSONObject().put("hostAttached", false)
        .put("backgroundSession", CarPlayBackgroundSession.hasSession())
        .put("controllerRunning", CarPlayBackgroundSession.snapshot() != null)

    private fun stopHost() {
        CarPlayBackgroundSession.stop {
            main.post { host.get()?.finish() }
        }
    }
    private fun startWired(context: Context) {
        CarPlayBackgroundSession.stop {
            main.post {
                host.get()?.finish()
                AirPlayPersistence.saveWirelessEnabled(context, false)
                context.startActivity(Intent(context, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
