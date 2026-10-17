package com.shilapi.xcertplay.btprobe

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 1280×720 横屏：左侧步骤，右侧证据；设备选择和报告独立于主 DiPlay。 */
class ProbeActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private lateinit var report: ProbeReport
    private lateinit var output: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var devices: Spinner
    private lateinit var status: TextView
    private val buttons = mutableListOf<Button>()
    private val stages = mutableListOf<TextView>()
    private val pioneer = PioneerAdapter()
    private var paired = emptyList<PairedDevice>()
    private var adapterAvailable = false
    private var busy = false
    private var iosCallbackRegistered = false
    private var iosSppRequested = false
    private var receiverRegistered = false
    private val events = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val extras = runCatching { intent.extras?.keySet()?.joinToString { key ->
                "$key=${intent.extras?.get(key)}"
            } }.getOrElse { "extras 无法解码：${message(it)}" }
            record("蓝牙事件 ${intent.action} $extras")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = ProbeReport(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        record("新测试会话 uid=${Process.myUid()} pid=${Process.myPid()}；历史日志仅供回溯")
        runCatching { pioneer.initialize() }.onSuccess { adapterAvailable = true }
            .onFailure { stage(0, "阻塞：没有可用先锋 SDK；${message(it)}") }
        registerEvents()
        refreshDevices()
        dispatchProbeIntent(intent)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchProbeIntent(intent)
    }
    private fun dispatchProbeIntent(command: Intent) {
        val action = command.getStringExtra("probe_action") ?: return
        val handler = android.os.Handler(mainLooper)
        val task = object : Runnable {
            override fun run() {
                if (isDestroyed) return
                if (busy) { handler.postDelayed(this, 250); return }
                runTest {
                    record("ADB 技术验证 action=$action")
                    when (action) {
                        "bluetooth" -> { inspect(paired.first()); iosMgrConnectSpp(); iosMgrCleanup() }
                        "audio_inventory" -> AudioChannelProbe(this@ProbeActivity, ::record, cancelled).inventory()
                        "audio" -> AudioChannelProbe(this@ProbeActivity, ::record, cancelled).play(
                            command.getIntExtra("source", 300), command.getIntExtra("stream", 401),
                            command.getStringExtra("token"), command.getIntExtra("focus_stream", -1),
                            command.getIntExtra("duration_ms", 8000), command.getBooleanExtra("restore", true))
                        else -> record("未知验证动作=$action")
                    }
                }
            }
        }
        handler.postDelayed(task, 500)
    }
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.rgb(12, 17, 27))
        }
        root.addView(label("CarPlay 蓝牙协商 · 分步验证", 23f))
        status = label("普通 APP · 先查接口与证据，再推进协议", 14f)
        root.addView(status)
        val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), dp(14), 0)
        }
        val leftScroll = ScrollView(this).apply { addView(left) }
        columns.addView(leftScroll, LinearLayout.LayoutParams(0, -1, 0.38f))
        devices = Spinner(this).apply { setBackgroundColor(Color.rgb(225, 231, 239)) }
        left.addView(devices, LinearLayout.LayoutParams(-1, dp(48)))
        button(left, "刷新已配对设备") { refreshDevices() }
        val titles = listOf("1. 适配器与现有连接", "2. CarPlay 能力发现 / EIR、SDP",
            "3. 配对 / SSP 安全属性", "4. 蓝牙 iAP2 数据通道", "5. iAP2 链路与 MFi", "6. 无线授权与 Wi-Fi 参数")
        titles.forEach { title ->
            val row = label("$title\n待验证", 15f).apply { setPadding(0, dp(5), 0, dp(3)) }
            stages.add(row)
            left.addView(row)
        }
        button(left, "读取步骤 1–4 的接口证据") { runSelected { inspect(it) } }
        button(left, "验证原生候选连接入口") { runSelected { connectCandidate(it) } }
        button(left, "IOSMgr：注册 IIOSClient 回调（77）") { runTest { iosMgrCallbackToggle() } }
        button(left, "IOSMgr：发起 SPP 连接协商（65）") { runTest { iosMgrConnectSpp() } }
        button(left, "IOSMgr：断开 SPP 并注销（66/78）") { runTest { iosMgrCleanup() } }
        button(left, "读取音源 / 音频通道清单") { runTest {
            AudioChannelProbe(this, ::record, cancelled).inventory()
        } }
        button(left, "USB 蓝牙模块测试页（OTG · HCI）") {
            startActivity(Intent(this, UsbBtProbeActivity::class.java))
        }
        button(left, "检查步骤 5–6 的前置条件") { runTest {
            stage(4, "静态解码已定位字节通道链路（77 注册 → 65 请求 SPP → 87 承载数据）；未实测前不定论")
            stage(5, "待验证：无线授权由 iPhone 侧驱动 iAP2 流程；本探针不伪造授权或凭据")
            record("IOSMgr 接口已在解码层定位；实测通过后接入 Iap2LinkProbe 和 DiPlay 无线控制引擎")
        } }
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        columns.addView(right, LinearLayout.LayoutParams(0, -1, 0.62f))
        val actions = LinearLayout(this)
        right.addView(actions)
        actions.addView(Button(this).apply { text = "停止测试"; setOnClickListener {
            cancelled.set(true)
            record("请求停止；已受理的本次候选连接由测试线程清理")
        } }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(Button(this).apply { text = "导出报告"; setOnClickListener { share() } },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        output = label(report.read(), 14f).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        logScroll = ScrollView(this).apply { addView(output) }
        right.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }
    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(228, 236, 248))
    }
    private fun button(parent: LinearLayout, title: String, action: () -> Unit) {
        parent.addView(Button(this).apply {
            text = title; isAllCaps = false
            setOnClickListener { action() }; buttons.add(this)
        }, LinearLayout.LayoutParams(-1, dp(50)))
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun selected(): PairedDevice = paired.getOrNull(devices.selectedItemPosition)
        ?: error("请选择已配对设备")
    private fun runSelected(action: (PairedDevice) -> Unit) {
        // 在 UI 线程捕获选择结果，后台线程不读取 Spinner 状态。
        runCatching { selected() }.onSuccess { target -> runTest { action(target) } }
            .onFailure { record("未开始测试：${message(it)}") }
    }
    private fun refreshDevices() = runTest {
        check(adapterAvailable) { "这台设备没有可用先锋 SDK；需在先锋车机验证" }
        val found = pioneer.paired()
        runOnUiThread {
            paired = found
            devices.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                found.map { it.toString() }.ifEmpty { listOf("没有已配对设备") })
        }
        record("读取已配对设备 ${found.size} 个：${found.joinToString()}")
        record("报告为本 APK 新 UID 的结果；不继承上一 APK 的权限验证结论")
    }
    private fun runTest(action: () -> Unit) {
        if (busy) return
        busy = true; cancelled.set(false)
        buttons.forEach { it.isEnabled = false }; devices.isEnabled = false
        status.text = "测试中 · 结果会自动保存"
        worker.execute {
            try { action() }
            catch (failure: Exception) { record("测试未完成：${message(failure)}") }
            finally { runOnUiThread {
                if (!isDestroyed) {
                    busy = false
                    buttons.forEach { it.isEnabled = true }; devices.isEnabled = true
                    status.text = "本轮结束 · 请查看各步骤证据"
                }
            } }
        }
    }
    private fun inspect(device: PairedDevice) {
        record("设备=${device.name} address=${device.address} handle=${device.handle}")
        record("fingerprint=${Build.FINGERPRINT}; uid=${Process.myUid()}")
        record("标准 Android Bluetooth enabled=${BluetoothAdapter.getDefaultAdapter()?.isEnabled}")
        for (name in listOf("getState", "isBtValid", "getSppEnableApple", "isSppConnectable")) {
            record("先锋 $name=${pioneer.query(name)}")
        }
        val connection = pioneer.query("getConnectionState", device)
        val profiles = pioneer.query("getProfileSetting", device)
        stage(0, "已读取：device=$connection 配置=$profiles；不推断 iAP2")
        val binder = PioneerBinder(::record)
        check(binder.firmwareKnown()) { "固件不同；已完成 Java 查询，停止原生探测" }
        binder.query(67, device.handle)
        stage(2, "已读取普通配对状态；SSP 模式、密钥类型、加密状态仍未知")
        val supported = binder.output(79, device.handle)
        val connected = binder.output(80, device.handle)
        val setting = binder.output(81, device.handle)
        record("profile 掩码：supported=$supported connected=$connected setting=$setting")
        stage(1, "待验证：profile 掩码不等于 EIR 或 iAP2 SDP 记录")
        for (index in 0..4) binder.query(78, device.handle, index)
        for (index in 0..8) { if (cancelled.get()) break; binder.query(134, index) }
        binder.query(141)
        iosMgrSweep()
        stage(5, "待验证：依赖 iAP2 与 MFi")
        record("SDK 查询接口清单：" + device.native.javaClass.methods.filter {
            it.name.contains("pair", true) || it.name.contains("uuid", true) || it.name.contains("service", true)
        }.joinToString { it.toString() })
    }
    private fun connectCandidate(device: PairedDevice) {
        val binder = PioneerBinder(::record)
        check(binder.firmwareKnown()) { "固件不同，未调用候选入口" }
        val before = binder.output(81, device.handle)
        val state = binder.query(134, 0)
        val block = ProbePolicy.connectionBlock(true, before, state == listOf(0, 0))
        if (block != null) {
            stage(3, "阻塞：$block"); record("未发送连接请求：$block"); return
        }
        record("候选入口 133/index=0：未证实属于 CarPlay；不修改 profile 配置")
        val start = binder.startCandidate()
        if (start != listOf(0)) {
            stage(3, "失败：原生连接返回 $start；不推断 SSP 或硬件能力"); return
        }
        try {
            stage(3, "请求已受理；正在观察，尚未证明 iAP2")
            repeat(20) {
                if (cancelled.get()) return@repeat
                Thread.sleep(1000)
                binder.query(134, 0)
            }
            stage(3, "观察结束：缺少 UUID 与字节证据，尚未验证 iAP2")
        } finally {
            // 只清理本次请求的、测试前为空闲的候选通道。
            runCatching { binder.stopCandidate() }.onFailure { record("本次候选清理失败：${message(it)}") }
            record("配置回读 before=$before after=${binder.output(81, device.handle)}")
            record("连接 profile 回读=${binder.output(80, device.handle)}")
        }
    }
    private fun iosMgrSweep() {
        val ios = IosMgrProbe(::record)
        record(ios.availableDescription())
        for (code in listOf(75, 76, 67, 74, 70, 71, 72, 82)) ios.call(code, IosMgrProbe.DEV_BT)
        for (mode in 0..2) ios.call(79, mode)
        stage(3, "已读取 IOSMgr 原生状态；会话有无以 67/74 与 dumpsys IOSMgr 对照为准")
        stage(4, "静态解码已定位字节通道（77→65→87）；实测完成前不定论，不写字节、不伪造会话")
    }
    private fun iosMgrCallbackToggle() {
        check(IosMgrProbe.firmwareKnown()) { "固件指纹不同，未发送 IOSMgr 调用" }
        val ios = IosMgrProbe(::record)
        record(ios.availableDescription())
        if (!iosCallbackRegistered) {
            val result = ios.call(77, IosMgrProbe.DEV_BT)
            iosCallbackRegistered = result.status == 0
            record("回调注册：status=${result.status} registered=$iosCallbackRegistered（独立 CLIENT_ID 槽，未占用其他客户端）")
            stage(4, if (iosCallbackRegistered) "IIOSClient 回调已注册，事件将实时记录；处理完成用清理按钮注销" else "注册被拒绝或被过滤，见报告")
        } else {
            val result = ios.call(78, IosMgrProbe.DEV_BT)
            iosCallbackRegistered = result.status != 0
            record("回调注销：status=${result.status} stillRegistered=$iosCallbackRegistered")
            stage(4, if (iosCallbackRegistered) "注销未受理；进程退出后残留注册会自然失效" else "IIOSClient 回调已注销")
        }
    }
    private fun iosMgrConnectSpp() {
        check(IosMgrProbe.firmwareKnown()) { "固件指纹不同，未发送 IOSMgr 调用" }
        val ios = IosMgrProbe(::record)
        record(ios.availableDescription())
        if (!iosCallbackRegistered) {
            val reg = ios.call(77, IosMgrProbe.DEV_BT, quiet = true)
            iosCallbackRegistered = reg.status == 0
            record("预置回调观察：注册${if (iosCallbackRegistered) "成功" else "未受理"}（77 状态=${reg.status}）")
        }
        val device = ios.call(79, IosMgrProbe.DEV_BT)
        val connect = ios.call(67, IosMgrProbe.DEV_BT)
        val lastResult = ios.call(74, IosMgrProbe.DEV_BT)
        val accepted = listOf(device, connect, lastResult).all { it.status == 0 }
        val block = ProbePolicy.sppConnectBlock(true, accepted, device.value, connect.value)
        if (block != null) {
            stage(3, "阻塞：$block")
            record("未发送连接请求：$block")
            return
        }
        record("连接前基线：dev=${device.value} conn=${connect.value} 上次结果=${lastResult.value}（74 是对端保留的上次结果 bool，不参与门禁）")
        val request = ios.call(65, IosMgrProbe.DEV_BT)
        if (request.status != 0) {
            stage(3, "失败：ConnectSpp 未受理（状态=${request.status}）；不推断硬件或配对能力")
            return
        }
        iosSppRequested = true
        stage(3, "已受理 SPP 连接请求；观察 30s，比对前后状态与回调")
        record("已受理（65 状态=0）；每秒轮询 67/74/79，值变化时记录")
        var lastLine = ""
        repeat(30) { index ->
            if (!cancelled.get()) {
                Thread.sleep(1000)
                val currentConnect = ios.call(67, IosMgrProbe.DEV_BT, quiet = true)
                val currentResult = ios.call(74, IosMgrProbe.DEV_BT, quiet = true)
                val currentDevice = ios.call(79, IosMgrProbe.DEV_BT, quiet = true)
                val line = "t+${index + 1}s conn=${currentConnect.value} result=${currentResult.value} dev=${currentDevice.value}"
                if (line != lastLine) {
                    record(line)
                    lastLine = line
                }
            }
        }
        val afterConnect = ios.call(67, IosMgrProbe.DEV_BT)
        val afterResult = ios.call(74, IosMgrProbe.DEV_BT)
        val afterDevice = ios.call(79, IosMgrProbe.DEV_BT)
        record("连接后回读：dev=${afterDevice.value} conn=${afterConnect.value} 上次结果=${afterResult.value}")
        stage(4, "观察结束：SPP 是否进入 iAP2 以回调事件、67/74 值变化与 iPhone 侧表现为准")
    }
    private fun iosMgrCleanup() {
        check(IosMgrProbe.firmwareKnown()) { "固件指纹不同，未发送 IOSMgr 调用" }
        val ios = IosMgrProbe(::record)
        record(ios.availableDescription())
        if (iosSppRequested) {
            val before = ios.call(67, IosMgrProbe.DEV_BT)
            val disconnect = ios.call(66, IosMgrProbe.DEV_BT)
            iosSppRequested = false
            Thread.sleep(1500)
            val after = ios.call(67, IosMgrProbe.DEV_BT)
            record("断开 SPP：66 状态=${disconnect.status}；conn ${before.value} -> ${after.value}")
            stage(3, "已请求断开本次会话发起的 SPP（66 状态=${disconnect.status}）")
        } else {
            record("本次会话未发起过 SPP 连接，未发送 66；不误断系统会话")
        }
        if (iosCallbackRegistered) {
            val unreg = ios.call(78, IosMgrProbe.DEV_BT)
            iosCallbackRegistered = unreg.status != 0
            record("回调注销：78 状态=${unreg.status} stillRegistered=$iosCallbackRegistered")
        } else {
            record("回调未注册，无需注销")
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
    private fun registerEvents() {
        val filter = IntentFilter()
        runCatching {
            val type = Class.forName("jp.pioneer.ceam.bluetooth.BluetoothAdapter")
            for (field in type.fields.filter { it.name.startsWith("ACTION_") && it.type == String::class.java }) {
                (field.get(null) as? String)?.let { filter.addAction(it) }
            }
            if (filter.countActions() > 0) {
                registerReceiver(events, filter); receiverRegistered = true
                record("监听先锋 SDK 已声明的 ${filter.countActions()} 种状态事件；事件本身不证明加密属性")
            }
        }.onFailure { record("事件监听不可用：${message(it)}") }
    }
    private fun share() {
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.reports", report.file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "导出蓝牙协商报告"))
        }.onFailure { Toast.makeText(this, message(it), Toast.LENGTH_LONG).show() }
    }
    private fun message(failure: Throwable): String {
        val actual = if (failure is InvocationTargetException) failure.targetException else failure
        return "${actual.javaClass.simpleName}: ${actual.message}"
    }
    override fun onDestroy() {
        cancelled.set(true)
        if (receiverRegistered) unregisterReceiver(events)
        worker.shutdown()
        super.onDestroy()
    }
}
