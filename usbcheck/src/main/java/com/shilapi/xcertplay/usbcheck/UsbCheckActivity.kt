package com.shilapi.xcertplay.usbcheck

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.FileProvider
import java.io.File
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.delay

private enum class Page { MAIN, DEVICES, STEPS, LOG }

/**
 * 8 寸 1280x720 横屏诊断界面：主页放状态与操作，设备列表/步骤详情/日志为独立二级页面。
 * 每个二级页面都有“← 返回”按钮，且内容超出屏幕时可滚动。
 */
class UsbCheckActivity : ComponentActivity() {

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (android.net.VpnService.prepare(this) == null) startService(Intent(this, VpnDemoService::class.java))
        else VpnDemoService.save(this, org.json.JSONObject().put("state", "CONSENT_DENIED"))
    }

    private fun startVpnDemo() {
        runCatching {
            val consent = android.net.VpnService.prepare(this)
            if (consent == null) startService(Intent(this, VpnDemoService::class.java))
            else {
                VpnDemoService.save(this, org.json.JSONObject().put("state", "WAITING_CONSENT"))
                vpnConsent.launch(consent)
            }
        }.onFailure {
            VpnDemoService.save(this, org.json.JSONObject().put("state", "CONSENT_UNAVAILABLE").put("consentLaunchError", it.toString()))
            // Measure the ordinary-app establish result without any privileged authorization.
            runCatching { startService(Intent(this, VpnDemoService::class.java)) }.onFailure { error ->
                VpnDemoService.save(this, org.json.JSONObject().put("state", "FAIL").put("error", error.toString()))
            }
        }
    }

    private fun openVpnSettings() {
        val attempts = listOf(android.provider.Settings.ACTION_VPN_SETTINGS, android.provider.Settings.ACTION_SETTINGS)
        for (action in attempts) {
            val opened = runCatching {
                startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (::engine.isInitialized) engine.appendDiagnostic("VPN_SETTINGS_OPENED action=$action newTask=true")
            }
            if (opened.isSuccess) {
                if (action != android.provider.Settings.ACTION_VPN_SETTINGS)
                    Toast.makeText(this, "VPN 设置入口不可用，已打开系统设置", Toast.LENGTH_LONG).show()
                return
            }
            if (::engine.isInitialized) engine.appendDiagnostic("VPN_SETTINGS_FAILED action=$action error=${opened.exceptionOrNull()}")
        }
        Toast.makeText(this, "车机未提供可打开的系统设置入口", Toast.LENGTH_LONG).show()
    }

    private lateinit var engine: UsbCheckEngine
    private var debugService by mutableStateOf<DebugService?>(null)
    private var debugText by mutableStateOf("TCP 调试服务正在启动…")
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as DebugService.LocalBinder).service
            debugService = service
            engine = service.engine
            engine.onUpdate = { runOnUiThread { ui = engine.state } }
            ui = engine.state
            engine.refreshDevices()
            handleLaunchIntent(intent)
        }
        override fun onServiceDisconnected(name: ComponentName?) { debugService = null }
    }
    private var ui by mutableStateOf<UsbCheckEngine.State?>(null)
    private var page by mutableStateOf(Page.MAIN)

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (::engine.isInitialized) usbDevice(intent)?.let { engine.noteAttach(describe(it)) }
        }
    }
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (::engine.isInitialized) usbDevice(intent)?.let { engine.noteDetach(describe(it)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val serviceIntent = Intent(this, DebugService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bound = bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        handleInstallIntent(intent)

        ContextCompat.registerReceiver(
            this, attachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            LaunchedEffect(Unit) {
                while (true) {
                    if (::engine.isInitialized) engine.refreshDevices()
                    debugService?.let { service ->
                        debugText = "TCP ${service.addresses().joinToString(" / ")} :${service.port}  口令 ${service.token}  ${service.listenerMessage}"
                    }
                    delay(1_000)
                }
            }
            BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state = ui
                    if (state == null) { Text("正在启动 USB 调试服务…"); return@Surface }
                    when (page) {
                        Page.MAIN -> MainScreen(
                            state = state,
                            onStart = { engine.start() },
                            onUsbOnly = { engine.start(UsbCheckEngine.Mode.USB_ONLY) },
                            onDetach = { engine.start(UsbCheckEngine.Mode.DETACH_NEGOTIATE) },
                            onNative = { engine.start(UsbCheckEngine.Mode.NATIVE_NEGOTIATE) },
                            onMfi = { engine.start(UsbCheckEngine.Mode.MFI_AUTH) },
                            onVpn = ::startVpnDemo,
                            onVpnSettings = ::openVpnSettings,
                            onStop = { engine.stop() },
                            debugText = debugText,
                            onCloseDebug = {
                                engine.stop()
                                stopService(Intent(this, DebugService::class.java))
                                finish()
                            },
                            onCopy = ::copyReport,
                            onSave = ::saveReportFile,
                            onOpen = { page = it },
                        )
                        Page.DEVICES -> SubPage("系统可见 USB 设备", onBack = { page = Page.MAIN }) {
                            DeviceList(state)
                        }
                        Page.STEPS -> SubPage("检查步骤详情", onBack = { page = Page.MAIN }) {
                            StepList(state)
                        }
                        Page.LOG -> SubPage(
                            title = "日志（${state.log.size} 行）",
                            onBack = { page = Page.MAIN },
                            actions = {
                                OutlinedButton(onClick = { engine.clearLog() }) { Text("清空日志") }
                            },
                        ) {
                            LogList(state)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::engine.isInitialized) handleLaunchIntent(intent)
        handleInstallIntent(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(attachReceiver) }
        runCatching { unregisterReceiver(detachReceiver) }
        if (::engine.isInitialized) engine.onUpdate = null
        if (bound) unbindService(connection)
        super.onDestroy()
    }

    private fun handleInstallIntent(intent: Intent?) {
        val path = intent?.getStringExtra("install_path") ?: return
        intent.removeExtra("install_path")
        val file = File(path)
        if (!file.exists() || file.parentFile?.canonicalPath != cacheDir.canonicalPath) return
        runCatching {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure {
            Toast.makeText(this, "无法打开系统安装器: ${it.message}", Toast.LENGTH_LONG).show()
            debugService?.engine?.appendDiagnostic("APK 安装器不可用: ${it.message}；需恢复 USB ADB 安装")
        }
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("vpn_demo", false) == true) {
            intent.removeExtra("vpn_demo")
            startVpnDemo()
        }
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        usbDevice(intent)?.let { engine.noteAttach("应用由 USB 插入事件启动：${describe(it)}") }
    }

    private fun usbDevice(intent: Intent): UsbDevice? =
        IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

    private fun describe(device: UsbDevice): String =
        "VID=0x%04x PID=0x%04x %s".format(device.vendorId, device.productId, device.deviceName)

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DiPlay USB 检查报告", engine.report()))
        Toast.makeText(this, "报告已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun saveReportFile() {
        val path = engine.saveReportFile()
        val message = if (path.isEmpty()) "报告保存失败，详见日志" else "报告已保存:\n$path"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

private val PASS_COLOR = Color(0xFF4CAF50)
private val FAIL_COLOR = Color(0xFFEF5350)
private val RUNNING_COLOR = Color(0xFFFFC107)
private val DIM_COLOR = Color(0xFF9E9E9E)

@Composable
private fun MainScreen(
    state: UsbCheckEngine.State,
    onStart: () -> Unit,
    onUsbOnly: () -> Unit,
    onDetach: () -> Unit,
    onNative: () -> Unit,
    onMfi: () -> Unit,
    onVpn: () -> Unit,
    onVpnSettings: () -> Unit,
    onStop: () -> Unit,
    debugText: String,
    onCloseDebug: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onOpen: (Page) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DiPlay USB 链路检查", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text("（分阶段验证 USB / iAP2 / MFi）", style = MaterialTheme.typography.bodySmall, color = DIM_COLOR)
        }
        Text(debugText, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onVpn, enabled = !state.running) { Text("验证 VPN / TUN") }
            OutlinedButton(onClick = onVpnSettings) { Text("打开 VPN 系统设置") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onUsbOnly, enabled = !state.running) { Text("只查 USB") }
            OutlinedButton(onClick = onDetach, enabled = !state.running) { Text("预解绑对照实验") }
            OutlinedButton(onClick = onNative, enabled = !state.running) { Text("Native errno 实验") }
            OutlinedButton(onClick = onStop, enabled = state.running) { Text("停止检查") }
            OutlinedButton(onClick = onCloseDebug) { Text("退出并关闭调试") }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverallStatus(state)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onCopy) { Text("复制报告") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onSave) { Text("保存报告") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onMfi, enabled = !state.running) { Text("验证 MFi") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onStart, enabled = !state.running) {
                Text(if (state.running) "检查中…" else "验证协商入口")
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp)) {
                Text("检查步骤概览", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                state.steps.forEach { StepRow(it, compact = true) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row {
            OutlinedButton(onClick = { onOpen(Page.DEVICES) }, modifier = Modifier.weight(1f)) {
                Text("USB 设备（${state.devices.size}）")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onOpen(Page.STEPS) }, modifier = Modifier.weight(1f)) {
                Text("步骤详情")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onOpen(Page.LOG) }, modifier = Modifier.weight(1f)) {
                Text("日志（${state.log.size} 行）")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "先暂停 DiPlay。插上并解锁 iPhone 后验证协商入口，允许“信任”。成功标志为 IdentificationAccepted，" +
                "协商入口模式随后停止；“验证 MFi”额外完成认证与 NCM 探测，不启动 VPN。通过同一局域网 TCP 导出日志。",
            style = MaterialTheme.typography.bodySmall,
            color = DIM_COLOR,
        )
    }
}

/** 二级页面骨架：顶部“← 返回”+ 标题 + 可选操作按钮，内容区域由各页面自行滚动。 */
@Composable
private fun SubPage(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            actions()
        }
        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(10.dp)) { content() }
        }
    }
}

