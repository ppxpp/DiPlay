package com.shilapi.xcertplay.connection

import android.content.Context
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.orchestration.progress.ConnectionProgress
import com.shilapi.xcertplay.orchestration.progress.ConnectionStep
import com.shilapi.xcertplay.orchestration.progress.ConnectionTiming
import com.shilapi.xcertplay.pioneer.PioneerLwipRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/** Local, bounded, mode-specific history; only successful first-frame samples enter it. */
internal class ConnectionProgressHistory(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("connection_progress_v1", Context.MODE_PRIVATE)

    fun create(config: CarPlayRuntimeConfig, previous: ConnectionProgress?): ConnectionProgress {
        val wireless = config.transport == CarPlayTransport.WIRELESS
        val proxy = wireless && config.wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI && config.remoteRfcommGateway != null
        val link = if (wireless) "wireless-${config.wirelessHotspotMode.name}-${if (proxy) "proxy" else "native"}"
            else "usb-${if (PioneerLwipRuntime.enabled(context)) "lwip" else "framework"}"
        val profile = "$link-${config.mfiTarget.name}"
        val samples = read(profile)
        val baseline = if (samples.isEmpty()) null else ConnectionTiming(
            ConnectionStep.entries.associateWith { step -> median(samples.map { it.durations.getValue(step) }) },
            median(samples.map { it.totalMillis }),
        )
        val continuing = previous?.takeIf { !it.hasRendered && it.profile == profile }
        return ConnectionProgress(
            profile, wireless, (continuing?.attempt ?: 0) + 1,
            continuing?.overallStartedMillis ?: ConnectionProgress.monotonicMillis(),
            baseline, samples.size,
            onCompleted = { timing ->
                // Enqueue only: no JSON, preference writes or file I/O on the video/protocol thread.
                writer.execute {
                    runCatching {
                        val updated = (read(profile) + timing).takeLast(MAX_SAMPLES)
                        val json = JSONArray()
                        updated.forEach { sample ->
                            val row = JSONObject().put("total", sample.totalMillis)
                            sample.durations.forEach { (step, duration) -> row.put(step.key, duration) }
                            json.put(row)
                        }
                        prefs.edit().putString(profile, json.toString()).apply()
                    }
                }
            },
        )
    }

    private fun read(profile: String): List<ConnectionTiming> = runCatching {
        val json = JSONArray(prefs.getString(profile, "[]"))
        (maxOf(0, json.length() - MAX_SAMPLES) until json.length()).mapNotNull { index ->
            val row = json.optJSONObject(index) ?: return@mapNotNull null
            val total = row.optLong("total", -1)
            val durations = ConnectionStep.entries.associateWith { row.optLong(it.key, -1) }
            if (total !in 1..180_000 || durations.values.any { it < 0 || it > total } || durations.values.sum() != total) null
            else ConnectionTiming(durations, total)
        }
    }.getOrDefault(emptyList())

    private fun median(values: List<Long>): Long = values.sorted().let { sorted ->
        if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    companion object {
        private const val MAX_SAMPLES = 20
        private val writer = Executors.newSingleThreadExecutor { Thread(it, "Connection-history") }
    }
}
