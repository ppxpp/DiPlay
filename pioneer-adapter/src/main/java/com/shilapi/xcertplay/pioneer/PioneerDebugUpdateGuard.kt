package com.shilapi.xcertplay.pioneer

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Upload owns one lease through the actual installer handoff, all transitions on main. */
object PioneerDebugUpdateGuard {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var owner: Any? = null
    private var installPath: String? = null

    fun isBusy(): Boolean = owner != null
    fun requireStartAllowed() { check(!isBusy()) { "APK update in progress; wait for installer handoff" } }

    internal fun requireIdle(state: JSONObject = PioneerDebugRuntime.snapshot()) {
        check(!state.has("hostStateError") && !state.optBoolean("hostStateTimeout") &&
            listOf("controllerRunning", "backgroundSession", "hostAttached").all { state.opt(it) == false } &&
            !PioneerLwipRuntime.active) { "请先 stop 并等待会话状态明确空闲，再更新（未知状态不允许升级）" }
    }

    internal fun begin(): Any {
        val token = Any()
        try {
            return onMain {
                requireStartAllowed()
                requireIdle()
                owner = token
                token
            }
        } catch (error: Exception) {
            // If a slow host snapshot finishes after the worker times out, undo its late lease.
            release(token)
            throw error
        }
    }

    internal fun handoff(token: Any, file: File, action: () -> Unit) = onMain {
        check(owner === token) { "Update lease expired" }
        requireIdle()
        installPath = file.absolutePath
        try { action() } catch (error: Throwable) { releaseOnMain(token); throw error }
        // If Android never delivers the activity intent, do not leave connection startup locked.
        main.postDelayed({ releaseOnMain(token) }, 10_000)
    }

    /** Called on main immediately before ACTION_INSTALL_PACKAGE, closing the activity delivery gap. */
    internal fun install(file: File, action: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val token = checkNotNull(owner) { "Update lease expired; upload APK again" }
        check(installPath == file.absolutePath) { "APK is not the pending update" }
        val marker = File(file.path + ".installing")
        try {
            requireIdle()
            // Keep the installer-readable APK through concurrent cache maintenance and process
            // replacement; an in-memory flag would be lost when DiPlay itself is upgraded.
            check(marker.createNewFile() || marker.isFile) { "Cannot retain installer APK" }
            try { action() } catch (error: Throwable) { marker.delete(); throw error }
        } finally { releaseOnMain(token) }
    }

    internal fun canDelete(file: File): Boolean {
        val marker = File(file.path + ".installing")
        return !marker.exists() || System.currentTimeMillis() - marker.lastModified() > 86_400_000L
    }

    /** Cache cleanup and upload admission acquire the same main-thread lease. */
    internal fun <T> withCacheMaintenance(action: () -> T): T {
        val token = begin()
        try { return action() } finally { release(token) }
    }

    internal fun release(token: Any) { main.post { releaseOnMain(token) } }
    private fun releaseOnMain(token: Any) {
        if (owner === token) { installPath = null; owner = null }
    }

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        check(main.post(task)) { "Main thread unavailable" }
        try { return task.get(3, TimeUnit.SECONDS) }
        catch (error: Exception) { task.cancel(false); throw error }
    }
}
