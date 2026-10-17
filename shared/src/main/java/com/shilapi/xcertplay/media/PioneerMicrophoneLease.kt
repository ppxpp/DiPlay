package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.pioneer.PioneerLwipRuntime
import com.shilapi.xcertplay.pioneer.PioneerUsbPolicy
import java.io.Closeable

/** The wired Pioneer microphone is gated separately from Android's RECORD_AUDIO permission. */
internal class PioneerMicrophoneLease private constructor(
    private val controller: Any,
    private val token: String,
    private val report: (String) -> Unit,
) : Closeable {
    private var closed = false
    override fun close() = synchronized(PioneerMicrophoneLease::class.java) {
        if (closed) return@synchronized
        closed = true
        users--
        if (users > 0) return@synchronized
        runCatching {
            val type = controller.javaClass
            if (type.getMethod("getCurMicToken").invoke(controller) == token) {
                val released = type.getMethod("requestMicOff", String::class.java).invoke(controller, token)
                report("Microphone: Pioneer lease released=$released")
            }
        }.onFailure { runCatching { report("Microphone: Pioneer release failed=${it.javaClass.simpleName}") } }
    }

    companion object {
        private var users = 0
        fun acquire(token: String?, report: (String) -> Unit): PioneerMicrophoneLease? = synchronized(PioneerMicrophoneLease::class.java) {
            if (!PioneerUsbPolicy.matchesDevice() || !PioneerLwipRuntime.active) return@synchronized null
            val ownToken = checkNotNull(token) { "Microphone application identity unavailable" }
            val type = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
            val controller = checkNotNull(type.getMethod("getInstance").invoke(null))
            val granted = type.getMethod("requestMicOn", String::class.java).invoke(controller, ownToken) == true
            runCatching { report("Microphone: Pioneer lease granted=$granted") }
            check(granted) { "Vehicle microphone request refused" }
            users++
            PioneerMicrophoneLease(controller, ownToken, report)
        }
    }
}
