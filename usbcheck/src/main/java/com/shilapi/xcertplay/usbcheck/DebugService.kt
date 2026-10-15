package com.shilapi.xcertplay.usbcheck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** One TCP request per connection. JSON line header, then optional size-delimited binary body. */
class DebugService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@DebugService }
    private val binder = LocalBinder()
    lateinit var engine: UsbCheckEngine
        private set
    @Volatile var port = 0
        private set
    @Volatile var listenerMessage = "正在启动"
        private set
    lateinit var token: String
        private set
    private var listener: ServerSocket? = null
    private val workers = Executors.newFixedThreadPool(3)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false
    @Volatile private var armedMode: UsbCheckEngine.Mode? = null
    private val armLock = Any()
    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            if (device.vendorId != 0x05ac) return
            synchronized(armLock) {
                val mode = armedMode ?: return
                if (engine.start(mode)) {
                    armedMode = null
                    engine.appendDiagnostic("ARMED_ATTACH_TRIGGERED mode=$mode path=${device.deviceName}")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = UsbCheckEngine(this)
        ContextCompat.registerReceiver(this, attachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        val prefs = getSharedPreferences("usbcheck-debug", MODE_PRIVATE)
        token = prefs.getString("token", null) ?: UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString("token", it).apply()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("usbcheck", "USB 调试", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "usbcheck") else Notification.Builder(this)
        startForeground(1, builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("USB 检查 TCP 调试运行中").setContentText("在应用页面查看连接地址与口令").build())
        Thread(::listen, "usbcheck-listener").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder = binder

    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { iface ->
            iface.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }.map { "${iface.name}: ${it.hostAddress}" }
        }
    }.getOrDefault(emptyList())

    private fun listen() {
        for (candidate in listOf(9911, 9912, 8721)) {
            val socket = ServerSocket()
            try {
                // Explicit IPv4 wildcard; no dependency on the OEM IPv6 dual-stack setting.
                socket.bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), candidate), 4)
                if (closed) { socket.close(); return }
                listener = socket
                port = candidate
                listenerMessage = "监听中"
                engine.appendDiagnostic("TCP listening 0.0.0.0:$port；需要本机 status 响应确认网络可达")
                break
            } catch (error: Exception) {
                socket.close()
                engine.appendDiagnostic("TCP bind $candidate 失败: ${error.message}")
            }
        }
        val server = listener ?: run { listenerMessage = "9911 / 9912 / 8721 均无法绑定"; return }
        while (!closed) {
            try {
                val socket = server.accept()
                socket.soTimeout = 30_000
                clients.add(socket)
                workers.execute {
                    socket.use { runCatching { handle(it) }.onFailure { error ->
                        runCatching { send(it, JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.simpleName)) }
                    } }
                    clients.remove(socket)
                }
            } catch (error: Exception) {
                if (!closed) engine.appendDiagnostic("TCP accept failed: ${error.message}")
                break
            }
        }
    }

    private fun readHeader(socket: Socket): JSONObject {
        val bytes = java.io.ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (bytes.size() < 16_384) {
            val next = input.read()
            if (next < 0) error("Incomplete JSON header")
            if (next == 10) return JSONObject(bytes.toString("UTF-8"))
            bytes.write(next)
        }
        error("JSON header too large")
    }

    private fun send(socket: Socket, response: JSONObject, body: ByteArray? = null) {
        if (body != null) response.put("size", body.size)
        val output = socket.getOutputStream()
        output.write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
        if (body != null) output.write(body)
        output.flush()
    }

    private fun handle(socket: Socket) {
        val request = readHeader(socket)
        require(request.optString("token") == token) { "调试口令不匹配" }
        val response = JSONObject().put("ok", true)
        when (val command = request.getString("cmd")) {
            "status" -> {
                response.put("package", packageName).put("version", packageManager.getPackageInfo(packageName, 0).versionName)
                    .put("vpn", vpnStatus()).put("port", port).put("addresses", JSONArray(addresses())).put("running", engine.state.running)
                    .put("mode", engine.mode.name).put("armedMode", armedMode?.name ?: "").put("steps", JSONArray().also { steps ->
                        engine.state.steps.forEach { steps.put(JSONObject().put("id", it.id).put("status", it.status.name)
                            .put("title", it.title).put("detail", it.detail)) }
                    }).put("devices", JSONArray(engine.state.devices.map { it.text }))
                send(socket, response)
            }
            "arm" -> {
                val mode = UsbCheckEngine.Mode.valueOf(request.optString("mode", "LOW_POWER_DISCONNECT"))
                synchronized(armLock) {
                    require(!engine.state.running) { "已有检查正在执行" }
                    armedMode = mode
                }
                engine.appendDiagnostic("ARMED_FOR_NEXT_ATTACH mode=$mode")
                send(socket, response.put("armedMode", mode.name))
            }
            "run" -> {
                val mode = UsbCheckEngine.Mode.valueOf(request.optString("mode", "NEGOTIATE"))
                val started = engine.start(mode)
                send(socket, response.put("ok", started).put("mode", mode.name).put("error", if (started) "" else "已有检查正在执行"))
            }
            "vpn-demo" -> {
                require(!engine.state.running) { "请先等待 USB 检查结束" }
                startActivity(Intent(this, UsbCheckActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("vpn_demo", true))
                send(socket, response.put("message", "已请求 VPN 授权/最小 TUN 验证；结果见 status.vpn.demo"))
            }
            "stop" -> { synchronized(armLock) { armedMode = null }; engine.stop(); send(socket, response.put("message", "已请求停止")) }
            "logs" -> {
                val offset = request.optLong("offset", 0).coerceAtLeast(0)
                val file = engine.logFile
                val bytes: ByteArray
                val next: Long
                if (file.exists()) {
                    RandomAccessFile(file, "r").use { input ->
                        val start = offset.coerceAtMost(input.length())
                        input.seek(start)
                        bytes = ByteArray(minOf(256 * 1024L, input.length() - start).toInt())
                        input.readFully(bytes)
                        next = start + bytes.size
                    }
                } else { bytes = byteArrayOf(); next = 0 }
                send(socket, response.put("offset", next), bytes)
            }
            "export" -> {
                require(!engine.state.running) { "请等待检查结束或 stop 后再导出完整报告；检查中可使用 logs" }
                val archive = makeArchive()
                try {
                    send(socket, response.put("size", archive.length()).put("name", archive.name))
                    archive.inputStream().use { it.copyTo(socket.getOutputStream()) }
                    socket.getOutputStream().flush()
                } finally { archive.delete() }
            }
            "update" -> receiveUpdate(socket, request, response)
            else -> error("未知指令 $command")
        }
    }

    private fun receiveUpdate(socket: Socket, request: JSONObject, response: JSONObject) {
        require(!engine.state.running) { "请先停止 USB 检查再更新" }
        val size = request.getLong("size")
        require(size in 1..128L * 1024 * 1024) { "APK 大小超出范围" }
        val file = File.createTempFile("usbcheck-update-", ".apk", cacheDir)
        try {
            file.outputStream().use { output ->
                val input = socket.getInputStream()
                val buffer = ByteArray(64 * 1024)
                var remaining = size
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    require(read > 0) { "APK 上传中断" }
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            val info = packageManager.getPackageArchiveInfo(file.absolutePath, android.content.pm.PackageManager.GET_SIGNATURES)
                ?: error("不是有效 APK")
            require(info.packageName == packageName) { "包名不符: ${info.packageName}" }
            val current = packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
            require(info.signatures?.toList() == current.signatures?.toList() && !info.signatures.isNullOrEmpty()) { "APK 签名不匹配" }
            // Activity provides FileProvider URI and handles missing OEM installer gracefully.
            startActivity(Intent(this, UsbCheckActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("install_path", file.absolutePath))
            engine.appendDiagnostic("已上传同包名同签名 APK，已请求打开安装器；请在车机确认安装")
            send(socket, response.put("message", "APK 已接收，已请求打开车机安装确认；更新后重新打开 APP"))
        } catch (error: Exception) {
            file.delete()
            engine.appendDiagnostic("APK 更新失败: ${error.message}")
            throw error
        }
    }

    private fun vpnStatus(): JSONObject {
        val result = JSONObject()
        runCatching {
            val consent = android.net.VpnService.prepare(this)
            result.put("authorized", consent == null)
                .put("consentComponent", consent?.component?.flattenToShortString() ?: "")
                .put("consentActivityAvailable", consent?.component?.let { component ->
                    runCatching { packageManager.getActivityInfo(component, 0).enabled }.getOrDefault(false)
                } ?: false)
        }.onFailure { result.put("probeError", it.message ?: it.javaClass.simpleName) }
        val nodes = listOf("/dev/tun", "/dev/net/tun")
        result.put("tunNodeExists", nodes.any { File(it).exists() })
        result.put("tunNodes", JSONObject().also { values -> nodes.forEach { values.put(it, File(it).exists()) } })
        val demo = VpnDemoService.status(this)
        result.put("demo", demo)
        result.put("tunnelEstablished", demo.optBoolean("established") && !demo.optBoolean("tunnelClosed"))
        return result
    }

    private fun makeArchive(): File {
        engine.saveReportFile()
        val file = File.createTempFile("usbcheck-report-", ".zip", cacheDir)
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("report.txt", engine.report())
            entry("vpn-status.json", vpnStatus().toString(2))
            if (engine.logFile.exists()) {
                zip.putNextEntry(ZipEntry("live.log")); engine.logFile.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
            entry("system-logcat.txt", capture(listOf("logcat", "-b", "all", "-d", "-t", "2500", "-v", "threadtime")))
            entry("kernel-dmesg.txt", capture(listOf("dmesg")))
            entry("environment.txt", "package=$packageName\nandroid=${Build.VERSION.RELEASE}\nmodel=${Build.MODEL}\naddresses=${addresses()}\nport=$port\n")
        }
        return file
    }

    private fun capture(command: List<String>): String = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = java.io.ByteArrayOutputStream()
        val reader = Thread {
            runCatching { process.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (output.size() < 2 * 1024 * 1024) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
        }.apply { isDaemon = true; start() }
        reader.join(5_000)
        process.destroy()
        reader.join(1_000)
        if (reader.isAlive) "${command.first()}: collection timed out" else output.toString("UTF-8").ifBlank {
            "${command.first()}: no readable output; ordinary APP permissions are not ADB shell permissions"
        }
    }.getOrElse { "${command.first()}: unavailable: ${it.message}" }

    override fun onDestroy() {
        closed = true
        runCatching { unregisterReceiver(attachReceiver) }
        runCatching { listener?.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        engine.shutdown()
        super.onDestroy()
    }
}
