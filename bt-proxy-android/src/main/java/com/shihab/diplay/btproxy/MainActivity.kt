package com.shihab.diplay.btproxy

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var permissionButton: Button
    private lateinit var devicePicker: Spinner
    private lateinit var logView: TextView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val probe = BluetoothProbe(::appendLog)
    private val gateway by lazy { RfcommGatewayServer(this, filesDir, ::appendLog) }
    private var devices: List<BluetoothDevice> = emptyList()
    private var destroyed = false
    private var sdpAddress: String? = null
    private val sdpTimeout = Runnable {
        if (sdpAddress != null) {
            appendLog("phase=SDP result=TIMEOUT (SDP 结果不能单独判定 RFCOMM 可用性)")
            sdpAddress = null
        }
    }
    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (!hasBluetoothPermission()) return
            if (intent.action == BluetoothDevice.ACTION_UUID && device.address == sdpAddress) {
                mainHandler.removeCallbacks(sdpTimeout)
                sdpAddress = null
                val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    ?.filterIsInstance<ParcelUuid>()?.map { it.uuid }.orEmpty()
                appendLog("phase=SDP result=RECEIVED targetPresent=${BluetoothProbe.IAP2_UUID in uuids} uuids=$uuids")
            } else if (device.address == devices.getOrNull(devicePicker.selectedItemPosition)?.address) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> appendLog("phase=ACL event=CONNECTED")
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> appendLog("phase=ACL event=DISCONNECTED")
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> appendLog("phase=PAIRING bondState=${device.bondState}")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun addText(resource: Int, size: Float = 16f) {
            content.addView(TextView(this).apply {
                setText(resource)
                textSize = size
            })
        }
        addText(R.string.title, 24f)
        addText(R.string.description)
        addText(R.string.scope)
        statusView = TextView(this).apply { textSize = 16f }
        content.addView(statusView)
        permissionButton = Button(this).apply {
            setText(R.string.grant_permission)
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 1)
                }
            }
        }
        content.addView(permissionButton)
        content.addView(Button(this).apply {
            setText(R.string.open_bluetooth_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        })
        content.addView(Button(this).apply {
            setText(R.string.refresh)
            setOnClickListener { refreshEnvironment() }
        })

        devicePicker = Spinner(this)
        content.addView(devicePicker)
        fun addButton(resource: Int, action: () -> Unit) {
            content.addView(Button(this).apply {
                setText(resource)
                setOnClickListener { action() }
            })
        }
        addButton(R.string.query_sdp) {
            withSelectedDevice { device ->
                if (sdpAddress != null) {
                    appendLog("phase=SDP result=BUSY 请等待当前查询完成")
                } else {
                    sdpAddress = device.address
                    appendLog("phase=SDP event=QUERY_STARTED")
                    if (device.fetchUuidsWithSdp()) {
                        mainHandler.postDelayed(sdpTimeout, 12000)
                    } else {
                        sdpAddress = null
                        appendLog("phase=SDP result=REQUEST_REJECTED")
                    }
                }
            }
        }
        addButton(R.string.connect_rfcomm) {
            withSelectedDevice { device ->
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    adapter?.cancelDiscovery()
                }
                appendLog("phase=PAIRING bondState=${device.bondState} device=${device.name ?: "unknown"} " +
                    "address=**:**:**:${device.address.takeLast(8)}")
                probe.connect(device)
            }
        }
        addButton(R.string.disconnect) { probe.disconnect() }
        addButton(R.string.handshake_iap2) {
            withSelectedDevice { device ->
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
                    adapter?.cancelDiscovery()
                }
                probe.connect(device, handshake = true)
            }
        }
        addButton(R.string.copy_log) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("Bluetooth probe", logView.text))
        }
        addButton(R.string.start_gateway) {
            withSelectedDevice { device ->
                probe.disconnect()
                runCatching { gateway.start(device) }
                    .onSuccess { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    .onFailure { appendLog("phase=GATEWAY result=START_FAILED message=${it.message}") }
            }
        }
        addButton(R.string.stop_gateway) {
            gateway.close()
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
        }
        content.addView(logView)
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        appendLog("phase=APP event=STARTED uuid=${BluetoothProbe.IAP2_UUID}")

        val scroll = ScrollView(this).apply { addView(content) }
        // Keep controls clear of system bars with target SDK edge-to-edge enforcement.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        refreshEnvironment()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshEnvironment()
    }

    private fun refreshEnvironment() {
        val hasPermission = hasBluetoothPermission()
        val hasScan = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        permissionButton.visibility = if (hasPermission && hasScan) View.GONE else View.VISIBLE
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val bluetoothState = when {
            adapter == null -> R.string.bluetooth_unavailable
            !hasPermission -> R.string.bluetooth_permission_missing
            else -> try {
                if (adapter.isEnabled) R.string.bluetooth_on else R.string.bluetooth_off
            } catch (_: SecurityException) {
                R.string.bluetooth_permission_missing
            }
        }
        statusView.text = getString(R.string.environment, Build.MANUFACTURER, Build.MODEL,
            Build.VERSION.RELEASE, Build.VERSION.SDK_INT, getString(bluetoothState))
        val previousAddress = devices.getOrNull(devicePicker.selectedItemPosition)?.address
        devices = if (hasPermission) {
            try { adapter?.bondedDevices?.sortedBy { it.name ?: it.address }.orEmpty() }
            catch (_: SecurityException) { emptyList() }
        } else emptyList()
        val labels = devices.map { "${it.name ?: "unknown"} · ${it.address.takeLast(8)}" }
            .ifEmpty { listOf(getString(R.string.no_devices)) }
        devicePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selection = devices.indexOfFirst { it.address == previousAddress }.takeIf { it >= 0 }
            ?: devices.indexOfFirst { it.name?.contains("iphone", ignoreCase = true) == true }.coerceAtLeast(0)
        devicePicker.setSelection(selection)
    }

    private fun hasBluetoothPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun withSelectedDevice(action: (BluetoothDevice) -> Unit) {
        if (!hasBluetoothPermission()) {
            appendLog("phase=PERMISSION result=MISSING 请先申请蓝牙权限")
            return
        }
        val device = devices.getOrNull(devicePicker.selectedItemPosition)
        if (device == null) {
            appendLog("phase=PAIRING result=NO_DEVICE 请先在系统设置中配对并刷新")
            return
        }
        try { action(device) }
        catch (error: Exception) {
            sdpAddress = null
            appendLog("phase=PROBE result=ERROR ${error.javaClass.simpleName}: ${error.message}")
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$timestamp $message"
        Log.i("BtProxyProbe", line)
        mainHandler.post {
            if (destroyed) return@post
            val text = (logView.text.toString().lineSequence().takeLastLines(199) + line).joinToString("\n")
            logView.text = text
            runCatching { File(filesDir, "probe-log.txt").writeText(text + "\n") }
        }
    }

    override fun onDestroy() {
        destroyed = true
        probe.close()
        gateway.close()
        mainHandler.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver)
        super.onDestroy()
    }
}

private fun Sequence<String>.takeLastLines(count: Int): List<String> = toList().takeLast(count)
