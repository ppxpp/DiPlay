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
import com.shilapi.xcertplay.transport.UsbSessionDiagnostics
import com.shilapi.xcertplay.transport.UsbVerdict
import com.shilapi.xcertplay.transport.LockdownPairingClient
import com.shilapi.xcertplay.transport.LockdownCarKitClient
import com.shilapi.xcertplay.transport.LockdownPairRecord
import com.shilapi.xcertplay.transport.LockdownPlistChannel
import com.shilapi.xcertplay.transport.LockdownPlistValue
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import java.io.File
import java.util.UUID
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
 * NCM（interface 3）强制占用。协商模式继续打开 carkit 并完成 iAP2 身份协商，在 MFi 认证前停止；不创建 VPN / tun / AirPlay。
 */
class UsbCheckEngine(context: Context) {

    enum class Mode { USB_ONLY, NEGOTIATE, DETACH_NEGOTIATE, NATIVE_NEGOTIATE, DISCONNECT_NEGOTIATE, LOW_POWER_DISCONNECT, MFI_AUTH }

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

    val logFile = File(appContext.filesDir, "usbcheck-live.log")
    @Volatile var mode = Mode.NEGOTIATE
        private set
    @Volatile private var cancelled = false
    private val resources = mutableListOf<AutoCloseable>()
    @Volatile private var operationDevice: UsbDevice? = null
    private var pairedRecord: LockdownPairRecord? = null

    private fun <T : AutoCloseable> own(resource: T): T {
        synchronized(lock) {
            if (cancelled) {
                runCatching { resource.close() }
                throw InterruptedException("检查已取消")
            }
            resources.add(resource)
        }
        return resource
    }

    fun stop() {
        val active = synchronized(lock) {
            if (!state.running) return
            cancelled = true
            resources.toList().also { resources.clear() }
        }
        // Keep the queued task alive so its finally block always records cancellation.
        active.asReversed().forEach { runCatching { it.close() } }
        log("停止请求：释放 USB，等待当前有界操作退出")
    }

