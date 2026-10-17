package com.shilapi.xcertplay.pioneer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** usbcheck-compatible JSON-line protocol. No shell, root, or automatic VPN authorization. */
class PioneerDebugService : Service() {
    @Volatile private var closed = false
    @Volatile private var listener: ServerSocket? = null
    private val connections = PioneerDebugConnections(::handle)

    override fun onCreate() {
        super.onCreate()
        PioneerDebugRuntime.phoneDiagnosticsEnabled = getSharedPreferences("pioneer-debug", MODE_PRIVATE)
            .getBoolean("phone_auth_diagnostics", false)
        PioneerDebugRuntime.usbTxBatchFrames = getSharedPreferences("pioneer-debug", MODE_PRIVATE)
            .getInt("usb_tx_batch_frames", 8).takeIf { it in listOf(1, 4, 8) } ?: 8
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("pioneer-debug", "DiPlay 局域网调试", NotificationManager.IMPORTANCE_LOW))
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "pioneer-debug") else Notification.Builder(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, PioneerDebugActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(99110, builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("DiPlay 局域网调试").setContentText("点击查看地址与端口").setContentIntent(open).build())
        Thread(::listen, "pioneer-debug-listener").start()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    private fun listen() {
        PioneerDiagnostics.enabled = getSharedPreferences("pioneer-debug", MODE_PRIVATE).getBoolean("lwip_diagnostics", true)
        // Keep 9911/9912 for the existing pinned AirPlay channels when 8721 is free.
        for (port in listOf(8721, 9911, 9912)) {
            val server = ServerSocket()
            try {
                server.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), port), 4)
                if (closed) { server.close(); return }
                listener = server; PioneerDebugRuntime.port = port; PioneerDebugRuntime.listenerState = "监听中"
                PioneerDebugRuntime.log(this, "TCP_DEBUG_LISTEN 0.0.0.0:$port")
                break
            } catch (error: Exception) { server.close(); PioneerDebugRuntime.log(this, "TCP bind $port failed: ${error.message}") }
        }
        val server = listener ?: run { PioneerDebugRuntime.listenerState = "8721 / 9911 / 9912 均不可用"; stopSelf(); return }
        while (!closed) {
            try {
                connections.accept(server.accept())
            } catch (error: Exception) { if (!closed) PioneerDebugRuntime.log(this, "TCP accept failed: ${error.message}"); break }
        }
    }

    private fun handle(socket: Socket, request: JSONObject) {
        val reply = JSONObject().put("ok", true)
        when (val command = request.getString("cmd")) {
            "async-session-logs", "freescale-reorder" -> {
                require(PioneerUsbPolicy.matchesDevice())
                val mode = request.getString("mode")
                require(mode in listOf("ON", "OFF"))
                if (request.getString("cmd") == "async-session-logs") PioneerDebugRuntime.asyncSessionLogs = mode == "ON"
                else PioneerDebugRuntime.freescaleNoReorder = mode == "ON"
                send(socket, reply.put("mode", mode).put("message", "日志立即生效；解码重排下次连接生效"))
            }
            "audio-clock-trim" -> {
                require(PioneerUsbPolicy.matchesDevice())
                val mode = request.getString("mode")
                require(mode in listOf("ON", "OFF"))
                PioneerDebugRuntime.audioClockTrimEnabled = mode == "ON"
                PioneerDebugRuntime.log(this, "AUDIO_CLOCK_TRIM enabled=${PioneerDebugRuntime.audioClockTrimEnabled}")
                send(socket, reply.put("mode", mode).put("message", "音乐下次写入生效；进程重启恢复设备默认"))
            }
            "audio-source" -> {
                require(PioneerUsbPolicy.matchesDevice())
                val mode = request.optString("mode", "STATUS")
                require(mode in listOf("STATUS", "FM", "WECARFLOW", "OFF", "NEXT", "BT"))
                val cls = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
                val controller = cls.getMethod("getInstance").invoke(null)
                fun read(name: String): Int = cls.getMethod(name, Int::class.javaPrimitiveType).invoke(controller, 1) as Int
                val previous = read("getCurSource")
                val result = when (mode) {
                    "STATUS" -> null
                    "NEXT" -> cls.getMethod("change2NextSource", Int::class.javaPrimitiveType).invoke(controller, 1)
                    else -> cls.getMethod("change2Source", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        .invoke(controller, 1, when (mode) { "FM" -> 201; "OFF" -> 0; "BT" -> 205; else -> 300 })
                }
                val state = JSONObject().put("previous", previous).put("source", read("getCurSource"))
                    .put("mix", read("getCurMix")).put("soundSwitch", read("getCurSoundSwitchStatus"))
                    .put("mode", mode).put("result", result ?: JSONObject.NULL)
                PioneerDebugRuntime.log(this, "AUDIO_SOURCE $state")
                send(socket, reply.put("audio", state))
            }
            "system-load" -> send(socket, reply, PioneerSystemLoad.snapshot().toString(2).toByteArray())
            "touch-coalescing" -> {
                require(PioneerUsbPolicy.matchesDevice())
                val mode = request.getString("mode")
                require(mode in listOf("ON", "OFF"))
                PioneerDebugRuntime.touchCoalescingComparisonEnabled = mode == "ON"
                send(socket, reply.put("mode", mode))
            }
            "lwip-poll-wait" -> {
                require(PioneerUsbPolicy.matchesDevice())
                val millis = request.getInt("millis")
                require(millis in listOf(1, 2, 4))
                PioneerDebugRuntime.lwipReceiveWaitMillis = millis
                send(socket, reply.put("millis", millis))
            }
            "video-priority" -> {
                val mode = request.getString("mode")
                require(mode in listOf("ON", "OFF"))
                send(socket, reply.put("threads", PioneerVideoScheduling.apply(mode == "ON")))
            }
            "native-crypto" -> {
                val mode = request.getString("mode")
                require(mode in listOf("ON", "OFF"))
                require(mode == "OFF" || (PioneerUsbPolicy.matchesDevice() && PioneerNativeCrypto.available)) { "Native crypto unavailable" }
                PioneerDebugRuntime.nativeCryptoComparisonEnabled = mode == "ON"
                PioneerDebugRuntime.log(this, "CRYPTO_COMPARISON native=${mode == "ON"}")
                send(socket, reply.put("mode", mode).put("message", "运行时对照；不写入偏好，重启恢复默认"))
            }
            "video-snapshot" -> {
                val bytes = PioneerDebugRuntime.videoSnapshot?.invoke() ?: error("CarPlay host unavailable")
                send(socket, reply.put("format", "png"), bytes)
            }
            "status" -> send(socket, status().put("ok", true))
            "video-settings" -> {
                val settings = PioneerDebugRuntime.videoSettings?.invoke(request) ?: error("Open DiPlay first")
                send(socket, reply.put("settings", settings).put("message", "设置下次连接生效；当前会话不变"))
            }
            "restart-app" -> {
                require(PioneerUsbPolicy.matchesDevice())
                val intent = android.content.Intent().setClassName(packageName, "com.shilapi.xcertplay.DiPlayActivity")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pending = PendingIntent.getActivity(this, 99112, intent,
                    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val alarms = getSystemService(android.app.AlarmManager::class.java)
                PioneerDebugRuntime.log(this, "SELF_PROCESS_RESTART scheduled pid=${android.os.Process.myPid()}")
                send(socket, reply.put("message", "本应用进程将在500ms后结束，随后3秒重新打开；请检测新PID及新鲜状态"))
                Thread({
                    Thread.sleep(500L)
                    // Arm immediately before killing, independent of a stalled UI thread.
                    alarms.setExact(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        android.os.SystemClock.elapsedRealtime() + 3_000L, pending)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, "self-restart-probe").start()
            }
            "host-ui" -> {
                require(PioneerUsbPolicy.matchesDevice())
                send(socket, reply.put("ui", PioneerDebugRuntime.runHostUi(request.getString("action"))))
            }
            "video-tap" -> send(socket, reply.put("tap", PioneerDebugRuntime.tapVideo(request)))
            "video-gesture" -> send(socket, reply.put("gesture", PioneerDebugRuntime.startVideoGesture(request.optString("kind", "SWIPE"))))
            "network" -> send(socket, reply, networkDiagnostics().toString(2).toByteArray())
            "runtime" -> send(socket, reply, runtimeDiagnostics().toString(2).toByteArray())
            "usb-tx-batch" -> {
                val count = request.getInt("frames")
                require(count in listOf(1, 4, 8))
                PioneerDebugRuntime.usbTxBatchFrames = count
                getSharedPreferences("pioneer-debug", MODE_PRIVATE).edit().putInt("usb_tx_batch_frames", count).apply()
                send(socket, reply.put("frames", count).put("message", "立即生效；仅合并已排队数据，不等待凑包"))
            }
            "phone-diagnostics" -> {
                val mode = request.getString("mode")
                require(mode in listOf("ON", "OFF"))
                PioneerDebugRuntime.phoneDiagnosticsEnabled = mode == "ON"
                getSharedPreferences("pioneer-debug", MODE_PRIVATE).edit()
                    .putBoolean("phone_auth_diagnostics", PioneerDebugRuntime.phoneDiagnosticsEnabled).apply()
                send(socket, reply.put("mode", mode).put("message", "关闭会停止当前采集；开启在下次有线连接生效（最长2分钟）"))
            }
            "usb-reset" -> {
                val state = PioneerDebugRuntime.snapshot()
                require(!state.optBoolean("controllerRunning") && !state.optBoolean("backgroundSession") &&
                    !state.optBoolean("hostAttached") && !state.optBoolean("hostStateTimeout") &&
                    !state.has("hostStateError") && !PioneerLwipRuntime.active) { "Stop the session and wait for complete cleanup before USB reset" }
                val manager = getSystemService(UsbManager::class.java)
                val phone = manager.deviceList.values.singleOrNull { it.vendorId == 0x05ac && manager.hasPermission(it) }
                    ?: error("Expected one authorized iPhone")
                val connection = manager.openDevice(phone) ?: error("Could not open authorized iPhone")
                val started = System.nanoTime()
                val rc = try { PioneerUsbNative.resetDevice(connection.fileDescriptor) } finally { connection.close() }
                PioneerDebugRuntime.log(this, "USB_DEVICE_RESET rc=$rc elapsedMs=${(System.nanoTime()-started)/1_000_000}")
                send(socket, reply.put("resetRc", rc).put("message", "USB reset attempted; inspect fresh status before reconnecting"))
            }
            "gc" -> {
                System.gc()
                send(socket, reply.put("memory", memorySnapshot()))
            }
            "logs" -> {
                val name = request.optString("source", "session")
                require(name in listOf("session", "adapter")) { "source must be session or adapter" }
                val file = if (name == "adapter") PioneerDebugRuntime.logFile(this) else File(filesDir, "logs/diplay.log")
                var offset = request.optLong("offset", 0).coerceAtLeast(0)
                var data = byteArrayOf()
                if (file.exists()) RandomAccessFile(file, "r").use { stream ->
                    if (offset > stream.length()) offset = 0
                    stream.seek(offset); data = ByteArray(minOf(256 * 1024L, stream.length() - offset).toInt()); stream.readFully(data)
                }
                send(socket, reply.put("offset", offset + data.size).put("source", name), data)
            }
            "export" -> {
                val archive = archive()
                try {
                    send(socket, reply.put("size", archive.length()))
                    connections.write(socket) {
                        archive.inputStream().use { it.copyTo(socket.getOutputStream()) }
                        socket.getOutputStream().flush()
                    }
                } finally { archive.delete() }
            }
            "start-wired", "stop", "retry-vpn" -> {
                PioneerDebugRuntime.request(this, command)
                send(socket, reply.put("message", "指令已排队，实际状态请查询 status").put("command", command))
            }
            "usb-configuration" -> {
                val state = PioneerDebugRuntime.snapshot()
                require(!state.optBoolean("controllerRunning") && !state.optBoolean("backgroundSession") &&
                    !state.optBoolean("hostAttached") && !state.optBoolean("hostStateTimeout") &&
                    !state.has("hostStateError") && !PioneerLwipRuntime.active) { "Stop and wait for complete cleanup before changing USB configuration mode" }
                PioneerUsbPolicy.setConfigurationMode(this, request.getString("mode"))
                send(socket, reply.put("mode", PioneerUsbPolicy.configurationMode(this)).put("message", "下次连接生效；REINITIALIZE 为 lwip11 对照，REUSE 为实验"))
            }
            "usb-mode" -> {
                PioneerUsbPolicy.setMode(this, request.getString("mode"))
                send(socket, reply.put("mode", PioneerUsbPolicy.mode(this)).put("message", "下次建立连接生效；请先 stop，再 start-wired"))
            }
            "toast" -> {
                val text = request.optString("text").ifBlank { "DiPlay 调试：请执行一次交互（滑动/点击）" }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                }
                send(socket, reply.put("text", text).put("message", "toast 已显示"))
            }
            "lwip-diagnostics" -> {
                val mode = request.getString("mode")
                require(mode in listOf("ON", "OFF"))
                PioneerDiagnostics.enabled = mode == "ON"
                getSharedPreferences("pioneer-debug", MODE_PRIVATE).edit().putBoolean("lwip_diagnostics", PioneerDiagnostics.enabled).apply()
                send(socket, reply.put("mode", mode).put("message", "聚合计时开关立即生效；连接与错误日志继续保留"))
            }
            "network-backend" -> {
                val mode = request.getString("mode")
                require(mode in listOf("LWIP", "VPN")) { "mode must be LWIP or VPN" }
                getSharedPreferences("pioneer-debug", MODE_PRIVATE).edit().putString("network_backend", mode).commit()
                send(socket, reply.put("mode", mode).put("message", "下次连接生效"))
            }
            "update-storage", "clear-update-cache" -> {
                val updates = File(cacheDir, "pioneer-update")
                var reclaimed = 0L
                if (command == "clear-update-cache") {
                    PioneerDebugUpdateGuard.withCacheMaintenance {
                        updates.listFiles()?.filter {
                            it.isFile && it.name.endsWith(".apk") && PioneerDebugUpdateGuard.canDelete(it)
                        }?.forEach {
                            val bytes = it.length()
                            if (it.delete()) {
                                reclaimed += bytes
                                File(it.path + ".installing").delete()
                            }
                        }
                    }
                }
                val disk = android.os.StatFs(cacheDir.absolutePath)
                send(socket, reply.put("availableBytes", disk.availableBytes).put("reclaimedBytes", reclaimed)
                    .put("updateApks", org.json.JSONArray(updates.listFiles()?.filter { it.isFile }?.map {
                        JSONObject().put("name", it.name).put("bytes", it.length())
                    } ?: emptyList<JSONObject>())))
            }
            "companion-status" -> {
                val info = packageManager.getPackageInfo("com.tencent.wecarflow", PackageManager.GET_SIGNATURES or PackageManager.GET_SERVICES)
                val details = JSONObject().put("package", info.packageName).put("versionCode", info.versionCode).put("versionName", info.versionName)
                    .put("stopped", (info.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0)
                val guard = info.services?.firstOrNull { it.name == "com.tencent.wecarflow.carplayguard.PlaybackGuardService" }
                details.put("guardProcess", guard?.processName).put("guardEnabled", guard?.enabled)
                details.put("certificateSha256", org.json.JSONArray(info.signatures?.map { signature ->
                    java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
                } ?: emptyList<String>()))
                if (guard?.processName == "com.tencent.wecarflow:carplayguard") {
                    val connected = java.util.concurrent.CountDownLatch(1)
                    val peer = java.util.concurrent.atomic.AtomicReference<android.os.IBinder?>()
                    val connection = object : android.content.ServiceConnection {
                        override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) { peer.set(service); connected.countDown() }
                        override fun onServiceDisconnected(name: android.content.ComponentName) { peer.set(null) }
                    }
                    var bound = false
                    try {
                        bound = bindService(Intent().setClassName(info.packageName, guard.name), connection, android.content.Context.BIND_AUTO_CREATE)
                        if (bound && connected.await(4, java.util.concurrent.TimeUnit.SECONDS)) {
                            val data = android.os.Parcel.obtain(); val response = android.os.Parcel.obtain()
                            try {
                                data.writeInterfaceToken("com.shilapi.xcertplay.vehicle.LocalPlaybackGuard.v1")
                                if (peer.get()?.transact(5, data, response, 0) == true) {
                                    response.readException()
                                    val state = response.readBundle(javaClass.classLoader)
                                    details.put("bridge", JSONObject().also { json -> state?.keySet()?.forEach { key -> json.put(key, org.json.JSONObject.wrap(state.get(key))) } })
                                }
                            } finally { data.recycle(); response.recycle() }
                        } else details.put("bridgePending", bound)
                    } finally { if (bound) unbindService(connection) }
                }
                send(socket, reply.put("companion", details))
            }
            "update" -> send(socket, PioneerDebugUpdater(this).receive(socket, request))
            else -> error("未知指令 $command")
        }
    }

    private fun send(socket: Socket, reply: JSONObject, data: ByteArray? = null) {
        if (data != null) reply.put("size", data.size)
        connections.write(socket) {
            val output = socket.getOutputStream(); output.write((reply.toString() + "\n").toByteArray(Charsets.UTF_8))
            if (data != null) output.write(data)
            output.flush()
        }
    }

    fun status(): JSONObject {
        val consent = runCatching { VpnService.prepare(this) }
        val vpn = JSONObject().put("authorized", consent.isSuccess && consent.getOrNull() == null)
            .put("consentComponent", consent.getOrNull()?.component?.flattenToShortString() ?: "")
            .put("tunNodeExists", File("/dev/tun").exists() || File("/dev/net/tun").exists())
        consent.exceptionOrNull()?.let { vpn.put("error", it.toString()) }
        vpn.put("consentActivityAvailable", consent.getOrNull()?.component?.let {
            runCatching { packageManager.getActivityInfo(it, 0).enabled }.getOrDefault(false)
        } ?: false)
        val devices = getSystemService(UsbManager::class.java).deviceList.values.map { device ->
            JSONObject().put("vid", device.vendorId).put("pid", device.productId).put("path", device.deviceName)
                .put("permission", getSystemService(UsbManager::class.java).hasPermission(device))
        }
        return JSONObject().put("package", packageName).put("version", packageManager.getPackageInfo(packageName, 0).versionName)
            .put("versionCode", packageManager.getPackageInfo(packageName, 0).versionCode)
            .put("port", PioneerDebugRuntime.port).put("listener", PioneerDebugRuntime.listenerState)
            .put("addresses", JSONArray(addresses())).put("vpn", vpn).put("host", PioneerDebugRuntime.snapshot())
            .put("usbConfigurationMode", PioneerUsbPolicy.configurationMode(this))
            .put("usbConfigurationEffectiveMode", if (LocalConnectionExperiments.USB_CONFIGURATION_REUSE)
                PioneerUsbPolicy.configurationMode(this) else "REINITIALIZE")
            .put("upstreamAlignment", LocalConnectionExperiments.summary())
            .put("connectionTimeline", JSONArray(PioneerConnectionTrace.snapshot()))
            .put("usbMode", PioneerUsbPolicy.mode(this)).put("pioneerUsbEnabled", PioneerUsbPolicy.enabled(this))
            .put("matchedFirmware", PioneerUsbPolicy.matchesDevice()).put("devices", JSONArray(devices))
            .put("lastCommand", PioneerDebugRuntime.lastCommand)
            .put("networkBackend", if (PioneerLwipRuntime.enabled(this)) "LWIP" else "VPN")
            .put("lwipActive", PioneerLwipRuntime.active).put("lwipStatus", PioneerLwipRuntime.summary)
            .put("lwipQueues", JSONArray(PioneerDiagnostics.queues()))
            .put("lwipDiagnostics", PioneerDiagnostics.enabled)
            .put("phoneDiagnostics", PioneerDebugRuntime.phoneDiagnosticsEnabled)
            .put("usbTxBatchFrames", PioneerDebugRuntime.usbTxBatchFrames)
            .put("videoSettings", PioneerDebugRuntime.videoSettings?.invoke(JSONObject()))
            .put("lwipSessionId", PioneerLwipRuntime.sessionId).put("lwipAttemptId", PioneerLwipRuntime.attemptId)
            .put("lwipControlPort", PioneerLwipRuntime.controlPort)
            .put("lwipTimeline", JSONArray(PioneerLwipRuntime.timeline()))
            .put("nativeCryptoVerified", PioneerNativeCrypto.available)
            .put("nativeCryptoEnabled", PioneerNativeCrypto.enabled)
            .put("touchCoalescingEnabled", PioneerDebugRuntime.touchCoalescingComparisonEnabled)
            .put("lwipReceiveWaitMillis", PioneerDebugRuntime.lwipReceiveWaitMillis)
            .put("asyncSessionLogs", PioneerDebugRuntime.asyncSessionLogs)
            .put("freescaleNoReorderRequested", PioneerDebugRuntime.freescaleNoReorder)
            .put("appearanceDeclarationsEnabled", PioneerDebugRuntime.appearanceDeclarationsEnabled)
            .put("audioClockTrimEnabled", PioneerDebugRuntime.audioClockTrimEnabled)
            .put("audioClockTrimOverride", PioneerDebugRuntime.audioClockTrimOverride ?: JSONObject.NULL)
            .put("memory", memorySnapshot())
            .put("networkCounters", runCatching { File("/proc/net/dev").readText().take(16_384) }.getOrElse { it.toString() })
            .put("ipv6Sockets", runCatching { File("/proc/net/tcp6").readText().take(4_096) }.getOrElse { it.toString() })
    }

    private fun networkDiagnostics(): JSONObject = JSONObject().apply {
        for (path in listOf("/proc/net/snmp6", "/proc/net/dev_snmp6/tun0", "/proc/net/snmp",
            "/proc/net/if_inet6", "/proc/net/ipv6_route", "/proc/net/route",
            "/proc/sys/net/ipv6/conf/tun0/disable_ipv6", "/proc/sys/net/ipv6/conf/tun0/accept_dad",
            "/proc/sys/net/ipv6/conf/tun0/dad_transmits", "/system/bin/iptables_rules.sh")) {
            put(path, runCatching { File(path).readText().take(64 * 1024) }.getOrElse { it.toString() })
        }
    }

    private fun memorySnapshot(): JSONObject {
        val runtime = Runtime.getRuntime()
        return JSONObject().put("javaUsed", runtime.totalMemory() - runtime.freeMemory())
            .put("javaTotal", runtime.totalMemory()).put("javaMax", runtime.maxMemory())
            .put("nativeAllocated", android.os.Debug.getNativeHeapAllocatedSize())
            .put("threads", Thread.activeCount())
    }

    /** Fixed read-only capture: no remotely supplied command or path. */
    private fun runtimeDiagnostics(): JSONObject = JSONObject().apply {
        val pid = android.os.Process.myPid()
        put("pid", pid).put("atNs", System.nanoTime())
            .put("processCpuMs", android.os.Process.getElapsedCpuTime())
        put("memory", memorySnapshot())
        put("systemStat", runCatching { File("/proc/stat").readLines().take(5).joinToString("\n") }.getOrDefault("unavailable"))
        put("threads", JSONArray(File("/proc/self/task").listFiles().orEmpty().mapNotNull { task ->
            runCatching {
                val stat = File(task, "stat").readText()
                val fields = stat.substringAfterLast(") ").split(' ')
                JSONObject().put("tid", task.name).put("name", File(task, "comm").readText().trim())
                    .put("userTicks", fields[11].toLong()).put("systemTicks", fields[12].toLong())
            }.getOrNull()
        }))
        put("cpuFrequency", JSONArray((0..7).mapNotNull { index ->
            runCatching { "cpu$index=" + File("/sys/devices/system/cpu/cpu$index/cpufreq/scaling_cur_freq").readText().trim() }.getOrNull()
        }))
        put("logcat", runCatching {
            val process = ProcessBuilder("logcat", "-d", "-t", "1200", "-v", "threadtime").redirectErrorStream(true).start()
            try {
                val own = Regex("^\\d\\d-\\d\\d\\s+\\S+\\s+$pid\\s+\\d+\\s")
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.take(1200).filter { own.containsMatchIn(it) }
                        .mapNotNull { PioneerDebugRuntime.redact(it) }.joinToString("\n").takeLast(64 * 1024)
                }
            } finally { process.destroy() }
        }.getOrElse { "Capture unavailable: ${it.javaClass.simpleName}" })
    }

    private fun archive(): File {
        val target = File.createTempFile("pioneer-report-", ".zip", cacheDir)
        ZipOutputStream(target.outputStream()).use { zip ->
            fun add(name: String, data: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            add("status.json", status().toString(2).toByteArray())
            add("network.json", networkDiagnostics().toString(2).toByteArray())
            add("environment.txt", "android=${Build.VERSION.RELEASE}\nmodel=${Build.MODEL}\ndevice=${Build.DEVICE}\n".toByteArray())
            for (dir in listOf(File(filesDir, "logs"), File(filesDir, "pioneer-debug"))) {
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }?.forEach {
                    // Session logs are already redacted by DiPlay; retain rotated logs for reconnect/update failures.
                    add("${dir.name}/${it.name}", it.inputStream().use { stream ->
                        val data = ByteArray(minOf(it.length(), 1024 * 1024L).toInt())
                        var used = 0
                        while (used < data.size) { val count = stream.read(data, used, data.size - used); if (count < 0) break; used += count }
                        data.copyOf(used)
                    })
                }
            }
        }
        return target
    }

    override fun onDestroy() {
        closed = true; runCatching { listener?.close() }; connections.close()
        PioneerDebugRuntime.port = 0; PioneerDebugRuntime.listenerState = "已停止"
        super.onDestroy()
    }
    companion object {
        fun addresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { iface -> iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                .map { "${iface.name}: ${it.hostAddress}" } }
        }.getOrDefault(emptyList())
    }
}
