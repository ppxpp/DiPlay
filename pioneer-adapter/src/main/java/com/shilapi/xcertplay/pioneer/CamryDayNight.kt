package com.shilapi.xcertplay.pioneer

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

/** Process-wide vehicle day/night subscription, isolated from UI and test controls. */
object CamryDayNight {
    private const val TAG = "DiPlay-CamryNight"
    private const val CONTROLLER = "jp.pioneer.ceam.widget.MiscManager.MiscController"
    private const val BRIDGE = "com.shilapi.xcertplay.pioneer.CamryDayNightListenerBridge"
    private const val MAX_ATTEMPTS = 8
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedMapOf<Any, (Boolean) -> Unit>()
    private val notifications = AtomicLong()
    // The vendor holds only a WeakReference: retain both objects after successful registration.
    private var listener: Any? = null
    private var listenerRef: WeakReference<Any>? = null
    private var registered = false
    private var querying = false
    private var attempts = 0
    private var retryPending = false
    private val retry = Runnable {
        retryPending = false
        initialize()
    }
    @Volatile var latestNight: Boolean? = null
        private set

    /** A later host opening can retry an exhausted initialization; success never registers twice. */
    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::start)
            return
        }
        if (!PioneerUsbPolicy.matchesDevice() || querying || retryPending) return
        if (registered && latestNight != null) return
        attempts = 0
        initialize()
    }

    private fun initialize() {
        if (querying) return
        attempts++
        try {
            val type = Class.forName(CONTROLLER)
            val instance = type.getMethod("getInstance").invoke(null)
                ?: error("MiscController is not ready")
            val read = type.getMethod("getDayNightSts", java.lang.Integer::class.java)
            if (!registered) {
                try {
                    val bridge = Class.forName(BRIDGE).getConstructor(Function1::class.java)
                        .newInstance({ status: Int ->
                            val night = decode(status)
                            if (night != null) {
                                val revision = notifications.incrementAndGet()
                                main.post {
                                    if (notifications.get() == revision) publish(night, "notification")
                                }
                            } else Log.w(TAG, "Ignored unknown notification status=$status")
                        })
                    val ref = WeakReference(bridge)
                    type.getMethod("registerMiscManagerListener", WeakReference::class.java).invoke(instance, ref)
                    listener = bridge
                    listenerRef = ref
                    registered = true
                    Log.i(TAG, "CEAM listener registered")
                } catch (error: Throwable) {
                    Log.w(TAG, "CEAM listener unavailable: ${error.javaClass.simpleName}")
                }
            }
            readInitial(instance, read)
        } catch (error: Throwable) {
            Log.w(TAG, "CEAM initialization attempt=$attempts: ${error.javaClass.simpleName}")
            scheduleRetry()
        }
    }

    /** An arriving notification wins over an older Binder read. Never block startup on the read. */
    private fun readInitial(instance: Any, read: Method) {
        querying = true
        val revision = notifications.get()
        Thread({
            val night = try {
                // The vendor mutates this boxed output argument; use an uncached sentinel.
                val status: Any = java.lang.Integer.valueOf(-9999)
                if (read.invoke(instance, status) == 0) decode((status as Number).toInt()) else null
            } catch (error: Throwable) {
                Log.w(TAG, "CEAM initial read failed: ${error.javaClass.simpleName}")
                null
            }
            main.post {
                querying = false
                if (night != null && notifications.get() == revision) publish(night, "initial-read")
                if (!registered || latestNight == null) scheduleRetry()
            }
        }, "Camry-night-read").start()
    }

    private fun scheduleRetry() {
        if (retryPending || attempts >= MAX_ATTEMPTS) return
        retryPending = true
        main.postDelayed(retry, (attempts * 1_000L).coerceAtMost(10_000L))
    }

    /** Main-thread observer; retained state is delivered immediately. Dispose with the host. */
    fun observe(changed: (Boolean) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        val token = Any()
        observers[token] = changed
        latestNight?.let(changed)
        return { observers.remove(token) }
    }

    private fun decode(status: Int): Boolean? = when (status) {
        0 -> false
        1 -> true
        else -> null
    }

    private fun publish(night: Boolean, source: String) {
        if (latestNight == night) return
        latestNight = night
        Log.i(TAG, "mode=${if (night) "night" else "day"} source=$source")
        observers.values.toList().forEach { it(night) }
    }
}
