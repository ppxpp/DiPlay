package com.shilapi.xcertplay.btprobe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/** 实机短时 PCM 探针：分别记录音源选择、令牌、焦点和播放消费，不将写入成功当作听感。 */
internal class AudioChannelProbe(
    context: Context,
    private val log: (String) -> Unit,
    private val cancelled: AtomicBoolean,
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val controllerClass = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
    private val controller = controllerClass.getMethod("getInstance").invoke(null)
    private fun current() = controllerClass.getMethod("getCurSource", Int::class.javaPrimitiveType)
        .invoke(controller, 1) as Int
    private fun switch(source: Int): Any? = controllerClass.getMethod("change2Source",
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(controller, 1, source)

    fun inventory() {
        check(PioneerBinder.firmwareKnown())
        log("音频清单 current=${current()}")
        for (field in controllerClass.fields.filter { it.name.startsWith("SOURCE_ID_") }) {
            val source = field.getInt(null)
            val state = runCatching { controllerClass.getMethod("getSourceStatus", Int::class.javaPrimitiveType)
                .invoke(controller, source) }.getOrElse { it.toString() }
            log("音源 ${field.name}=$source status=$state")
        }
        val sources = controllerClass.getMethod("getSourceList", Int::class.javaPrimitiveType)
            .invoke(controller, 1) as? IntArray
        log("前排可选音源=${sources?.contentToString()}")
        val system = Class.forName("android.media.AudioSystem")
        for (field in AudioManager::class.java.fields.filter { it.name.startsWith("STREAM_") && it.type == Int::class.javaPrimitiveType }) {
            val stream = field.getInt(null)
            val token = runCatching { system.getMethod("getTokenName", Int::class.javaPrimitiveType)
                .invoke(null, stream) }.getOrNull()
            log("音频流 ${field.name}=$stream token=$token")
        }
    }

    @Suppress("DEPRECATION")
    fun play(source: Int, stream: Int, token: String?, focusStream: Int, durationMs: Int, restore: Boolean) {
        check(PioneerBinder.firmwareKnown())
        check(source in setOf(0, 200, 201, 202, 203, 204, 205, 300, 301))
        check(stream in setOf(3, 10, 11, 12, 240, 241, 242, 243, 400, 401))
        val original = current()
        val listener = AudioManager.OnAudioFocusChangeListener { log("音频焦点事件=$it source=${current()}") }
        var track: AudioTrack? = null
        var requestedFocus = false
        log("音频实验 BEGIN source=$source stream=$stream token=$token focus=$focusStream original=$original")
        try {
            log("切换音源 result=${switch(source)}")
            Thread.sleep(1000)
            log("切换回读 source=${current()}")
            val attributes = AudioAttributes.Builder().setLegacyStreamType(stream).build()
            val format = AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            track = AudioTrack(attributes, format, 32768, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
            if (token != null) {
                val result = AudioTrack::class.java.getMethod("setTokenName", String::class.java).invoke(track, token)
                log("设置音轨 token=$token result=$result")
            }
            val effective = runCatching {
                AudioTrack::class.java.getDeclaredField("mAttributes").apply { isAccessible = true }
                    .get(track) as AudioAttributes
            }.getOrNull()
            log("音轨 state=${track.state} session=${track.audioSessionId} requestedContent=${attributes.contentType} requestedUsage=${attributes.usage} effectiveContent=${effective?.contentType} effectiveUsage=${effective?.usage} effectiveStream=${track.streamType}")
            if (focusStream >= 0) {
                requestedFocus = true
                log("请求焦点 stream=$focusStream result=${audio.requestAudioFocus(listener, focusStream, AudioManager.AUDIOFOCUS_GAIN)}")
            }
            track.play()
            val pcm = ByteArray(1920)
            val started = SystemClock.elapsedRealtime()
            var frames = 0L
            var writtenBytes = 0L
            var zeroWrites = 0
            var errors = 0
            var nextLog = 0L
            while (!cancelled.get() && SystemClock.elapsedRealtime() - started < durationMs.coerceIn(1000, 30000)) {
                for (i in 0 until 480) {
                    val sample = (1200 * sin(2 * Math.PI * 440 * (frames + i) / 48000)).toInt()
                    pcm[i * 4] = sample.toByte(); pcm[i * 4 + 1] = (sample shr 8).toByte()
                    pcm[i * 4 + 2] = sample.toByte(); pcm[i * 4 + 3] = (sample shr 8).toByte()
                }
                val count = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
                if (count > 0) { writtenBytes += count; frames += count / 4 }
                else if (count == 0) zeroWrites++ else errors++
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed >= nextLog) {
                    log("PCM t=${elapsed}ms source=${current()} head=${track.playbackHeadPosition} bytes=$writtenBytes zeros=$zeroWrites errors=$errors")
                    nextLog += 2000
                }
                Thread.sleep(10)
            }
            log("PCM FINAL source=${current()} head=${track.playbackHeadPosition} bytes=$writtenBytes zeros=$zeroWrites errors=$errors")
        } finally {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (requestedFocus) log("释放焦点 result=${audio.abandonAudioFocus(listener)}")
            if (restore) log("恢复原音源=$original result=${switch(original)}")
            log("音频实验 END source=${current()}")
        }
    }
}
