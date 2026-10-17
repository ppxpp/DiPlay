package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.shilapi.xcertplay.telecom.PhoneAudioRoute
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Explicit local diagnostic: four seconds, statistics only; no PCM retained or transmitted. */
object MicrophoneProbe {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var result = JSONObject().put("state", "idle")
    fun snapshot(): JSONObject = JSONObject(result.toString())

    fun stop() {
        running.set(false)
        worker?.takeUnless { it == Thread.currentThread() }?.let {
            it.join(1500)
            check(!it.isAlive) { "Microphone probe is still stopping" }
        }
    }

    fun start(context: Context, choice: String, report: (String) -> Unit) {
        require(choice in listOf("AUTO", "MIC", "VOICE_RECOGNITION", "VOICE_COMMUNICATION", "BUILTIN_MIC", "MIC_16K", "MIC_16K_NORMAL", "VOICE_COMMUNICATION_16K", "VOICE_BUILTIN", "PIONEER_MIC_16K", "PIONEER_MIC_16K_NORMAL", "CALL_PIPELINE"))
        check(worker?.isAlive != true && running.compareAndSet(false, true)) { "Microphone probe already running" }
        result = JSONObject().put("state", "running").put("choice", choice)
        worker = Thread({
            val manager = context.getSystemService(AudioManager::class.java)
            val oldMode = manager.mode
            var record: AudioRecord? = null
            var micController: Any? = null
            var micOwned = false
            var callMicLease: PioneerMicrophoneLease? = null
            val rate = if (choice.contains("16K")) 16000 else 32000
            val data = JSONObject().put("choice", choice).put("sampleRate", rate).put("previousMode", oldMode)
            try {
                if (choice == "CALL_PIPELINE") {
                    manager.mode = AudioManager.MODE_IN_COMMUNICATION
                    callPipeline(context, data, report)
                    data.put("state", if (running.get()) "complete" else "cancelled")
                    return@Thread
                }
                if (choice == "AUTO") callMicLease = PioneerMicrophoneLease.acquire(context.packageName, report)
                val source = if (callMicLease != null) MediaRecorder.AudioSource.MIC else when (choice) {
                    "MIC", "BUILTIN_MIC", "MIC_16K", "MIC_16K_NORMAL", "PIONEER_MIC_16K", "PIONEER_MIC_16K_NORMAL" -> MediaRecorder.AudioSource.MIC
                    "VOICE_RECOGNITION" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                    "VOICE_COMMUNICATION", "VOICE_COMMUNICATION_16K", "VOICE_BUILTIN" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else -> telephonyMicrophoneSource(false, PhoneAudioRoute.carPlayCallsUseCarAudio(),
                        com.shilapi.xcertplay.network.GeelyHeadUnit.detected)
                }
                data.put("microphoneMuted", manager.isMicrophoneMute)
                if (choice.startsWith("PIONEER_")) {
                    check(com.shilapi.xcertplay.pioneer.PioneerUsbPolicy.matchesDevice())
                    val type = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
                    val controller = type.getMethod("getInstance").invoke(null)
                    micController = controller
                    data.put("vendorTokenBefore", type.getMethod("getCurMicToken").invoke(controller))
                    micOwned = type.getMethod("requestMicOn", String::class.java).invoke(controller, context.packageName) == true
                    data.put("vendorMicGranted", micOwned)
                    data.put("vendorTokenAfter", type.getMethod("getCurMicToken").invoke(controller))
                    check(micOwned) { "Vehicle microphone request refused" }
                    Thread.sleep(150)
                }
                val devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                data.put("inputs", JSONArray().also { list -> devices.forEach { d ->
                    list.put(JSONObject().put("id", d.id).put("type", d.type).put("name", d.productName.toString()))
                } }).put("source", source)
                manager.mode = if (choice.endsWith("16K_NORMAL")) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(min > 0) { "Invalid minimum buffer: $min" }
                val next = AudioRecord.Builder().setAudioSource(source).setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min * 2, 5120)).build()
                record = next
                check(next.state == AudioRecord.STATE_INITIALIZED) { "Recorder not initialized" }
                if (choice == "BUILTIN_MIC" || choice == "VOICE_BUILTIN") {
                    val device = checkNotNull(devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }) { "No built-in microphone" }
                    data.put("preferredDeviceAccepted", next.setPreferredDevice(device))
                }
                next.startRecording()
                check(next.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Recorder did not start" }
                val pcm = ShortArray(640)
                var count = 0L; var nonzero = 0L; var sumSquares = 0.0; var peak = 0; var reads = 0; var empty = 0
                val until = SystemClock.elapsedRealtime() + 4000
                while (running.get() && SystemClock.elapsedRealtime() < until) {
                    val n = next.read(pcm, 0, pcm.size, AudioRecord.READ_NON_BLOCKING)
                    check(n >= 0) { "AudioRecord read error: $n" }
                    reads++
                    if (n == 0) { empty++; Thread.sleep(10) } else {
                        count += n
                        for (i in 0 until n) {
                            val v = pcm[i].toInt(); if (v != 0) nonzero++
                            peak = maxOf(peak, abs(v)); sumSquares += v.toDouble() * v
                        }
                    }
                }
                data.put("samples", count).put("nonzeroSamples", nonzero).put("bytes", count * 2)
                    .put("reads", reads).put("emptyReads", empty).put("peak", peak)
                    .put("rmsDbfs", if (sumSquares > 0 && count > 0) 20 * log10(sqrt(sumSquares / count) / 32768) else "silent")
                    .put("routedDeviceType", next.routedDevice?.type).put("routedDeviceId", next.routedDevice?.id)
                    .put("state", if (running.get()) "complete" else "cancelled")
            } catch (e: Exception) {
                data.put("state", "failed").put("error", "${e.javaClass.simpleName}: ${e.message}")
            } finally {
                runCatching { record?.stop() }; runCatching { record?.release() }
                runCatching { if (manager.mode == (if (choice.endsWith("16K_NORMAL")) AudioManager.MODE_NORMAL else AudioManager.MODE_IN_COMMUNICATION)) manager.mode = oldMode }
                runCatching { callMicLease?.close() }
                if (micOwned) runCatching {
                    val controller = checkNotNull(micController)
                    val type = controller.javaClass
                    if (type.getMethod("getCurMicToken").invoke(controller) == context.packageName) {
                        data.put("vendorMicReleased", type.getMethod("requestMicOff", String::class.java).invoke(controller, context.packageName))
                    }
                }.onFailure { data.put("vendorReleaseError", it.javaClass.simpleName) }
                result = data
                running.set(false)
                runCatching { report("MIC_PROBE $data") }
            }
        }, "diplay-mic-probe").apply { isDaemon = true }
        worker!!.start()
    }
    /** Run the production capture, effects and RTP code against a local packet counter. */
    private fun callPipeline(context: Context, data: JSONObject, report: (String) -> Unit) {
        val diagnostics = java.util.Collections.synchronizedList(mutableListOf<String>())
        java.net.DatagramSocket(0, java.net.InetAddress.getByName("127.0.0.1")).use { receiver ->
            receiver.soTimeout = 100
            val config = com.shilapi.xcertplay.airplay.MicrophoneConfig(
                audioType = "telephony", sampleRate = 32000, channels = 1, payloadType = 96,
                frameMillis = 20, host = java.net.InetAddress.getByName("127.0.0.1"),
                port = receiver.localPort, key = ByteArray(32),
            )
            val uplink = MicrophoneUplink(config, onDiagnostic = { line -> diagnostics.add(line); report(line) },
                applicationPackage = context.packageName)
            try {
                check(uplink.start()) { "Production microphone capture failed" }
                val until = SystemClock.elapsedRealtime() + 4000
                val packet = java.net.DatagramPacket(ByteArray(8192), 8192)
                var packets = 0; var bytes = 0L
                while (running.get() && SystemClock.elapsedRealtime() < until) {
                    try { packet.length = packet.data.size; receiver.receive(packet); packets++; bytes += packet.length }
                    catch (_: java.net.SocketTimeoutException) { }
                }
                data.put("localRtpPackets", packets).put("localRtpBytes", bytes)
            } finally {
                uplink.close()
                synchronized(diagnostics) { data.put("captureDiagnostics", JSONArray(diagnostics)) }
            }
        }
    }

}