    private fun checkCancelled() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException("检查已取消")
    }

    fun appendDiagnostic(text: String) = log(text)

    private val host = IphoneUsbHost(
        appContext,
        usbManager,
        IphoneUsbMatcher.appleVendor(),
        onDiagnostic = { line -> log("USB $line") },
        diagnosticClaim = { connection, iface -> claim(connection, iface) },
        diagnosticSetConfiguration = { connection, configuration ->
            if ((mode == Mode.DISCONNECT_NEGOTIATE || mode == Mode.LOW_POWER_DISCONNECT || mode == Mode.MFI_AUTH)) {
                val device = operationDevice ?: error("缺少当前 Apple 设备")
                val active = UsbVerdict.readActiveConfiguration(connection) ?: error("不能读取活动配置")
                val current = (0 until device.configurationCount).map(device::getConfiguration).firstOrNull { it.id == active }
                    ?: error("活动配置不在描述符中: $active")
                val ids = (0 until current.interfaceCount).map(current::getInterface).map { it.id }.distinct().toIntArray()
                log("直接断开对照：active=$active target=${configuration.id} interfaces=${ids.joinToString()}")
                NativeUsbProbe.disconnectAndSelect(connection, configuration.id, ids, ::log)
            } else if (mode == Mode.NATIVE_NEGOTIATE) NativeUsbProbe.selectConfiguration(connection, configuration.id, ::log)
            else connection.setConfiguration(configuration)
        },
    )

    private fun claim(connection: android.hardware.usb.UsbDeviceConnection, iface: android.hardware.usb.UsbInterface): Boolean {
        checkCancelled()
        return if (mode == Mode.NATIVE_NEGOTIATE || mode == Mode.DISCONNECT_NEGOTIATE || mode == Mode.LOW_POWER_DISCONNECT || mode == Mode.MFI_AUTH) NativeUsbProbe.claim(connection, iface, ::log)
        else connection.claimInterface(iface, true)
    }

    fun start(selectedMode: Mode = Mode.NEGOTIATE): Boolean {
        synchronized(lock) {
            if (state.running) return false
            cancelled = false
            mode = selectedMode
            state = State(STEP_SPECS.map { Step(it.first, it.second, Status.PENDING, "") }, emptyList(), state.devices, true)
            runningStepId = null
        }
        notifyUpdate()
        UsbSessionDiagnostics.clear()
        executor.submit { runPipeline() }
        return true
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
            appendLine("DiPlay USB / iAP2 / MFi 检查报告")
            appendLine("模式: $mode；MFI_AUTH 到认证与 NCM 探测，其余协商模式到 MFi 认证前")
            appendLine("包名: ${appContext.packageName}")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine()
            appendLine("== 步骤 ==")
            snapshot.steps.forEach { step ->
                appendLine("[${step.status.label}] ${step.title}" + if (step.detail.isNotBlank()) " — ${step.detail}" else "")
            }
            appendLine()
            appendLine("== USB 判定 ==")
            appendLine(UsbSessionDiagnostics.report())
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
        onUpdate = null
        stop()
        executor.shutdownNow()
        callbackExecutor.shutdownNow()
    }

    private fun runPipeline() {
        val startedAt = SystemClock.elapsedRealtime()
        var mux: Iap2UsbMuxHost? = null
        var bridge: NcmUsbBridge? = null
        var csm: Iap2Session? = null
        try {
            log("=== 开始检查 mode=$mode；MFI_AUTH 执行认证与 NCM 探测，其余协商模式在 MFi 前停止 ===")
            checkCancelled()

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
            if (mode == Mode.LOW_POWER_DISCONNECT) requestLowPower(device, "首次授权后")

            updateStep(CONFIG, Status.RUNNING)
            if (mode == Mode.DETACH_NEGOTIATE) detachActiveInterfaces(device)
            val configList = (0 until device.configurationCount).joinToString("; ") { index ->
                val configuration = device.getConfiguration(index)
                "id=${configuration.id}{${IphoneCarPlayConfiguration.describe(configuration)}}"
            }
            log("USB 配置列表: $configList")
            var configuration = IphoneCarPlayConfiguration.find(device)
            if (configuration != null) {
                updateStep(
                    CONFIG, Status.PASS,
                    "CarPlay 配置描述符已就绪 id=${configuration.id}（描述符就绪≠已激活）；" +
                        "活动配置=${activeConfigurationText(device)}",
                )
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
                if (mode == Mode.LOW_POWER_DISCONNECT) requestLowPower(device, "重新枚举后")
                configuration = IphoneCarPlayConfiguration.find(device)
                    ?: throw StepFailed(REENUM, "重新枚举后仍无 CarPlay 配置（USBMUX+NCM）")
                updateStep(REENUM, Status.PASS, "iPhone 已重新枚举，目标配置描述符 id=${configuration.id}（活动配置另行验证）")
                updateStep(
                    CONFIG, Status.PASS,
                    "重新枚举后 CarPlay 配置描述符就绪 id=${configuration.id}（描述符就绪≠已激活）；" +
                        "活动配置=${activeConfigurationText(device)}",
                )
            }

            UsbVerdict.captureSnapshot(device)?.let { snapshot ->
                UsbSessionDiagnostics.recordSnapshot(snapshot.summary())
                log("USB 绑定快照: ${snapshot.summary()}")
            }
            updateStep(USBMUX_CLAIM, Status.RUNNING)
            val session = own(openUsbmuxSession(device))
            checkCancelled()
            val selected = host.activeConfiguration(device)
            log("配置切换读回: target=${configuration.id} active=${selected ?: "unknown"}")
            if (selected != configuration.id) throw StepFailed(USBMUX_CLAIM, "不能确认目标配置已激活：target=${configuration.id} active=$selected")
            updateStep(USBMUX_CLAIM, Status.PASS, "目标配置已读回确认 + USBMUX 接口占用成功")

            updateStep(USBMUX_IO, Status.RUNNING)
            try {
                mux = own(Iap2UsbMuxHost.open(session, USBMUX_READ_TIMEOUT_MILLIS) { line -> log("USBMUX $line") })
                updateStep(USBMUX_IO, Status.PASS, "收到 iPhone 的 USBMUX 版本应答，USB 双向数据通讯正常")
            } catch (error: Throwable) {
                checkCancelled()
                runCatching { session.close() }
                val message = error.message ?: error.javaClass.simpleName
                updateStep(USBMUX_IO, Status.FAIL, message)
                log("USBMUX 握手失败（继续检查接口占用）：$message")
            }

            checkCancelled()
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
            val openedBridge = try {
                NcmUsbBridge.open(connection, function, device, diagnosticClaim = { conn, iface -> claim(conn, iface) }) { line -> log("NCM $line") }
            } catch (error: IphoneUsbException) {
                val verdict = UsbSessionDiagnostics.verdict() ?: UsbVerdict.forUsbFailure(
                    device = device,
                    connection = null,
                    claimInterfaceId = function.control.id,
                    targetConfiguration = IphoneCarPlayConfiguration.find(device)?.id,
                    switchSucceeded = true,
                )
                log(verdict.line)
                log("判定建议: ${verdict.remedy}")
                throw StepFailed(NCM_CLAIM, "NCM 接口无法强制占用（内核驱动或其它进程可能占用着它）：${error.message}")
            }
            bridge = own(openedBridge)
            checkCancelled()
            val mac = openedBridge.hostMac?.joinToString(":") { "%02x".format(it) } ?: "读取失败"
            val claimedIds = listOf(function.control.id, function.data.id).distinct().sorted().joinToString("/")
            val matchesReport = function.control.id == REPORTED_INTERFACE_NUMBER || function.data.id == REPORTED_INTERFACE_NUMBER
            updateStep(
                NCM_CLAIM, Status.PASS,
                "强制占用成功：$ifaceText；hostMac=$mac\n" +
                    "生产默认 interfaceNumber=$REPORTED_INTERFACE_NUMBER；本测试使用实际控制接口号 ${function.control.id}。" +
                    if (matchesReport) "一致（占用接口 $claimedIds）" else "不一致（实际占用 $claimedIds）",
            )

            if (mode == Mode.USB_ONLY) {
                listOf(LOCKDOWN, CARKIT, IAP2_LINK, IDENTIFY, MFI, NCM_IO).forEach { updateStep(it, Status.SKIP, "USB_ONLY 模式") }
            } else {
                val readyMux = mux ?: throw StepFailed(LOCKDOWN, "USBMUX 握手未通过，不能进入协商")
                checkCancelled()
                updateStep(LOCKDOWN, Status.RUNNING, "解锁 iPhone，并允许信任；最多等待 60 秒")
                // A harmless query proves that the paired service is actually reachable.
                LockdownPlistChannel(readyMux.connect()).use { channel ->
                    val reply = channel.request(LockdownPlistValue.Dictionary(mapOf(
                        "Label" to LockdownPlistValue.Text("DiPlay USB Check"),
                        "Request" to LockdownPlistValue.Text("QueryType"),
                    )))
                    val type = (reply.entries["Type"] as? LockdownPlistValue.Text)?.value
                    if (type != "com.apple.mobile.lockdown") throw StepFailed(LOCKDOWN, "QueryType 返回异常 Type=$type")
                    log("USB Lockdown QueryType 已应答 type=$type")
                    for (key in listOf("ProductVersion", "TimeIntervalSince1970")) {
                        runCatching {
                            val value = channel.request(LockdownPlistValue.Dictionary(mapOf(
                                "Label" to LockdownPlistValue.Text("DiPlay USB Check"),
                                "Request" to LockdownPlistValue.Text("GetValue"), "Key" to LockdownPlistValue.Text(key),
                            )))
                            val text = when (val item = value.entries["Value"]) {
                                is LockdownPlistValue.Text -> item.value
                                is LockdownPlistValue.Integer -> item.value.toString()
                                else -> "unavailable"
                            }
                            log("LOCKDOWN_INFO key=$key value=$text androidTimeMillis=${System.currentTimeMillis()}")
                        }.onFailure { log("LOCKDOWN_INFO key=$key unavailable=${it.message}") }
                    }
                }
                // Pair material is deliberately memory-only. A newly generated certificate chain
                // must have a new host identity; reusing persisted IDs with new keys is rejected
                // once the phone has trusted the old identity.
                val record = pairedRecord ?: run {
                    val hostId = UUID.randomUUID().toString().uppercase(Locale.US)
                    val systemBuid = UUID.randomUUID().toString().uppercase(Locale.US)
                    log("LOCKDOWN_NEW_IDENTITY freshHostAndBuid=true certificateStorage=memory-only")
                    LockdownPairingClient(readyMux, onDiagnostic = { log(it) }).pair(
                        label = "DiPlay", hostId = hostId, systemBuid = systemBuid,
                        totalTimeoutMillis = 60_000, keepConnectionWhilePending = true, isCancelled = { cancelled },
                    ).pairRecord.also { pairedRecord = it }
                }
                checkCancelled()
                updateStep(LOCKDOWN, Status.PASS, "Lockdown 配对完成（配对素材仅保存在本进程内存）")
                updateStep(CARKIT, Status.RUNNING)
                val stream = own(LockdownCarKitClient(readyMux).open(record, "DiPlay"))
                updateStep(CARKIT, Status.PASS, "com.apple.carkit.service 已经通过 USB 打开")
                checkCancelled()
                updateStep(IAP2_LINK, Status.RUNNING)
                csm = own(Iap2Session.open(stream, traceContext = "usbcheck", onTrace = { log(if (it.contains("0xaa")) it.substringBefore('\n') else it) }))
                if (!csm.awaitReady(15_000)) throw StepFailed(IAP2_LINK, "iAP2 link negotiation 超时")
                updateStep(IAP2_LINK, Status.PASS, "iAP2 CSM 控制链路已协商就绪")
                checkCancelled()
                updateStep(IDENTIFY, Status.RUNNING)
                Iap2IdentificationClient(csm).identify(Iap2IdentificationConfig(
                    name = "DiPlay", modelIdentifier = android.os.Build.MODEL.ifBlank { "USBCheck" },
                    manufacturer = android.os.Build.MANUFACTURER.ifBlank { "DiPlay" },
                    serialNumber = "DIPLAY-USBCHECK", firmwareVersion = "0.1.0", hardwareVersion = "1.0",
                    carPlayUsbInterfaceNumber = function.control.id,
                ), timeoutMillis = 15_000, onProgress = { log(it) })
                checkCancelled()
                updateStep(IDENTIFY, Status.PASS, "收到 iAP2 IdentificationAccepted (0x1d02)")
                if (mode == Mode.MFI_AUTH) {
                    updateStep(MFI, Status.RUNNING)
                    val directory = File(appContext.noBackupFilesDir, "offline-mfi")
                    directory.mkdirs()
                    for (name in listOf("identity.pk8", "certificate.p7b")) {
                        val file = File(directory, name)
                        appContext.assets.open("offline-mfi/$name").use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                        file.setReadable(false, false); file.setReadable(true, true)
                        file.setWritable(false, false); file.setWritable(true, true)
                    }
                    val authentication = LocalMfiAuthenticationClient.load(directory) { size -> log("MFI local signature challengeBytes=$size") }
                    Iap2MfiAuthenticationClient(authentication).run(csm, 30_000) { log(it) }
                    checkCancelled()
                    updateStep(MFI, Status.PASS, "iPhone 返回 AuthenticationSucceeded (0xaa05)")
                    log("USB_MFI_AUTH_OK: 身份协商与 MFi 认证通过，未启动 VPN 或 AirPlay 会话")
                    updateStep(NCM_IO, Status.RUNNING, "直接在 USB NCM 上探测 IPv6 收发，不依赖 VPN")
                    val probe = NcmPacketProbe.run(openedBridge, { cancelled }, ::log)
                    val detail = "USB NCM tx=${probe.tx} rx=${probe.rx} echoReply=${probe.echoReply}"
                    log(detail)
                    updateStep(NCM_IO, if (probe.rx > 0) Status.PASS else Status.SKIP,
                        detail + if (probe.echoReply) "；收到匹配的 ICMPv6 应答" else if (probe.rx > 0) "；收到手机以太网帧，未确认 Echo 往返" else "；无应答不能证明接口故障，尚未宣告 CarPlay 网络会话")
                } else {
                    updateStep(MFI, Status.SKIP, "本模式在 MFi 认证前停止")
                    updateStep(NCM_IO, Status.SKIP, "本模式不进行网络数据探测")
                    log("NEGOTIATION_ENTRY_OK: USB carkit → iAP2 ready → IdentificationAccepted；MFi_AUTH_NOT_STARTED")
                }
            }

            val failedSteps = state.steps.filter { it.status == Status.FAIL }
            if (failedSteps.isEmpty()) {
                updateStep(
                    SUMMARY, Status.PASS,
                    if (mode == Mode.USB_ONLY) "USB 前置步骤通过；未执行协商入口验证" else
                        if (mode == Mode.MFI_AUTH) "USB 接管、Lockdown、carkit、iAP2 身份协商与 MFi 认证通过；网络探测详见结果，未启动完整 CarPlay 会话。" else
                        "达到目标：USB carkit 与 iAP2 身份协商通过，已在 MFi 认证之前主动停止。未启动 CarPlay 会话。",
                )
            } else {
                updateStep(SUMMARY, Status.FAIL, "存在失败步骤：" + failedSteps.joinToString("、") { it.title })
            }
        } catch (error: Throwable) {
            usbManager.deviceList.values.filter { it.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID }.forEach { device ->
                UsbVerdict.captureSnapshot(device)?.let { log("失败时 USB 快照: ${it.summary()}") }
            }
            val failedId = (error as? StepFailed)?.id ?: runningStepId ?: SUMMARY
            val message = if (cancelled) "检查已取消" else error.message ?: error.javaClass.simpleName
            updateStep(failedId, Status.FAIL, message)
            log("步骤失败 [${titleOf(failedId)}]: $message")
            updateStep(SUMMARY, Status.FAIL, "失败于「${titleOf(failedId)}」：${message.lineSequence().first()}")
        } finally {
            runCatching { csm?.close() }
            runCatching { bridge?.close() }
            runCatching { mux?.close() }
            val leftovers = synchronized(lock) { resources.toList().also { resources.clear() } }
            leftovers.asReversed().forEach { runCatching { it.close() } }
            log("=== 检查结束，用时 %.1fs ===".format((SystemClock.elapsedRealtime() - startedAt) / 1000.0))
            saveReportFile()
            synchronized(lock) { state = state.copy(running = false) }
            notifyUpdate()
        }
    }

    private fun requestLowPower(device: UsbDevice, stage: String) {
        checkCancelled()
        require(device.vendorId == IphoneUsbMatcher.APPLE_VENDOR_ID)
        val connection = usbManager.openDevice(device) ?: throw StepFailed(CONFIG, "低充电实验 openDevice 失败")
        try {
            // Apple's USB power-capability request: zero extra current, as used by Linux's
            // apple-mfi-fastcharge trickle mode. This is independent of iAP2 PowerSourceUpdate.
            val result = connection.controlTransfer(0x40, 0x40, 0, 0, null, 0, 1_000)
            log("USB_POWER_CAPABILITY stage=$stage extraCurrentMa=0 request=0x40 value=0 index=0 result=$result")
            if (result != 0) throw StepFailed(CONFIG, "iPhone 未接受低充电请求 result=$result；停止本对照实验")
        } finally { connection.close() }
    }

    private fun detachActiveInterfaces(device: UsbDevice) {
        checkCancelled()
        val connection = usbManager.openDevice(device) ?: throw StepFailed(CONFIG, "openDevice 返回 null")
        own(AutoCloseable { connection.close() })
        try {
            val active = UsbVerdict.readActiveConfiguration(connection)
                ?: throw StepFailed(CONFIG, "不能读取活动配置，停止预解绑实验")
            val config = (0 until device.configurationCount).map(device::getConfiguration).firstOrNull { it.id == active }
                ?: throw StepFailed(CONFIG, "活动配置 $active 不在描述符内")
            log("对照实验：只释放 Apple 当前配置 $active 的接口")
            (0 until config.interfaceCount).map(config::getInterface).distinctBy { it.id }.forEach { iface ->
                checkCancelled()
                val claimed = connection.claimInterface(iface, true)
                val released = claimed && connection.releaseInterface(iface)
                log("pre-detach iface=${iface.id}/${iface.alternateSetting} class=${iface.interfaceClass} claim=$claimed release=$released")
            }
        } finally { connection.close() }
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
                checkCancelled()
                if (denied.get()) throw StepFailed(PERMISSION, "USB 权限被拒绝。请在系统设置中允许本应用访问该 USB 设备")
                if (granted.get() || usbManager.hasPermission(device)) return device
                latch.await(300, TimeUnit.MILLISECONDS)
            }
            throw StepFailed(PERMISSION, "等待 USB 权限超时（${PERMISSION_TIMEOUT_MILLIS / 1000}s）。确认系统弹窗未被车机界面遮挡")
        } finally {
            receiver.close()
        }
    }

    private fun awaitCallback(latch: CountDownLatch, timeoutSeconds: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutSeconds * 1_000
        while (SystemClock.elapsedRealtime() < deadline) {
            checkCancelled()
            if (latch.await(100, TimeUnit.MILLISECONDS)) { checkCancelled(); return true }
        }
        return false
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
        if (!awaitCallback(latch, REQUEST_TIMEOUT_SECONDS)) {
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
                checkCancelled()
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
        operationDevice = device
        val latch = CountDownLatch(1)
        val result = AtomicReference<IphoneUsbHost.Iap2SessionResult?>(null)
        val resultLock = Any()
        var abandoned = false
        host.openIap2UsbSessionAsync(device, callbackExecutor) { sessionResult ->
            synchronized(resultLock) {
                if (abandoned && sessionResult is IphoneUsbHost.Iap2SessionResult.Connected) {
                    sessionResult.session.close()
                } else result.set(sessionResult)
            }
            latch.countDown()
        }
        try {
            if (!awaitCallback(latch, SESSION_TIMEOUT_SECONDS)) throw StepFailed(USBMUX_CLAIM, "打开 USBMUX 会话超时")
            return when (val sessionResult = result.get()!!) {
                is IphoneUsbHost.Iap2SessionResult.Connected -> sessionResult.session
                is IphoneUsbHost.Iap2SessionResult.Failed ->
                    throw StepFailed(USBMUX_CLAIM, sessionResult.error.message ?: sessionResult.error.javaClass.simpleName)
            }
        } catch (error: Throwable) {
            synchronized(resultLock) {
                abandoned = true
                (result.get() as? IphoneUsbHost.Iap2SessionResult.Connected)?.session?.close()
            }
            throw error
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
            runCatching { logFile.appendText(line + "\n") }
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

    /** GET_CONFIGURATION over a brief extra open; 描述符里列出配置不等于 iPhone 已激活它。 */
    private fun activeConfigurationText(device: UsbDevice): String =
        host.activeConfiguration(device)?.toString() ?: "未知（读取失败）"

    private fun titleOf(id: String): String = STEP_SPECS.firstOrNull { it.first == id }?.second ?: id

    companion object {
        private const val DETECT = "detect"
        private const val PERMISSION = "permission"
        private const val CONFIG = "config"
        private const val REENUM = "reenum"
        private const val USBMUX_CLAIM = "usbmux-claim"
        private const val USBMUX_IO = "usbmux-io"
        private const val NCM_CLAIM = "ncm-claim"
        private const val LOCKDOWN = "lockdown"
        private const val CARKIT = "carkit"
        private const val IAP2_LINK = "iap2-link"
        private const val IDENTIFY = "identify"
        private const val MFI = "mfi"
        private const val NCM_IO = "ncm-io"
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
            LOCKDOWN to "8. USB Lockdown 配对",
            CARKIT to "9. 打开 USB carkit 服务",
            IAP2_LINK to "10. iAP2 控制链路协商",
            IDENTIFY to "11. iAP2 身份协商",
            MFI to "12. iAP2 MFi 认证",
            NCM_IO to "13. USB NCM 网络数据收发",
            SUMMARY to "14. 汇总",
        )
    }
}