@Composable
private fun OverallStatus(state: UsbCheckEngine.State) {
    val (text, color) = when {
        state.running -> "检查中" to RUNNING_COLOR
        state.steps.any { it.status == UsbCheckEngine.Status.FAIL } -> "存在问题" to FAIL_COLOR
        state.steps.any { it.status == UsbCheckEngine.Status.PASS } -> "全部通过" to PASS_COLOR
        else -> "待检查" to DIM_COLOR
    }
    Text(text, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun DeviceList(state: UsbCheckEngine.State) {
    if (state.devices.isEmpty()) {
        Text(
            "系统层没有枚举出任何 USB 设备。\n检查：数据线、USB 口供电、换接口重试。",
            style = MaterialTheme.typography.bodySmall,
            color = FAIL_COLOR,
        )
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        state.devices.forEach { line ->
            Text(
                line.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (line.apple) PASS_COLOR else Color(0xFFB0BEC5),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun StepList(state: UsbCheckEngine.State) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        state.steps.forEach { StepRow(it, compact = false) }
    }
}

@Composable
private fun StepRow(step: UsbCheckEngine.Step, compact: Boolean) {
    val (mark, color) = when (step.status) {
        UsbCheckEngine.Status.PENDING -> "·" to DIM_COLOR
        UsbCheckEngine.Status.RUNNING -> "▶" to RUNNING_COLOR
        UsbCheckEngine.Status.PASS -> "✓" to PASS_COLOR
        UsbCheckEngine.Status.FAIL -> "✗" to FAIL_COLOR
        UsbCheckEngine.Status.SKIP -> "—" to DIM_COLOR
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(mark, color = color, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            Text(step.title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            Text("[${step.status.label}]", style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (!compact && step.detail.isNotBlank()) {
            Text(
                step.detail,
                style = MaterialTheme.typography.bodySmall,
                color = DIM_COLOR,
                modifier = Modifier.padding(start = 20.dp, top = 1.dp),
            )
        }
    }
}

@Composable
private fun LogList(state: UsbCheckEngine.State) {
    if (state.log.isEmpty()) {
        Text("暂无日志。", style = MaterialTheme.typography.bodySmall, color = DIM_COLOR)
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) { listState.scrollToItem(state.log.size - 1) }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(state.log) { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFFCFD8DC),
            )
        }
    }
}
