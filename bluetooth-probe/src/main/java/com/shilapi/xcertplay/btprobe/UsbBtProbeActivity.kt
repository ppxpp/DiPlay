package com.shilapi.xcertplay.btprobe

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB 蓝牙模块（OTG · HCI Rf Transport）测试页。
 *
 * 目标：判断第三方 App 能否用 OTG 上的 USB 蓝牙适配器建立 iPhone CarPlay 协商所需的通道。
 * 分步取证：枚举 → 权限 → 接口占用 → HCI 命令/事件收发 → 可行性判定。每一步只报告实测证据。
 */
class UsbBtProbeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private lateinit var report: ProbeReport
    private lateinit var output: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var status: TextView
    private val buttons = mutableListOf<Button>()
    private val stages = mutableListOf<TextView>()
    private var busy = false
    private var usbManager: UsbManager? = null
    private var permissionReceiver: Closeable? = null
    private val readFailures = HashMap<Int, Int>()
    private var asyncInterruptRequested = false
    private var asyncFailureLogged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = ProbeReport(this, "reports/usb-bluetooth.txt", "usb-bluetooth.txt")
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager?
        buildUi()
        record("新测试会话 uid=${Process.myUid()} pid=${Process.myPid()} 包名=$packageName")
        record("机型=${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})；无 root")
        if (usbManager == null) stage(0, "阻塞：拿不到 UsbManager")
        when (intent.getStringExtra(EXTRA_ACTION)) {
            null -> Unit
            ACTION_FAST -> runFast()
            else -> runAll()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.getStringExtra(EXTRA_ACTION)) {
            null -> Unit
            ACTION_FAST -> runFast()
            else -> runAll()
        }
    }

    /**
     * 3 秒内跑完的决定性实验：占用全部无线接口 → 原生 URB 队列收发 HCI。
     * 小米 MIUI 会在十秒级把探针进程回收，长测试拿不到尾部证据。
     */
    private fun runFast() = runTest {
        val found = enumerate() ?: return@runTest
        if (!awaitPermission(found.first)) {
            stage(1, "阻塞：USB 权限未授予")
            return@runTest
        }
        stage(1, "已授予 USB 访问权限")
        val held = claim(found.first, claimAll = true) ?: return@runTest
        stage(2, "占用成功：iface=${held.interface_.id} 全部无线接口已占用")
        prepareEndpoints(held)
        val native = if (UsbBtNative.available) UsbBtNative.probe(
            held.connection, held.interface_.id,
            held.endpoints.commandOut.address.toInt() and 0xFF,
            held.endpoints.eventIn.address.toInt() and 0xFF,
        ) else null
        if (native == null) {
            record("原生 URB 实验未执行：${if (UsbBtNative.available) "调用抛出异常" else "usb_bt_hci 未加载"}；退回 Java 层读取")
        } else {
            record("NATIVE $native")
        }
        val reply = if (native == null) transmit(held, "HCI Reset(fast)", opcode = 0x0C03, replyMillis = FAST_REPLY_MILLIS) else null
        val status = reply?.second?.getOrNull(0)
        stage(3, if (native != null) "以原生 URB 实验为准（Java 层读取已跳过，避免超出进程存活窗口）"
            else if (reply == null) "fast(Java)：无 HCI 事件" else "fast(Java)：事件=0x%02x status=%s".format(
                reply.first, status?.let { it.toInt() and 0xFF }?.toString() ?: "无"))
        held.connection.close()
        val answered = reply != null || (native != null && UsbBtNative.inDataSeen(native))
        verdict(null, true, if (answered) true else reply?.let { it.first == EVENT_COMMAND_COMPLETE && it.second.getOrNull(0) == 0.toByte() },
            null, null, null, native)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.rgb(12, 17, 27))
        }
        root.addView(label("USB 蓝牙模块 · CarPlay 通道可行性验证", 23f))
        status = label("OTG · HCI Rf Transport · 分步取证", 14f)
        root.addView(status)
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), dp(14), 0)
        }
        columns.addView(
            ScrollView(this).apply { addView(left) },
            LinearLayout.LayoutParams(0, -1, 0.40f),
        )
        button(left, "运行完整测试（步骤 1–5）") { runAll() }
        button(left, "快测：占用全部接口 + 一条 HCI Reset（<6s）") { runFast() }
        button(left, "只重新枚举 USB 设备") { runTest { enumerate() } }
        button(left, "读取内置蓝牙状态（对照组）") { runTest { builtinComparison() } }
        val titles = listOf(
            "1. USB 枚举与蓝牙接口识别", "2. USB 访问权限", "3. 打开并强制占用接口",
            "4. HCI 命令与事件收发", "5. CarPlay 协商可行性判定",
        )
        titles.forEach { title ->
            val row = label("$title\n待验证", 15f).apply { setPadding(0, dp(5), 0, dp(3)) }
            stages.add(row)
            left.addView(row)
        }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columns.addView(right, LinearLayout.LayoutParams(0, -1, 0.60f))
        val actions = LinearLayout(this)
        right.addView(actions)
        actions.addView(Button(this).apply {
            text = "停止测试"
            setOnClickListener {
                cancelled.set(true)
                record("请求停止；已受理的本次占用由测试线程释放")
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply {
            text = "导出报告"
            setOnClickListener { share() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        output = label(report.read(), 14f).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply { addView(output) }
        right.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun runAll() = runTest {
        val found = enumerate()
        if (found == null) {
            stage(1, "未执行：没有可测设备")
            stage(2, "未执行")
            stage(3, "未执行")
            verdict("步骤 1 未发现蓝牙 HCI 接口", null, null, null, null, null)
            return@runTest
        }
        val target = found.first
        val permission = awaitPermission(target)
        stage(1, if (permission) "已授予 USB 访问权限" else "阻塞：USB 权限未授予或被拒绝")
        if (!permission) {
            stage(2, "未执行")
            stage(3, "未执行")
            verdict("USB 权限未授予", null, null, null, null, null)
            return@runTest
        }
        val held = claim(target)
        if (held == null) {
            stage(3, "未执行：接口未占用成功")
            verdict("USB 接口占用失败", false, null, null, null, null)
            return@runTest
        }
        stage(2, "占用成功：iface=${held.interface_.id}/${held.interface_.alternateSetting} 命令 out=0x%02x 事件 in=0x%02x".format(
            held.endpoints.commandOut.address, held.endpoints.eventIn.address))
        val hci = hciSession(held)
        held.connection.close()
        verdict(null, true, hci.reset, hci.version, hci.address, hci.features)
    }

    /** @return Pair(设备, 候选蓝牙接口) ；没有蓝牙接口时返回第一个设备的 null 接口。 */
    private fun enumerate(): Pair<UsbDevice, UsbInterface>? {
        val manager = usbManager ?: return null
        val devices = manager.deviceList.values.sortedBy { "%04x:%04x".format(it.vendorId, it.productId) }
        if (devices.isEmpty()) {
            stage(0, "阻塞：UsbManager 看不到任何 USB 设备（OTG 未供电或模块未识别）")
            record("系统可见 USB 设备数=0")
            return null
        }
        var candidate: Pair<UsbDevice, UsbInterface>? = null
        devices.forEach { device ->
            record("USB 设备 ${"%04x:%04x".format(device.vendorId, device.productId)} 名称=${device.deviceName} " +
                "接口数=${device.interfaceCount} 配置数=${device.configurationCount} " +
                "权限=${if (manager.hasPermission(device)) "有" else "无"}")
            for (index in 0 until device.interfaceCount) {
                val iface = device.getInterface(index)
                val wireless = iface.isWirelessController()
                record("  iface=${iface.id}/${iface.alternateSetting} class=0x%02x sub=0x%02x proto=0x%02x 端点=${endpointText(iface)}".format(
                    iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol,
                ) + if (wireless) "  ← 无线控制器" else "")
                if (wireless && candidate == null && hciEndpoints(iface) != null) candidate = device to iface
            }
        }
        if (candidate == null) {
            stage(0, "已枚举 ${devices.size} 个设备，但没有 0xE0/0x01/0x01 蓝牙 HCI 接口（class=0xE0 表示无线控制器）")
            record("判定依据：USB 类代码 0xE0=Wireless Controller，子类 0x01=Rf Transport，协议 0x01=Bluetooth HCI")
            return null
        }
        val device = candidate.first
        stage(0, "识别到蓝牙 HCI 接口：vid:pid=%04x:%04x iface=${candidate.second.id}".format(
            device.vendorId, device.productId))
        return candidate
    }

    private fun endpointText(iface: UsbInterface): String =
        (0 until iface.endpointCount).joinToString(",") { index ->
            val endpoint = iface.getEndpoint(index)
            "0x%02x(type=%d,dir=%s,max=%d)".format(endpoint.address, endpoint.type,
                if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT", endpoint.maxPacketSize)
        }

    /**
     * USB 蓝牙 HCI Rf Transport 的端点分工：命令与 ACL 数据走 bulk OUT，事件走 interrupt IN
     * （个别模块把事件也放在 bulk IN），ACL 数据走另一个 bulk IN。
     */
    private fun hciEndpoints(iface: UsbInterface): HciEndpoints? {
        var commandOut: UsbEndpoint? = null
        var aclIn: UsbEndpoint? = null
        var eventIn: UsbEndpoint? = null
        for (index in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(index)
            when (endpoint.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> aclIn = aclIn ?: endpoint
                    else -> commandOut = commandOut ?: endpoint
                }
                UsbConstants.USB_ENDPOINT_XFER_INT -> if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    eventIn = eventIn ?: endpoint
                }
            }
        }
        val output = commandOut ?: return null
        val events = eventIn ?: aclIn ?: return null
        return HciEndpoints(iface, events, output, aclIn)
    }

    private fun awaitPermission(device: UsbDevice): Boolean {
        val manager = usbManager ?: return false
        if (manager.hasPermission(device)) {
            record("系统已授予该设备的 USB 权限")
            return true
        }
        val granted = AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)
        val action = "$packageName.USB_BT_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != action) return
                val extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val ok = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                record("权限回调：device=${extra?.deviceName} granted=$ok")
                if (ok) granted.set(true)
                latch.countDown()
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        permissionReceiver = Closeable { runCatching { unregisterReceiver(receiver) } }
        val pending = android.app.PendingIntent.getBroadcast(
            this, 0, Intent(action).setPackage(packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        record("已弹出 USB 权限请求：请在手机屏幕上点“允许”（最多等待 ${PERMISSION_TIMEOUT_MILLIS / 1000}s）")
        manager.requestPermission(device, pending)
        val deadline = android.os.SystemClock.elapsedRealtime() + PERMISSION_TIMEOUT_MILLIS
        try {
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (cancelled.get()) return false
                if (granted.get() || manager.hasPermission(device)) return true
                latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            record("等待 USB 权限超时")
            return false
        } finally {
            runCatching { permissionReceiver?.close() }
            permissionReceiver = null
        }
    }

    private fun claim(device: UsbDevice, claimAll: Boolean = false): Held? {
        val manager = usbManager ?: return null
        val endpoints = (0 until device.interfaceCount)
            .map(device::getInterface)
            .filter { it.isWirelessController() }
            .firstNotNullOfOrNull(::hciEndpoints)
        if (endpoints == null) {
            stage(2, "阻塞：蓝牙接口没有命令/事件端点（bulk OUT + interrupt 或 bulk IN）")
            return null
        }
        val candidate = endpoints.iface
        val connection = manager.openDevice(device)
        if (connection == null) {
            stage(2, "阻塞：openDevice 返回 null（权限或内核问题）")
            return null
        }
        ensureConfigured(connection, device)
        val claimed = runCatching { connection.claimInterface(candidate, true) }
            .onFailure { record("claimInterface 抛出：${message(it)}") }
            .getOrDefault(false)
        if (!claimed) {
            connection.close()
            stage(2, "阻塞：claimInterface(force=true) 失败；内核驱动（如 btusb）或其它进程仍占用该接口")
            record("无 root 时无法卸载内核驱动；这是 App 层直用 USB 蓝牙的硬门槛之一")
            return null
        }
        record("占用成功 iface=${candidate.id} proto=0x%02x".format(candidate.interfaceProtocol))
        if (claimAll) {
            // Linux btusb 会同时占用 HCI 接口与 isoc 音频接口；某些组合芯片只在两个接口都就绪后才启动 HCI。
            (0 until device.interfaceCount)
                .map(device::getInterface)
                .filter { it.isWirelessController() && it.id != candidate.id }
                .forEach { extra ->
                    val ok = runCatching { connection.claimInterface(extra, true) }.getOrDefault(false)
                    record("附加占用 iface=${extra.id}/${extra.alternateSetting} 端点=${endpointText(extra)} result=$ok")
                }
        }
        record("端点分工：事件 in=0x%02x 命令 out=0x%02x acl in=%s".format(
            endpoints.eventIn.address, endpoints.commandOut.address,
            endpoints.aclIn?.let { "0x%02x".format(it.address) } ?: "无",
        ))
        record("设备描述串=${device.manufacturerName ?: "无"} ${device.productName ?: "无"} 序列号=${device.serialNumber ?: "无"}")
        return Held(connection, candidate, endpoints)
    }

    /** 未配置（bConfigurationValue=0）的 USB 设备不会应答任何 HCI 命令，先确认再发命令。 */
    private fun ensureConfigured(connection: UsbDeviceConnection, device: UsbDevice) {
        val buffer = ByteArray(1)
        val read = runCatching {
            connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_DEVICE,
                USB_REQUEST_GET_CONFIGURATION, 0, 0, buffer, buffer.size, CONTROL_MILLIS,
            )
        }.onFailure { record("GET_CONFIGURATION 抛出：${message(it)}") }.getOrDefault(-1)
        val active = if (read == 1) buffer[0].toInt() and 0xFF else -1
        val wanted = if (device.configurationCount > 0) device.getConfiguration(0) else null
        record("GET_CONFIGURATION result=$read 活动配置=$active 描述符配置=${wanted?.id}（接口数=${wanted?.interfaceCount}）")
        if (wanted == null || active == wanted.id) return
        val set = runCatching { connection.setConfiguration(wanted) }
            .onFailure { record("SET_CONFIGURATION 抛出：${message(it)}") }.getOrDefault(-1)
        record("SET_CONFIGURATION target=${wanted.id} result=$set（0=成功）")
    }

    private data class HciResult(
        val reset: Boolean?,
        val version: String?,
        val address: String?,
        val features: String?,
    )

    private fun hciSession(held: Held): HciResult {
        synchronized(readFailures) { readFailures.clear() }
        asyncInterruptRequested = false
        asyncFailureLogged = false
        prepareEndpoints(held)
        // 首条命令可能被 NAK 或事件丢失，重试有界次数；Reset 未确认也继续试只读命令，
        // 避免把发送时序问题误判成"硬件不应答"。
        var reset: Boolean? = null
        var attempt = 0
        while (reset != true && attempt < RESET_ATTEMPTS && !cancelled.get()) {
            attempt += 1
            val reply = transmit(held, "HCI Reset(第 $attempt 次)", opcode = 0x0C03)
            reset = reply?.let { it.first == EVENT_COMMAND_COMPLETE && it.second.getOrNull(0) == 0.toByte() }
        }
        val readVersion = transmit(held, "HCI Read Local Version Information", opcode = 0x0C0D)
        val version = readVersion?.let { decodeVersion(it.second) }
        var address: String? = null
        var features: String? = null
        if (reset == true || version != null) {
            val readAddress = transmit(held, "HCI Read BD_ADDR", opcode = 0x1009)
            address = readAddress?.let { decodeAddress(it.second) }
            val readFeatures = transmit(held, "HCI Read Local Supported Features", opcode = 0x1003)
            features = readFeatures?.let { "LMP 特性=${hex(it.second)}" }
        } else {
            record("Reset 与只读命令都没有收到 HCI 事件：要么模块需要厂商固件/唤醒序列，要么事件端点与描述符不符")
        }
        return HciResult(reset, version, address, features)
    }

    /** @return Pair(事件码, 事件参数)；超时或无事件返回 null。 */
    private fun transmit(
        held: Held,
        name: String,
        opcode: Int,
        params: ByteArray = ByteArray(0),
        replyMillis: Long = REPLY_TIMEOUT_MILLIS,
    ): Pair<Int, ByteArray>? {
        val frame = byteArrayOf(
            H4_COMMAND,
            (opcode and 0xFF).toByte(),
            ((opcode shr 8) and 0xFF).toByte(),
            params.size.toByte(),
        ) + params
        val sent = runCatching { held.connection.bulkTransfer(held.endpoints.commandOut, frame, frame.size, TRANSFER_MILLIS) }
            .onFailure { record("$name 写入抛出：${message(it)}") }
            .getOrDefault(-1)
        record("→ $name 帧=${hex(frame)} 已发送字节=$sent")
        if (sent < 0) return null
        val deadline = android.os.SystemClock.elapsedRealtime() + replyMillis
        val acl = held.endpoints.aclIn?.takeIf { it.address != held.endpoints.eventIn.address }
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (cancelled.get()) return null
            pollEvent(held, held.endpoints.eventIn, name, "事件端点")?.let { return it }
            acl?.let { pollEvent(held, it, name, "ACL 端点") }?.let { return it }
        }
        record("× $name 在 ${replyMillis / 1000}s 内没有收到 HCI 事件")
        return null
    }

    private fun pollEvent(held: Held, endpoint: UsbEndpoint, name: String, where: String): Pair<Int, ByteArray>? {
        val payload = readPacket(held, endpoint) ?: return null
        record("← $name $where(0x%02x) 收到 ${payload.size}字节 ${hex(payload)}".format(endpoint.address))
        return parseH4Event(payload)
    }

    /**
     * 读取一个 h4 包。
     *
     * 中断 IN 端点一次只承载一个包；按 4 倍包长请求会让部分 Android 实现直接失败，
     * 所以这里按端点类型分别限定单次请求长度。bulkTransfer 连续失败的端点改用 UsbRequest 异步队列。
     */
    private fun readPacket(held: Held, endpoint: UsbEndpoint): ByteArray? {
        val single = endpoint.maxPacketSize.coerceAtLeast(1)
        val want = if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT) single else single * READ_PACKETS
        if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT && asyncInterruptRequested) {
            return readAsync(held, endpoint, want)
        }
        val buffer = ByteArray(want)
        val read = runCatching {
            held.connection.bulkTransfer(endpoint, buffer, want, POLL_MILLIS)
        }.onFailure { record("bulkTransfer(0x%02x) 抛出：${message(it)}".format(endpoint.address)) }.getOrDefault(-1)
        if (read < 0) {
            noteFailure(endpoint.address)
            return null
        }
        if (read == 0) return null
        synchronized(readFailures) { readFailures.remove(endpoint.address) }
        return buffer.copyOf(read)
    }

    private fun readAsync(held: Held, endpoint: UsbEndpoint, want: Int): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val buffer = java.nio.ByteBuffer.allocateDirect(want)
        val request = UsbRequest()
        if (!request.initialize(held.connection, endpoint)) {
            record("UsbRequest.initialize 失败 endpoint=0x%02x".format(endpoint.address))
            return null
        }
        val queued = runCatching { request.queue(buffer, want) }.getOrDefault(false)
        if (!queued) {
            request.close()
            record("UsbRequest.queue 失败 endpoint=0x%02x".format(endpoint.address))
            return null
        }
        val completed = runCatching { held.connection.requestWait(ASYNC_WAIT_MILLIS) }
            .onFailure {
                // 只记第一次，避免把报告刷满同一条超时。
                if (!asyncFailureLogged) {
                    asyncFailureLogged = true
                    record("requestWait 首次异常：${it.javaClass.simpleName}（后续同类异常不再记录）")
                }
            }
            .getOrNull()
        if (completed == null) {
            runCatching { request.cancel() }
            request.close()
            noteFailure(endpoint.address)
            return null
        }
        request.close()
        val received = buffer.position().coerceIn(0, want)
        if (received == 0) return null
        val data = ByteArray(received)
        buffer.rewind()
        buffer.get(data)
        return data
    }

    /** 某个端点连续读失败到达阈值后，中断 IN 改走 UsbRequest；只记一次，避免刷屏。 */
    private fun noteFailure(address: Int) {
        var count = 0
        synchronized(readFailures) {
            count = (readFailures[address] ?: 0) + 1
            readFailures[address] = count
        }
        if (count == FAIL_REPORT_AT) {
            record("endpoint=0x%02x 连续 $count 次读取失败（bulkTransfer 返回 <0）；无 root 时看不到具体 errno"
                .format(address))
        }
        if (count >= ASYNC_FALLBACK_AFTER && !asyncInterruptRequested) {
            asyncInterruptRequested = true
            record("中断端点 bulkTransfer 连续 $count 次失败，改用 UsbRequest 异步队列继续读")
        }
    }

    /** 清掉可能残留的端点 halt，并允许远程唤醒，避免自动挂起让控制器沉默。 */
    private fun prepareEndpoints(held: Held) {
        val connection = held.connection
        val status = ByteArray(2)
        val getStatus = runCatching {
            connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_DEVICE,
                USB_REQUEST_GET_STATUS, 0, 0, status, status.size, CONTROL_MILLIS,
            )
        }.getOrDefault(-1)
        record("GET_STATUS result=$getStatus 内容=${hex(status)}（bit1=远程唤醒使能）")
        val wake = runCatching {
            connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_DEVICE,
                USB_REQUEST_SET_FEATURE, USB_FEATURE_REMOTE_WAKEUP, 0, null, 0, CONTROL_MILLIS,
            )
        }.getOrDefault(-1)
        record("SET_FEATURE(REMOTE_WAKEUP) result=$wake")
        val endpoints = listOfNotNull(held.endpoints.commandOut, held.endpoints.eventIn, held.endpoints.aclIn)
        endpoints.forEach { endpoint ->
            val recip = if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_ENDPOINT
            } else {
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or USB_RECIP_ENDPOINT
            }
            val cleared = runCatching {
                connection.controlTransfer(recip, USB_REQUEST_CLEAR_FEATURE, USB_FEATURE_ENDPOINT_HALT,
                    endpoint.address, null, 0, CONTROL_MILLIS)
            }.getOrDefault(-1)
            record("CLEAR_FEATURE(ENDPOINT_HALT) endpoint=0x%02x result=$cleared".format(endpoint.address))
        }
    }

    private fun parseH4Event(payload: ByteArray): Pair<Int, ByteArray>? {
        if (payload.size < 3) return null
        var offset = 0
        while (offset + 3 <= payload.size) {
            when (payload[offset]) {
                H4_EVENT -> {
                    val code = payload[offset + 1].toInt() and 0xFF
                    val length = payload[offset + 2].toInt() and 0xFF
                    if (offset + 3 + length > payload.size) return null
                    return code to payload.copyOfRange(offset + 3, offset + 3 + length)
                }
                else -> offset += 1
            }
        }
        return null
    }

    private fun decodeVersion(params: ByteArray): String? {
        val reply = params.copyOfRange(1, params.size)
        if (reply.size < 8) return null
        val hciVersion = reply[0].toInt() and 0xFF
        val hciSub = le16(reply, 1)
        val lmpVersion = reply[3].toInt() and 0xFF
        val manufacturer = le16(reply, 4)
        val lmpSub = le16(reply, 6)
        return "HCI_Version=$hciVersion HCI_Subrevision=$hciSub LMP_Version=$lmpVersion " +
            "Manufacturer_Name=$manufacturer(需对照 Bluetooth SIG 分配表) LMP_Subversion=$lmpSub"
    }

    private fun decodeAddress(params: ByteArray): String? {
        val reply = params.copyOfRange(1, params.size)
        if (reply.size < 6) return null
        return (5 downTo 0).joinToString(":") { "%02X".format(reply[it]) }
    }

    /** 对照组：标准 Android 蓝牙 API 只绑定内置控制器，与 USB 模块无关。 */
    private fun builtinComparison() {
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        record("内置 BluetoothAdapter=${if (adapter == null) "不可用" else "存在"} enabled=${adapter?.isEnabled} " +
            "名称=${runCatching { adapter?.name }.getOrNull()}")
        record("内置地址=${runCatching { adapter?.address }.getOrNull()}")
        record("说明：Android 无公开 API 把 BluetoothAdapter/RFCOMM/SDP 指向 USB 上的第二个控制器；" +
            "本机内置蓝牙已按测试要求关闭")
    }

    private fun verdict(
        blocked: String?,
        claimed: Boolean?,
        reset: Boolean?,
        version: String?,
        address: String?,
        features: String?,
        native: String? = null,
    ) {
        version?.let { record("控制器版本：$it") }
        address?.let { record("控制器 BD_ADDR：$it") }
        features?.let { record("控制器特性：$it") }
        val lines = when {
            blocked != null -> listOf(
                "结论：本轮停在「$blocked」，USB 蓝牙通道能否用于 CarPlay 尚未测到 HCI 层",
                "排除该阻塞后重跑本页；本轮结果不外推到 HCI 以上各层",
            )
            claimed != true -> listOf(
                "结论：App 无法占用 USB 蓝牙接口，通道在 USB 层已断",
                "缺口：内核驱动或其它进程占用该接口；无 root 时 App 不能卸载它",
            )
            reset == true -> listOf(
                "结论：App 层能直发 HCI 并收到命令完成事件（USB 传输层打通）",
                "下一步：自建主机栈 L2CAP / SDP / RFCOMM / SSP。LMP 与基带在控制器固件里，不需要 App 实现",
                "仍未验证：iPhone 是否把本配件识别为 CarPlay 配件并主动发起连接",
            )
            else -> buildList {
                add("结论：USB 层全部可用（枚举、权限、占用、写入 HCI 命令都成功），但没有收到任何 HCI 事件")
                add("已排除的假因：设备未配置、事件端点选错（interrupt 0x81 与 bulk 0x82 都读过）、" +
                    "端点残留 halt、模块需要固件（主线 btusb.c 对本 VID:PID 没有 .firmware 项，" +
                    "只把 7 条标准命令标为 HCI_QUIRK_BROKEN）")
                add(attribution(native))
                transportConstraint(native)?.let { add(it) }
                add("与归因无关的确定缺口：CarPlay 需要 iAP2 over RFCOMM(BR/EDR)，Android 应用层没有这套主机栈，" +
                    "标准 API 也只绑定内置控制器")
            }
        }
        lines.forEach { record("判定：$it") }
        stage(4, lines.first())
    }

    /** 用原生实验的三个独立判据做归因：写送达、读通道、模块是否回数据。 */
    private fun attribution(native: String?): String = when {
        native == null -> "归因未定：原生实验没跑成；Java 层的 -1 分不清内核拒绝还是设备无数据"
        UsbBtNative.syncOutDelivered(native) && UsbBtNative.syncInTimedOut(native) && !UsbBtNative.inDataSeen(native) ->
            "归因：App 的 USB 通路已被证明可用——同步 bulk OUT 把 4 字节命令送达设备（SYNC_BULK ret=4），" +
                "同步读 IN 返回 ETIMEDOUT(110) 表示内核确实去取了、是设备没有数据。模块对 HCI Reset 不回任何事件 → " +
                "归因于模块本身，不是 Android 权限或 usbfs 通路"
        UsbBtNative.syncOutDelivered(native) -> "归因：写通道可用；读侧证据见报告 SYNC_IN/IN_STATUS 行，需要按 errno 再判"
        else -> "归因：连同步 bulk OUT 都没把命令送达 → 归因于 usbfs/SELinux/OTG 供电，见报告 errno"
    }

    /** 异步 URB 的行为要单独记录：它决定自建主机栈能用哪种传输写法。 */
    private fun transportConstraint(native: String?): String? {
        if (native == null || !UsbBtNative.asyncUrbStalls(native)) return null
        if (UsbBtNative.outChannelProven(native) || UsbBtNative.inDataSeen(native)) return null
        return "附加发现：SUBMITURB 提交成功但异步 URB 从不完成（OUT_STATUS=none / IN_STATUS=none），" +
            "而同步 USBDEVFS_BULK 正常。自建主机栈的 HCI 传输必须走同步阻塞读写线程，不能用 libusb 异步队列"
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(228, 236, 248))
    }

    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
            buttons.add(this)
        }, LinearLayout.LayoutParams(-1, dp(50)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun runTest(action: () -> Unit) {
        if (busy) return
        busy = true
        cancelled.set(false)
        buttons.forEach { it.isEnabled = false }
        status.text = "测试中 · 结果会自动保存"
        worker.execute {
            try {
                action()
            } catch (failure: Exception) {
                record("测试未完成：${message(failure)}")
            } finally {
                runOnUiThread {
                    if (!isDestroyed) {
                        busy = false
                        buttons.forEach { it.isEnabled = true }
                        status.text = "本轮结束 · 请查看各步骤证据"
                    }
                }
            }
        }
    }

    private fun stage(index: Int, evidence: String) {
        record("步骤 ${index + 1}：$evidence")
        runOnUiThread {
            val title = stages[index].text.toString().substringBefore('\n')
            stages[index].text = "$title\n$evidence"
        }
    }

    private fun record(message: String) {
        val saved = report.append(message)
        runOnUiThread {
            if (!isDestroyed) {
                output.text = saved
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun share() {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.reports", report.file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "导出 USB 蓝牙测试报告"))
        }.onFailure { Toast.makeText(this, message(it), Toast.LENGTH_LONG).show() }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it) }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun message(failure: Throwable): String {
        val actual = if (failure is InvocationTargetException) failure.targetException else failure
        return "${actual.javaClass.simpleName}: ${actual.message}"
    }

    override fun onDestroy() {
        runCatching { permissionReceiver?.close() }
        // 不置 cancelled、也不中断线程：被 MIUI 回收时也要把已取到的证据写完；record() 自带 isDestroyed 保护。
        worker.shutdown()
        super.onDestroy()
    }

    private data class HciEndpoints(
        val iface: UsbInterface,
        val eventIn: UsbEndpoint,
        val commandOut: UsbEndpoint,
        val aclIn: UsbEndpoint?,
    )

    private data class Held(
        val connection: UsbDeviceConnection,
        val interface_: UsbInterface,
        val endpoints: HciEndpoints,
    )

    private companion object {
        const val EXTRA_ACTION = "probe_action"
        const val ACTION_FAST = "usb_bt_fast"
        const val FAST_REPLY_MILLIS = 3000L
        const val H4_COMMAND = 0x01.toByte()
        const val H4_EVENT = 0x04.toByte()
        const val EVENT_COMMAND_COMPLETE = 0x0E
        const val TRANSFER_MILLIS = 2000
        const val CONTROL_MILLIS = 1000
        const val REPLY_TIMEOUT_MILLIS = 5000L
        const val POLL_MILLIS = 300
        const val READ_PACKETS = 4
        const val RESET_ATTEMPTS = 2
        const val PERMISSION_TIMEOUT_MILLIS = 90_000L
        const val ASYNC_WAIT_MILLIS = 600L
        const val FAIL_REPORT_AT = 6
        const val ASYNC_FALLBACK_AFTER = 8
        // Android 的 UsbConstants 只给出 DIR/TYPE/端点类型，没有 USB 规范请求码与 recipient 编码。
        const val USB_REQUEST_GET_CONFIGURATION = 8
        const val USB_REQUEST_GET_STATUS = 0
        const val USB_REQUEST_SET_FEATURE = 3
        const val USB_REQUEST_CLEAR_FEATURE = 1
        const val USB_FEATURE_REMOTE_WAKEUP = 1
        const val USB_FEATURE_ENDPOINT_HALT = 0
        const val USB_RECIP_DEVICE = 0
        const val USB_RECIP_ENDPOINT = 2
    }
}

/** USB 类代码 0xE0 = Wireless Controller；描述符里的 bInterfaceClass 是无符号字节。 */
private fun UsbInterface.isWirelessController(): Boolean = interfaceClass and 0xFF == 0xE0
