package com.shilapi.xcertplay.pioneer

import android.media.MediaCodecList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Authenticated, bounded, read-only counters; no command lines or process control. */
object PioneerSystemLoad {
    fun snapshot(): JSONObject = JSONObject().put("atNs", System.nanoTime())
        .put("processes", JSONArray(File("/proc").listFiles().orEmpty()
            .filter { it.name.all(Char::isDigit) }.take(512).mapNotNull { process ->
                runCatching {
                    val stat = File(process, "stat").readText()
                    val fields = stat.substringAfterLast(") ").split(' ')
                    JSONObject().put("pid", process.name).put("name", stat.substringAfter('(').substringBeforeLast(')'))
                        .put("userTicks", fields[11].toLong()).put("systemTicks", fields[12].toLong())
                }.getOrNull()
            }))
        .put("thermal", JSONArray(File("/sys/class/thermal").listFiles().orEmpty()
            .filter { it.name.startsWith("thermal_zone") }.take(16).mapNotNull { zone ->
                runCatching { JSONObject().put("type", File(zone, "type").readText().trim())
                    .put("temp", File(zone, "temp").readText().trim())
                    .put("trips", JSONArray(zone.listFiles().orEmpty().filter { it.name.matches(Regex("trip_point_[0-9]+_(temp|type)")) }.take(16).mapNotNull { point ->
                        runCatching { JSONObject().put("name", point.name).put("value", point.readText().trim()) }.getOrNull()
                    })) }.getOrNull()
            }))
        .put("decoders", JSONArray(runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter {
                !it.isEncoder && it.name.startsWith("OMX.Freescale.") && it.supportedTypes.contains("video/avc")
            }.map { info ->
                val caps = info.getCapabilitiesForType("video/avc")
                JSONObject().put("name", info.name)
                    .put("lowLatencyFeature", runCatching { caps.isFeatureSupported("low-latency") }.getOrDefault(false))
                    .put("achievable720pFps", runCatching { caps.videoCapabilities?.getAchievableFrameRatesFor(1280, 720)?.toString() }.getOrNull())
            }
        }.getOrDefault(emptyList())))
}
