package com.shilapi.xcertplay.pioneer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Separate diagnostic page avoids edits to upstream settings layout and translation machinery. */
class PioneerDebugActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            status.text = "${PioneerDebugService.addresses().joinToString("\n")}\n端口：${PioneerDebugRuntime.port}（${PioneerDebugRuntime.listenerState}）\n" +
                "TCP 调试无需口令\nUSB：${PioneerUsbPolicy.mode(this@PioneerDebugActivity)} / 生效=${PioneerUsbPolicy.enabled(this@PioneerDebugActivity)}\n" +
                "VPN 已授权：${runCatching { android.net.VpnService.prepare(this@PioneerDebugActivity) == null }.getOrDefault(false)}\n" +
                "${PioneerDebugRuntime.lastCommand}\n${PioneerDebugRuntime.snapshot().toString(2)}"
            handler.postDelayed(this, 1_000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 20, 24, 20) }
        status = TextView(this).apply { textSize = 17f; setTextIsSelectable(true) }
        column.addView(TextView(this).apply { text = "DiPlay 车机调试（有线验证）"; textSize = 22f })
        column.addView(status)
        fun button(label: String, action: () -> Unit) { column.addView(Button(this).apply { text = label; setOnClickListener { runCatching(action).onFailure {
            Toast.makeText(this@PioneerDebugActivity, it.message, Toast.LENGTH_LONG).show()
        } } }) }
        button("打开 DiPlay 主页面") {
            val launch = packageManager.getLaunchIntentForPackage(packageName) ?: error("没有 DiPlay 启动入口")
            // Explicit original activity; this diagnostic launcher must not recursively select itself.
            launch.setClassName(packageName, "com.shilapi.xcertplay.DiPlayActivity")
            startActivity(launch)
        }
        button("启动 TCP 调试") { PioneerDebugRuntime.start(this) }
        button("启动有线连接") { PioneerDebugRuntime.request(this, "start-wired") }
        button("重试 VPN 授权（手动 ADB 授权后使用）") { PioneerDebugRuntime.request(this, "retry-vpn") }
        button("停止 CarPlay，保留 TCP 调试") { PioneerDebugRuntime.request(this, "stop") }
        button("打开系统 VPN 设置") { startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        button("USB 适配：自动识别") { PioneerUsbPolicy.setMode(this, "AUTO") }
        button("USB 适配：启用（下次连接生效）") { PioneerUsbPolicy.setMode(this, "ON") }
        button("USB 适配：关闭（原始路径，下次连接生效）") { PioneerUsbPolicy.setMode(this, "OFF") }
        button("关闭 TCP 调试") { stopService(Intent(this, PioneerDebugService::class.java)) }
        column.addView(TextView(this).apply { text = "不要同时运行 usbcheck 与 DiPlay。有线验证前先关闭 usbcheck。更新前停止 CarPlay。使用实际显示端口；调试占用的端口可能与无线会话冲突，可先关闭调试。" })
        setContentView(ScrollView(this).apply { addView(column) })
        PioneerDebugRuntime.start(this)
        install(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); install(intent) }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    private fun install(intent: Intent?) {
        val path = intent?.getStringExtra("install_path") ?: return
        intent.removeExtra("install_path")
        val file = File(path)
        if (!file.exists() || file.parentFile?.canonicalPath != File(cacheDir, "pioneer-update").canonicalPath) return
        runCatching {
            PioneerDebugUpdateGuard.install(file) {
                val uri = FileProvider.getUriForFile(this, "$packageName.pioneer-updates", file)
                val installer = Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .apply { clipData = android.content.ClipData.newRawUri("APK update", uri) }
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                packageManager.queryIntentActivities(installer, 0).forEach { target ->
                    grantUriPermission(target.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(installer)
            }
        }.onFailure {
            PioneerDebugRuntime.log(this, "APK installer unavailable: ${it.message}")
            Toast.makeText(this, "无法打开安装器：${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}

class PioneerUpdateProvider : FileProvider()
