package com.shilapi.xcertplay.pioneer

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Process-wide CEAM subscription; never retains an Activity or loads vendor types on other devices. */
object CamryDayNight {
    private const val TAG = "DiPlay-CamryNight"
    private const val CONTROLLER = "jp.pioneer.ceam.widget.MiscManager.MiscController"
    private const val BRIDGE = "com.shilapi.xcertplay.pioneer.CamryDayNightListenerBridge"
    private val main = Handler(Looper.getMainLooper())
    private val observers = linkedMapOf<Any, (Boolean) -> Unit>()
    private val notifications = AtomicLong()
    private var started = false
    // CEAM only holds a WeakReference. Keep both the listener and the reference alive for the process.
    private var listener: Any? = null
    private var listenerRef: WeakReference<Any>? = null
    @Volatile var latestNight: Boolean? = null
        private set

    /** Call on the main thread when opening either the launcher or the direct USB host. */
    fun start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::start)
            return
        }
        if (started) return
        started = true
        if (!PioneerUsbPolicy.matchesDevice()) return
        try {
            val type = Class.forName(CONTROLLER)
            val controller = type.getMethod("getInstance").invoke(null) ?: return
            val read = type.getMethod("getDayNightSts", java.lang.Integer::class.java)
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
                listener = bridge
                listenerRef = ref
                type.getMethod("registerMiscManagerListener", WeakReference::class.java).invoke(controller, ref)
                Log.i(TAG, "CEAM listener registered")
            } catch (error: Throwable) {
                // Reading still works if this firmware does not expose the listener API.
                Log.w(TAG, "CEAM listener unavailable: ${error.javaClass.simpleName}")
            }
            // Do not block startup/connection on the synchronous Binder read. Register first so a
            // notification arriving during the query wins over the potentially older query result.
            val revision = notifications.get()
            val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "Camry-night-read") }
            worker.execute {
                try {
                    // The vendor API mutates this boxed output argument. Never use a cached Integer.
                    val status: Any = java.lang.Integer.valueOf(-9999)
                    val result = read.invoke(controller, status) as? Int
                    val night = if (result == 0) decode((status as Number).toInt()) else null
                    main.post {
                        if (night != null && notifications.get() == revision) publish(night, "initial-read")
                        else if (night == null) Log.w(TAG, "CEAM initial read ignored result=$result status=$status")
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "CEAM initial read failed: ${error.javaClass.simpleName}")
                } finally {
                    worker.shutdown()
                }
            }
        } catch (error: Throwable) {
            // Includes missing classes, incompatible firmware and vendor linkage/security errors.
            Log.w(TAG, "CEAM day/night unavailable: ${error.javaClass.simpleName}")
        }
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
