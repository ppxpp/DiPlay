package com.shihab.diplay.btproxy

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Owns the relay independently of the development UI. */
class GatewayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val gateway by lazy { RfcommGatewayServer(this, filesDir) { line ->
        ProxyLog.append(this, line)
        when {
            "event=RFCOMM_CONNECTED" in line -> updateStatus("iPhone 链路已连接 · 正在透传")
            "event=AUTHENTICATED" in line -> updateStatus("B 已连接 · 正在连接 iPhone")
            "event=SESSION_ENDED" in line -> updateStatus("代理已启动 · 等待 B 连接")
        }
    } }
    private var targetAddress: String? = null
    private var previousStatus: String? = null
    private val retry = object : Runnable {
        override fun run() {
            ensureRunning()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("gateway", "蓝牙透传代理", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "gateway") else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Android A · Wi-Fi 蓝牙代理")
            .setContentText("自动等待 Android B 和已配对的 iPhone")
            .setContentIntent(open).setOngoing(true).build()
        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(retry)
        retry.run()
        return START_STICKY
    }

    private fun ensureRunning() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                error("等待蓝牙权限")
            }
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: error("设备不支持蓝牙")
            if (!adapter.isEnabled) error("等待开启蓝牙")
            val preferences = getSharedPreferences("gateway", MODE_PRIVATE)
            val saved = preferences.getString("iphone", null)
            val bonded = adapter.bondedDevices
            val device = bonded.firstOrNull { it.address == saved }
                ?: bonded.filter { it.name?.contains("iphone", ignoreCase = true) == true }.singleOrNull()
                ?: error("请配对 iPhone；有多台时选择目标并点击启动")
            if (gateway.isRunning && targetAddress == device.address) return
            if (gateway.isRunning) gateway.close()
            GatewayConfiguration.ensure(this)
            gateway.start(device)
            targetAddress = device.address
            preferences.edit().putString("iphone", device.address).apply()
            updateStatus("代理已启动 · 等待 B 连接")
        } catch (error: Exception) {
            if (gateway.isRunning) gateway.close()
            updateStatus(error.message ?: "启动失败")
        }
    }

    private fun updateStatus(status: String) {
        if (previousStatus == status) return
        previousStatus = status
        getSharedPreferences("gateway", MODE_PRIVATE).edit().putString("status", status).apply()
        ProxyLog.append(this, "phase=AUTO_GATEWAY status=$status")
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        gateway.close()
        getSharedPreferences("gateway", MODE_PRIVATE).edit().putString("status", "代理已停止").apply()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
