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
        button(left, "检查步骤 5–6 的前置条件") { runTest {
            stage(4, "阻塞：尚无先锋字节收发通道，不能启动 iAP2 握手")
            stage(5, "待验证：依赖 iAP2 与 MFi，不发送 Wi-Fi 参数")
            record("下一接口：串口回调注册、原始字节接收、IMemory 写入；确认后接入 Iap2LinkProbe 和 DiPlay 无线控制引擎")
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
        val ios = PioneerBinder.service("IOSMgr")
        record("IOSMgr 存在=${ios != null} descriptor=${ios?.interfaceDescriptor}；存在不代表已建立会话")
        stage(3, "已读取候选串口状态；尚未证明 UUID、回调与字节通道")
        stage(4, "阻塞：尚无先锋字节收发通道")
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
