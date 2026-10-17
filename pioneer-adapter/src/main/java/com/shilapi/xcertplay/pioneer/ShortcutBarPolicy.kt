package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Suppresses the Pioneer UIFW ShortcutBar collapsed handle (mCloseFrame, the bottom arrow) while
 * DiPlay owns the foreground. The arrow is a SysUI overlay driven only by this broadcast and by
 * UIFW view state, so AOSP immersive flags never reach it.
 *
 * Foreground is tracked process-wide via [ProcessLifecycleOwner]: hidden on the app's ON_START and
 * handed back on ON_STOP. Its built-in grace period absorbs internal activity switches
 * (DiPlayActivity↔CarPlayHost↔video) and config-change recreates, so the handle never flickers back
 * while DiPlay stays on top.
 */
object ShortcutBarPolicy {
    private const val TAG = "DiPlay-ShortcutBar"
    private const val ACTION = "jp.pioneer.ceam.ui.show.shortcutbar"
    private const val BAR_KEY = "BAR_KEY"
    private var attached = false

    /** Register once at process start; no-op on head units that are not the A-navigation Camry. */
    fun attach(appContext: Context) {
        if (attached || !PioneerUsbPolicy.matchesDevice()) return
        attached = true
        val app = appContext.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> send(app, show = false)
                Lifecycle.Event.ON_STOP -> send(app, show = true)
                else -> Unit
            }
        })
    }

    private fun send(context: Context, show: Boolean) {
        try {
            // SysUI and StatusImp receivers check neither uid nor permission; BAR_KEY defaults to true,
            // so the hide branch must pass false explicitly.
            context.sendBroadcast(Intent(ACTION).putExtra(BAR_KEY, show))
            Log.i(TAG, "shortcutbar show=$show")
        } catch (error: Throwable) {
            Log.w(TAG, "shortcutbar broadcast failed: ${error.javaClass.simpleName}")
        }
    }
}
