package com.shihab.diplay.btproxy

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The bounded log that ADB keeps reading from `files/probe-log.txt`, mirrored in memory so the
 * screen repaints when a line arrives instead of re-reading the whole file once per second.
 */
object ProxyLog {
    private const val MAX_LINES = 200
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayList<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var hydrated = false

    @Synchronized
    fun append(context: Context, message: String) {
        val line = "${stamp.format(Date())} $message"
        Log.i("BtProxyProbe", line)
        ensureHydrated(context)
        lines.add(line)
        if (lines.size > MAX_LINES) lines.removeAt(0)
        persist(context)
        notifyListeners()
    }

    @Synchronized
    fun clear(context: Context) {
        ensureHydrated(context)
        lines.clear()
        persist(context)
        append(context, "phase=LOG event=CLEARED")
    }

    /** The retained tail, hydrated from the file the first time anything asks for it. */
    @Synchronized
    fun snapshot(context: Context): List<String> {
        ensureHydrated(context)
        return lines.toList()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        main.post(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun ensureHydrated(context: Context) {
        if (hydrated) return
        hydrated = true
        runCatching {
            val file = File(context.filesDir, "probe-log.txt")
            if (file.isFile) lines.addAll(file.readLines().takeLast(MAX_LINES))
        }
    }

    private fun persist(context: Context) {
        runCatching {
            val body = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
            File(context.filesDir, "probe-log.txt").writeText(body)
        }
    }

    private fun notifyListeners() {
        main.post { listeners.forEach { it() } }
    }
}
