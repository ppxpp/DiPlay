package com.shilapi.xcertplay.usbcheck

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.shilapi.xcertplay.transport.Iap2UsbMuxHost
import com.shilapi.xcertplay.transport.Iap2UsbSession
import com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration
import com.shilapi.xcertplay.transport.IphoneUsbException
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import com.shilapi.xcertplay.transport.NcmFunctionDiscovery
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 只跑到"生产 App 即将建立 VPN 隧道"为止的 USB 链路检查：
 * 发现设备 → 权限 → CarPlay 配置（缺失时请求切换并等待重新枚举）→ USBMUX 占用与数据握手 →
 * NCM（interface 3）强制占用。全程不创建 VpnService / tun / NCM 桥。
 */
class UsbCheckEngine(context: Context) {

    enum class Status(val label: String) {
        PENDING("等待"), RUNNING("进行中"), PASS("通过"), FAIL("失败"), SKIP("跳过")
    }

    data class Step(val id: String, val title: String, val status: Status, val detail: String)

    data class DeviceLine(val text: String, val apple: Boolean)

    data class State(
        val steps: List<Step>,
        val log: List<String>,
        val devices: List<DeviceLine>,
        val running: Boolean,
    )

    private class StepFailed(val id: String, message: String) : Exception(message)

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "usbcheck-pipeline") }
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "usbcheck-callback") }

    @Volatile private var runningStepId: String? = null
    @Volatile var onUpdate: (() -> Unit)? = null

    @Volatile
    var state: State = State(STEP_SPECS.map { Step(it.first, it.second, Status.PENDING, "") }, emptyList(), emptyList(), false)
        private set

    private val host = IphoneUsbHost(
        appContext,
        usbManager,
        IphoneUsbMatcher.appleVendor(),
        onDiagnostic = { line -> log("USB $line") },
    )

    fun start() {
        synchronized(lock) {
            if (state.running) return
            state = State(STEP_SPECS.map { Step(it.first, it.second, Status.PENDING, "") }, emptyList(), state.devices, true)
            runningStepId = null
        }
        notifyUpdate()
        executor.execute { runPipeline() }
    }

    /** 主线程每 2s 调用：刷新"系统可见 USB 设备"面板。 */
    fun refreshDevices() {
        val lines = usbManager.deviceList.values.map { device ->
            DeviceLine(
                text = "VID=0x%04x PID=0x%04x 接口=%d 权限=%s %s".format(
                    device.vendorId, device.productId, device.interfaceCount,
                    if (usbManager.hasPermission(device)) "有" else "无", device.deviceName,
                ),
                apple = device.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID,
            )
        }.sortedBy { it.text }
        var changed = false
        synchronized(lock) {
            if (lines != state.devices) {
                state = state.copy(devices = lines)
                changed = true
            }
        }
        if (changed) notifyUpdate()
    }

    fun noteAttach(text: String) {
        log("系统事件: 插入 $text")
        refreshDevices()
    }

    fun noteDetach(text: String) {
        log("系统事件: 拔出 $text")
        refreshDevices()
    }

    fun report(): String {
        val snapshot = state
        return buildString {
            appendLine("DiPlay USB 链路检查报告（VPN 之前）")
            appendLine("包名: ${appContext.packageName}")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine()
            appendLine("== 步骤 ==")
            snapshot.steps.forEach { step ->
                appendLine("[${step.status.label}] ${step.title}" + if (step.detail.isNotBlank()) " — ${step.detail}" else "")
            }
            appendLine()
            appendLine("== 系统可见 USB 设备 ==")
            if (snapshot.devices.isEmpty()) appendLine("（无）") else snapshot.devices.forEach { appendLine(it.text) }
            appendLine()
            appendLine("== 日志 ==")
            snapshot.log.forEach { appendLine(it) }
        }
    }

    /**
     * 把当前报告写入应用文件目录（内部存储 + 外部存储副本各一份），返回内部存储路径。
     *
     * 车机上无法同时连 ADB 和 iPhone，检查结束后可拔掉 iPhone 再插 ADB，用
     * `adb exec-out run-as <包名> cat files/usbcheck-report.txt` 把报告取走。
     */
    fun saveReportFile(): String {
        val content = report()
        val internalFile = File(appContext.filesDir, REPORT_FILE_NAME)
        val internalError = runCatching { internalFile.writeText(content) }.exceptionOrNull()
        if (internalError == null) {
            log("报告已保存: ${internalFile.absolutePath}")
        } else {
            log("内部报告保存失败: ${internalError.message}")
        }
        appContext.getExternalFilesDir(null)?.let { dir ->
            val externalFile = File(dir, REPORT_FILE_NAME)
            runCatching { externalFile.writeText(content) }
                .onSuccess { log("报告副本: ${externalFile.absolutePath}") }
                .onFailure { log("外部报告副本保存失败: ${it.message}") }
        }
        if (internalError == null) {
            log("取出方式: adb exec-out run-as ${appContext.packageName} cat files/$REPORT_FILE_NAME")
        }
        return if (internalError == null) internalFile.absolutePath else ""
    }

    /** 清空日志（步骤状态与设备列表保留），检查进行中新日志会继续追加。 */
    fun clearLog() {
        synchronized(lock) { state = state.copy(log = emptyList()) }
        notifyUpdate()
        log("日志已清空，新的日志会继续追加")
    }

    fun shutdown() {
        executor.shutdownNow()
        callbackExecutor.shutdownNow()
    }

    private fun runPipeline() {
        val startedAt = SystemClock.elapsedRealtime()
        var mux: Iap2UsbMuxHost? = null
        var bridge: NcmUsbBridge? = null
        try {
            log("=== 开始检查（只验证 VPN 之前的 USB 步骤）===")

            updateStep(DETECT, Status.RUNNING)
            val initial = host.discover().firstOrNull()
                ?: throw StepFailed(
                    DETECT,
                    "系统未发现 Apple 设备 (VID=0x05ac)。\n当前系统可见设备：\n${deviceSummary()}",
                )
            log("发现 iPhone VID=0x%04x PID=0x%04x".format(initial.vendorId, initial.productId))
            updateStep(DETECT, Status.PASS, "VID=0x%04x PID=0x%04x".format(initial.vendorId, initial.productId))

            updateStep(PERMISSION, Status.RUNNING)
            var device = awaitPermission(initial)
            updateStep(PERMISSION, Status.PASS, "USB 访问权限已授予")

            updateStep(CONFIG, Status.RUNNING)
            val configList = (0 until device.configurationCount).joinToString("; ") { index ->
                val configuration = device.getConfiguration(index)
                "id=${configuration.id}{${IphoneCarPlayConfiguration.describe(configuration)}}"
            }
            log("USB 配置列表: $configList")
            var configuration = IphoneCarPlayConfiguration.find(device)
            if (configuration != null) {
                updateStep(CONFIG, Status.PASS, "CarPlay 配置已就绪 id=${configuration.id}")
                updateStep(REENUM, Status.SKIP, "无需切换")
            } else {
                log("描述符中没有 CarPlay 配置（USBMUX+NCM 缺失），发送 CarPlay 模式切换请求")
                updateStep(REENUM, Status.RUNNING)
                requestReenumeration(device)
                updateStep(REENUM, Status.RUNNING, "已发送切换请求，等待 iPhone 重新枚举…")
                val reenumerated = awaitCarPlayEnumeration(REENUMERATION_TIMEOUT_MILLIS)
                    ?: throw StepFailed(
                        REENUM,
                        "请求后 ${REENUMERATION_TIMEOUT_MILLIS / 1000}s 内未等到含 CarPlay 配置的重新枚举。\n" +
                            "提示：解锁 iPhone 并点“信任”；换数据线（需数据线而非充电线）或换 USB 口。\n" +
                            "当前系统可见设备：\n${deviceSummary()}",
                    )
                device = awaitPermission(reenumerated)
                configuration = IphoneCarPlayConfiguration.find(device)
                    ?: throw StepFailed(REENUM, "重新枚举后仍无 CarPlay 配置（USBMUX+NCM）")
                updateStep(REENUM, Status.PASS, "iPhone 已切换到 CarPlay 配置 id=${configuration.id}")
                updateStep(CONFIG, Status.PASS, "重新枚举后 CarPlay 配置就绪 id=${configuration.id}")
            }

            updateStep(USBMUX_CLAIM, Status.RUNNING)
            val session = openUsbmuxSession(device)
            updateStep(USBMUX_CLAIM, Status.PASS, "配置切换 + USBMUX 接口强制占用成功")

            updateStep(USBMUX_IO, Status.RUNNING)
            try {
                mux = Iap2UsbMuxHost.open(session, USBMUX_READ_TIMEOUT_MILLIS) { line -> log("USBMUX $line") }
                updateStep(USBMUX_IO, Status.PASS, "收到 iPhone 的 USBMUX 版本应答，USB 双向数据通讯正常")
            } catch (error: Throwable) {
                val message = error.message ?: error.javaClass.simpleName
                updateStep(USBMUX_IO, Status.FAIL, message)
                log("USBMUX 握手失败（继续检查接口占用）：$message")
            }

            updateStep(NCM_CLAIM, Status.RUNNING)
            val activeConfiguration = IphoneCarPlayConfiguration.find(device)
                ?: throw StepFailed(NCM_CLAIM, "读取不到 CarPlay 配置")
            val function = NcmFunctionDiscovery.find(activeConfiguration)
                ?: throw StepFailed(NCM_CLAIM, "CarPlay 配置中未找到 NCM 功能（控制/数据接口）")
            val ifaceText = "控制 iface=${function.control.id}/${function.control.alternateSetting} " +
                "数据 iface=${function.data.id}/${function.data.alternateSetting} alt=1"
            log(
                "NCM $ifaceText in=0x%02x out=0x%02x".format(
                    function.bulkIn.address, function.bulkOut.address,
                ),
            )
            val connection = usbManager.openDevice(device)
                ?: throw StepFailed(NCM_CLAIM, "openDevice 返回 null（权限或内核问题）")
            bridge = NcmUsbBridge.open(connection, function) { line -> log("NCM $line") }
            val mac = bridge.hostMac?.joinToString(":") { "%02x".format(it) } ?: "读取失败"
            val claimedIds = listOf(function.control.id, function.data.id).distinct().sorted().joinToString("/")
            val matchesReport = function.control.id == REPORTED_INTERFACE_NUMBER || function.data.id == REPORTED_INTERFACE_NUMBER
            updateStep(
                NCM_CLAIM, Status.PASS,
                "强制占用成功：$ifaceText；hostMac=$mac\n" +
                    "与生产上报给 iPhone 的 interfaceNumber=$REPORTED_INTERFACE_NUMBER " +
                    if (matchesReport) "一致（占用接口 $claimedIds）" else "不一致（实际占用 $claimedIds）",
            )

            val failedSteps = state.steps.filter { it.status == Status.FAIL }
            if (failedSteps.isEmpty()) {
                updateStep(
                    SUMMARY, Status.PASS,
                    "VPN 之前的全部 USB 步骤通过（识别→权限→配置→USBMUX 数据通讯→接口 $REPORTED_INTERFACE_NUMBER 占用）。\n" +
                        "生产 App 在下一步才建立 VPN 隧道与 NCM 数据桥，本测试不涉及。",
                )
            } else {
                updateStep(SUMMARY, Status.FAIL, "存在失败步骤：" + failedSteps.joinToString("、") { it.title })
            }
        } catch (error: Throwable) {
            val failedId = (error as? StepFailed)?.id ?: runningStepId ?: SUMMARY
            val message = error.message ?: error.javaClass.simpleName
            updateStep(failedId, Status.FAIL, message)
            log("步骤失败 [${titleOf(failedId)}]: $message")
            updateStep(SUMMARY, Status.FAIL, "失败于「${titleOf(failedId)}」：${message.lineSequence().first()}")
        } finally {
            runCatching { bridge?.close() }
            runCatching { mux?.close() }
            synchronized(lock) { state = state.copy(running = false) }
            notifyUpdate()
            log("=== 检查结束，用时 %.1fs ===".format((SystemClock.elapsedRealtime() - startedAt) / 1000.0))
            saveReportFile()
        }
    }

    private fun awaitPermission(device: UsbDevice): UsbDevice {
        val request = try {
            host.requestPermission(device)
        } catch (error: IphoneUsbException) {
            throw StepFailed(PERMISSION, error.message ?: "请求 USB 权限失败")
        }
        if (request is IphoneUsbHost.PermissionRequest.AlreadyGranted) return request.device
        log("已弹出 USB 权限请求（若系统弹窗出现请点“允许”，或勾选默认允许）")
        val granted = AtomicBoolean(false)
        val denied = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val receiver = host.registerPermissionReceiver { result ->
            when (result) {
                is IphoneUsbHost.PermissionResult.Granted -> {
                    granted.set(true)
                    latch.countDown()
                }
                is IphoneUsbHost.PermissionResult.Denied -> {
                    denied.set(true)
                    latch.countDown()
                }
            }
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + PERMISSION_TIMEOUT_MILLIS
            while (SystemClock.elapsedRealtime() < deadline) {
                if (denied.get()) throw StepFailed(PERMISSION, "USB 权限被拒绝。请在系统设置中允许本应用访问该 USB 设备")
                if (granted.get() || usbManager.hasPermission(device)) return device
                latch.await(300, TimeUnit.MILLISECONDS)
            }
            throw StepFailed(PERMISSION, "等待 USB 权限超时（${PERMISSION_TIMEOUT_MILLIS / 1000}s）。确认系统弹窗未被车机界面遮挡")
        } finally {
            receiver.close()
        }
    }

    private fun requestReenumeration(device: UsbDevice) {
        val latch = CountDownLatch(1)
        val failure = AtomicReference<IphoneUsbException?>(null)
        host.requestCarPlayReenumerationAsync(device, callbackExecutor) { transition ->
            when (transition) {
                is IphoneUsbHost.TransitionResult.ReenumerationRequested -> Unit
                is IphoneUsbHost.TransitionResult.Failed -> failure.set(transition.error)
            }
            latch.countDown()
        }
        if (!latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw StepFailed(REENUM, "CarPlay 模式切换请求超时")
        }
        failure.get()?.let { error ->
            // iOS 12/15 会拒绝该请求（#509/#455）；若描述符已能读出 CarPlay 配置则继续。
            if (IphoneCarPlayConfiguration.find(device) != null) {
                log("切换请求被拒绝（${error.message}），但 CarPlay 配置已可读取，继续")
            } else {
                throw StepFailed(REENUM, "CarPlay 模式切换请求失败：${error.message}")
            }
        }
    }

    private fun awaitCarPlayEnumeration(timeoutMillis: Long): UsbDevice? {
        val latch = CountDownLatch(1)
        val attached = AtomicReference<UsbDevice?>(null)
        val receiver = host.registerAttachReceiver { device ->
            if (attached.compareAndSet(null, device)) latch.countDown()
        }
        try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMillis
            while (SystemClock.elapsedRealtime() < deadline) {
                attached.get()?.let { return it }
                // 部分车机 ROM 不派发 attach 广播，轮询 deviceList 兜底。
                usbManager.deviceList.values.firstOrNull {
                    it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID && IphoneCarPlayConfiguration.find(it) != null
                }?.let { return it }
                latch.await(500, TimeUnit.MILLISECONDS)
            }
            return attached.get()
        } finally {
            receiver.close()
        }
    }

    private fun openUsbmuxSession(device: UsbDevice): Iap2UsbSession {
        val latch = CountDownLatch(1)
        val result = AtomicReference<IphoneUsbHost.Iap2SessionResult?>(null)
        host.openIap2UsbSessionAsync(device, callbackExecutor) { sessionResult ->
            result.set(sessionResult)
            latch.countDown()
        }
        if (!latch.await(SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw StepFailed(USBMUX_CLAIM, "打开 USBMUX 会话超时")
        }
        return when (val sessionResult = result.get()!!) {
            is IphoneUsbHost.Iap2SessionResult.Connected -> sessionResult.session
            is IphoneUsbHost.Iap2SessionResult.Failed ->
                throw StepFailed(USBMUX_CLAIM, sessionResult.error.message ?: sessionResult.error.javaClass.simpleName)
        }
    }

    private fun updateStep(id: String, status: Status, detail: String = "") {
        if (status == Status.RUNNING) runningStepId = id
        synchronized(lock) {
            state = state.copy(
                steps = state.steps.map { if (it.id == id) it.copy(status = status, detail = detail) else it },
            )
        }
        notifyUpdate()
    }

    private fun log(text: String) {
        val line = "${nowText()} $text"
        synchronized(lock) {
            state = state.copy(log = (state.log + line).takeLast(MAX_LOG_LINES))
        }
        notifyUpdate()
    }

    private fun nowText(): String = synchronized(timeFormat) { timeFormat.format(Date()) }

    private fun notifyUpdate() {
        runCatching { onUpdate?.invoke() }
    }

    private fun deviceSummary(): String =
        usbManager.deviceList.values.joinToString("\n") { device ->
            "  VID=0x%04x PID=0x%04x %s".format(device.vendorId, device.productId, device.deviceName)
        }.ifEmpty { "  （系统 UsbManager 看不到任何 USB 设备）" }

    private fun titleOf(id: String): String = STEP_SPECS.firstOrNull { it.first == id }?.second ?: id

    companion object {
        private const val DETECT = "detect"
        private const val PERMISSION = "permission"
        private const val CONFIG = "config"
        private const val REENUM = "reenum"
        private const val USBMUX_CLAIM = "usbmux-claim"
        private const val USBMUX_IO = "usbmux-io"
        private const val NCM_CLAIM = "ncm-claim"
        private const val SUMMARY = "summary"

        /** 生产 App 在 iAP2 Identify 中上报的 CarPlay USB 接口号。 */
        private const val REPORTED_INTERFACE_NUMBER = 3

        private const val PERMISSION_TIMEOUT_MILLIS = 30_000L
        private const val REENUMERATION_TIMEOUT_MILLIS = 30_000L
        private const val SESSION_TIMEOUT_SECONDS = 60L
        private const val REQUEST_TIMEOUT_SECONDS = 30L
        private const val USBMUX_READ_TIMEOUT_MILLIS = 1_000L
        private const val MAX_LOG_LINES = 900
        private const val REPORT_FILE_NAME = "usbcheck-report.txt"

        private val STEP_SPECS = listOf(
            DETECT to "1. 发现 Apple USB 设备",
            PERMISSION to "2. 获取 USB 访问权限",
            CONFIG to "3. 检查 CarPlay USB 配置（USBMUX+NCM）",
            REENUM to "4. 请求切换 CarPlay 模式并等待重新枚举",
            USBMUX_CLAIM to "5. 切换配置并强制占用 USBMUX 接口",
            USBMUX_IO to "6. USBMUX 双向数据握手",
            NCM_CLAIM to "7. 强制占用 NCM 接口（interface $REPORTED_INTERFACE_NUMBER）",
            SUMMARY to "8. 汇总",
        )
    }
}
