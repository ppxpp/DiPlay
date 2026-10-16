package com.shilapi.xcertplay.pioneer

import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Diagnostic-only comparison on existing own-process video input threads. */
object PioneerVideoScheduling {
    fun apply(enabled: Boolean): JSONArray {
        check(PioneerUsbPolicy.matchesDevice()) { "Video scheduling comparison requires verified firmware" }
        val priority = if (enabled) Process.THREAD_PRIORITY_DISPLAY else Process.THREAD_PRIORITY_DEFAULT
        val results = JSONArray()
        File("/proc/self/task").listFiles().orEmpty().forEach { task ->
            val name = runCatching { File(task, "comm").readText().trim() }.getOrNull()
            if (name == "airplay-screen" || name == "carplay-video") {
                val result = runCatching { Process.setThreadPriority(task.name.toInt(), priority) }
                results.put(JSONObject().put("thread", name).put("tid", task.name)
                    .put("priority", priority).put("ok", result.isSuccess))
            }
        }
        return results
    }
}
