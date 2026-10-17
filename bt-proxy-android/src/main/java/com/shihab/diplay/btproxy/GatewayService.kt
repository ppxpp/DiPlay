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
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Owns the relay independently of the proxy UI and reports progress to [GatewayRuntime]. */
class GatewayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var gatewayCreated = false
    private val gateway: RfcommGatewayServer by lazy {
        RfcommGatewayServer(this, filesDir) { line -> ProxyLog.append(this, line) }
            .apply { gatewayCreated = true }
    }
    private val preferences: SharedPreferences by lazy { getSharedPreferences("gateway", MODE_PRIVATE) }
    private var targetAddress: String? = null
    private var targetName = ""
    private var previousStatus: String? = null
    private var previousPorts: String? = null
    private var blocked = false
    private var foregroundReady = true
    private val retry = object : Runnable {
        override fun run() {
            ensureRunning()
            handler.postDelayed(this, 5000)
        }
    }
    private val portsTick = object : Runnable {
        override fun run() {
            publishPorts()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // A START_STICKY restart must not inherit the previous session's badge.
        blocked = false
        targetAddress = null
        targetName = ""
        publish()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.gateway_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.gateway_notification_title))
            .setContentText(getString(R.string.gateway_notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (hasConnectedDeviceBasis(this)) {
            startForeground(1, notification)
        } else {
            // Android 14 refuses a connectedDevice service until a nearby permission is granted, and
            // calling startForeground anyway throws, so report the reason and let the UI ask for it.
            foregroundReady = false
            blocked = true
            updateStatus(getString(R.string.gateway_error_permission))
            publish()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(retry)
        handler.removeCallbacks(portsTick)
        gateway.stateListener = { stage ->
            handler.post {
                // A queued event may belong to a start that has already stopped or restarted.
                if (gateway.currentStage == stage) onStage(stage)
            }
        }
        retry.run()
        portsTick.run()
        return START_STICKY
    }

    /** Relay events arrive from gateway threads, so they are posted to the main thread first. */
    private fun onStage(stage: ProxyStage) {
        val headline = when (stage) {
            ProxyStage.LISTENING -> getString(R.string.gateway_listening)
            ProxyStage.DISCOVERED -> getString(R.string.gateway_discovered)
            ProxyStage.AUTHENTICATED, ProxyStage.RFCOMM_CONNECTING -> getString(R.string.gateway_authenticated)
            ProxyStage.RELAYING -> getString(R.string.gateway_relaying)
            ProxyStage.STOPPED -> getString(R.string.gateway_stopped)
            else -> getString(R.string.gateway_idle)
        }
        blocked = false
        updateStatus(headline)
        publish()
    }

    private fun publishPorts() {
        val ports = if (gatewayCreated) gateway.endpointSummary().orEmpty() else ""
        if (previousPorts != ports) {
            previousPorts = ports
            preferences.edit().putString("ports", ports).apply()
        }
        publish()
    }

    private fun ensureRunning() {
        try {
            if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                error(getString(R.string.gateway_error_permission))
            }
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: error(getString(R.string.gateway_error_no_bluetooth))
            if (!adapter.isEnabled) error(getString(R.string.gateway_error_bluetooth_off))
            val saved = preferences.getString("iphone", null)
            val bonded = adapter.bondedDevices
            val device = bonded.firstOrNull { it.address == saved }
                ?: bonded.filter { it.name?.contains("iphone", ignoreCase = true) == true }.singleOrNull()
                ?: error(getString(R.string.gateway_error_no_target))
            if (gateway.isRunning && targetAddress == device.address) {
                publish()
                return
            }
            if (gateway.isRunning) gateway.close()
            GatewayConfiguration.ensure(this)
            gateway.start(device)
            targetAddress = device.address
            targetName = try {
                device.name ?: ""
            } catch (_: SecurityException) {
                ""
            }
            preferences.edit().putString("iphone", device.address).apply()
            updateStatus(getString(R.string.gateway_listening))
        } catch (error: Exception) {
            if (gatewayCreated && gateway.isRunning) gateway.close()
            blocked = true
            updateStatus(error.message ?: getString(R.string.gateway_idle))
        } finally {
            publish()
        }
    }

    private fun publish() {
        val created = gatewayCreated
        val session = if (created) gateway.session() else RfcommGatewayServer.Session(0, 0, 0)
        GatewayRuntime.update {
            ProxySnapshot(
                stage = if (created) gateway.currentStage else ProxyStage.IDLE,
                headline = previousStatus ?: getString(R.string.gateway_status_default),
                listeningPort = if (created) gateway.listeningPort else 0,
                discoveryPort = if (created) gateway.discoveryPort else 0,
                discoveryPeer = if (created) gateway.discoveryPeer.orEmpty() else "",
                dataPeer = if (created) gateway.dataPeer.orEmpty() else "",
                bytesFromIphone = session.bytesFromIphone,
                bytesToIphone = session.bytesToIphone,
                sessionStartedMs = session.startedMs,
                targetName = targetName,
                targetAddress = targetAddress.orEmpty(),
                serviceRunning = created && gateway.isRunning,
                blocked = blocked,
            )
        }
    }

    private fun updateStatus(status: String) {
        if (previousStatus == status) return
        previousStatus = status
        preferences.edit().putString("status", status).apply()
        ProxyLog.append(this, "phase=AUTO_GATEWAY status=$status")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (gatewayCreated) {
            gateway.stateListener = null
            gateway.close()
        }
        previousStatus = getString(R.string.gateway_stopped)
        previousPorts = ""
        preferences.edit()
            .putString("status", getString(R.string.gateway_stopped))
            .putString("ports", "")
            .apply()
        blocked = false
        publish()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gateway"

        /** The connectedDevice service type needs one already-granted nearby permission on Android 12+. */
        private fun hasConnectedDeviceBasis(context: Context): Boolean =
            Build.VERSION.SDK_INT < 31 ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            if (!hasConnectedDeviceBasis(context)) return
            val intent = Intent(context, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
