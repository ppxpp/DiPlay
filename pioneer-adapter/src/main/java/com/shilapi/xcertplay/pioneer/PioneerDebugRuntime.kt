package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One integration boundary; the module never depends on DiPlay's UI or controller classes. */
object PioneerDebugRuntime {
    @Volatile var port: Int = 0
    @Volatile var listenerState = "未启动"
    @Volatile var lastCommand = ""
    @Volatile var control: ((String) -> Unit)? = null
    @Volatile var hostState: (() -> JSONObject)? = null
    @Volatile var videoSettings: ((JSONObject) -> JSONObject)? = null
    @Volatile var videoGesture: ((String) -> JSONObject)? = null
    @Volatile var videoTap: ((JSONObject) -> JSONObject)? = null
    @Volatile var videoSnapshot: (() -> ByteArray)? = null
    @Volatile var hostUi: ((String) -> JSONObject)? = null
    @Volatile var nativeCryptoComparisonEnabled: Boolean? = null
    @Volatile var phoneDiagnosticsEnabled = false
    @Volatile var asyncSessionLogs = true
    @Volatile var freescaleNoReorder = false
    // Compatibility view for TCP status/control; production playback reads its own policy.
    val appearanceDeclarationsEnabled: Boolean
        get() = PioneerPlaybackPolicy.appearanceDeclarationsEnabled
    val audioClockTrimOverride: Boolean?
        get() = PioneerPlaybackPolicy.audioClockTrimOverride
    var audioClockTrimEnabled: Boolean
        get() = PioneerPlaybackPolicy.audioClockTrimEnabled
        set(value) { PioneerPlaybackPolicy.audioClockTrimEnabled = value }
    @Volatile var touchCoalescingComparisonEnabled = false
    @Volatile var lwipReceiveWaitMillis = 1
    @Volatile var usbTxBatchFrames = 8
    @Volatile var redact: (String) -> String? = { it }
    private val main = Handler(Looper.getMainLooper())
    private val logLock = Any()

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, PioneerDebugService::class.java))
    }
    fun logFile(context: Context) = File(context.filesDir, "pioneer-debug/adapter.log")
    fun log(context: Context, message: String) {
        val safe = redact(message) ?: return
        android.util.Log.i("PioneerDebug", safe)
        synchronized(logLock) {
            runCatching {
                val file = logFile(context); file.parentFile?.mkdirs()
                if (file.length() > 512 * 1024) file.copyTo(File(file.parent, "previous.log"), overwrite = true).also { file.writeText("") }
                file.appendText(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " $safe\n")
            }
        }
    }
    fun request(context: Context, command: String) {
        check(control != null) { "请先打开 DiPlay 主页面，让调试控制入口完成初始化" }
        if (command == "start-wired") {
            PioneerDebugUpdateGuard.requireStartAllowed()
            PioneerConnectionTrace.requestStart()
        }
        lastCommand = "$command queued"
        main.post {
            runCatching {
                if (command == "start-wired") PioneerDebugUpdateGuard.requireStartAllowed()
                control?.invoke(command) ?: error("DiPlay control detached")
            }
                .onSuccess { lastCommand = "$command dispatched"; log(context, lastCommand) }
                .onFailure { lastCommand = "$command failed: ${it.message}"; log(context, lastCommand) }
        }
    }
    fun snapshot(): JSONObject {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching { hostState?.invoke() ?: JSONObject().put("hostAttached", false) }
            .getOrElse { JSONObject().put("hostStateError", it.toString()) }
        var value = JSONObject().put("hostAttached", false)
        val latch = CountDownLatch(1)
        main.post { try { value = runCatching { hostState?.invoke() ?: value }.getOrElse { JSONObject().put("hostStateError", it.toString()) } } finally { latch.countDown() } }
        if (!latch.await(2, TimeUnit.SECONDS)) return JSONObject().put("hostStateTimeout", true)
        return value
    }

    fun runHostUi(action: String): JSONObject {
        require(action in listOf("AUDIO_TAKE", "AUDIO_RELEASE", "SETTINGS_OPEN", "SETTINGS_CLOSE", "BACKGROUND", "FOREGROUND", "MIC_PROBE_AUTO", "MIC_PROBE_MIC", "MIC_PROBE_VOICE_RECOGNITION", "MIC_PROBE_VOICE_COMMUNICATION", "MIC_PROBE_BUILTIN_MIC", "MIC_PROBE_MIC_16K", "MIC_PROBE_MIC_16K_NORMAL", "MIC_PROBE_VOICE_COMMUNICATION_16K", "MIC_PROBE_VOICE_BUILTIN", "MIC_PROBE_PIONEER_MIC_16K", "MIC_PROBE_PIONEER_MIC_16K_NORMAL", "MIC_PROBE_CALL_PIPELINE"))
        val latch = CountDownLatch(1)
        var result: Result<JSONObject>? = null
        main.post {
            try { result = runCatching { hostUi?.invoke(action) ?: error("CarPlay host unavailable") } }
            finally { latch.countDown() }
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "Host UI operation still pending; inspect fresh status" }
        return checkNotNull(result).getOrThrow()
    }

    fun tapVideo(request: JSONObject): JSONObject {
        val latch = CountDownLatch(1)
        var result: Result<JSONObject>? = null
        main.post {
            try { result = runCatching { videoTap?.invoke(request) ?: error("CarPlay host unavailable") } }
            finally { latch.countDown() }
        }
        check(latch.await(2, TimeUnit.SECONDS)) { "CarPlay main thread did not respond" }
        return checkNotNull(result).getOrThrow()
    }

    /** Called by a TCP worker; only schedules a bounded gesture on the host UI thread. */
    fun startVideoGesture(kind: String = "SWIPE"): JSONObject {
        val latch = CountDownLatch(1)
        var result: Result<JSONObject>? = null
        main.post {
            try { result = runCatching { videoGesture?.invoke(kind) ?: error("CarPlay host unavailable") } }
            finally { latch.countDown() }
        }
        check(latch.await(2, TimeUnit.SECONDS)) { "CarPlay main thread did not respond" }
        return checkNotNull(result).getOrThrow()
    }
}
