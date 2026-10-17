package com.shihab.diplay.btproxy

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale

enum class ReadinessState { READY, ACTIVE, BLOCKED, PENDING }
enum class ReadinessAction { NONE, PERMISSION, BLUETOOTH_SETTINGS, PAIR_SETTINGS, MAC_INPUT, NETWORK_SETTINGS }
enum class ReadinessId { PERMISSION, BLUETOOTH, PAIRING, MAC, NETWORK }

data class ReadinessItem(
    val id: ReadinessId,
    val state: ReadinessState,
    val title: String,
    val value: String,
    val action: ReadinessAction,
    val actionLabel: String?,
)

/**
 * Every non-View decision behind the screens: environment, target choice, gateway control, bounded
 * diagnostics and the log tail. It is process-wide because the RFCOMM probe, the system receiver and
 * the gateway pause flag must survive moving between activities.
 */
class ProxyController private constructor(private val context: Context) {

    interface Listener {
        /** Structure changed: rebuild the page. */
        fun onStructureChanged()

        /** Counter or peer-address change: repaint the live region only. */
        fun onLiveChanged(snapshot: ProxySnapshot) = Unit

        /** A log line arrived: refresh data without rebuilding, so scroll position survives. */
        fun onLogChanged() = Unit
    }

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()

    private val main = Handler(Looper.getMainLooper())
    private val preferences: SharedPreferences by lazy { context.getSharedPreferences("gateway", Context.MODE_PRIVATE) }
    private val probe = BluetoothProbe(::observeLog)

    var devices: List<BluetoothDevice> = emptyList()
        private set
    var selectedAddress: String? = null
        private set
    var snapshot = GatewayRuntime.snapshot
        private set
    var autoScroll = true
    var logFilter: String? = null
        set(value) {
            field = value
            notifyStructure()
        }

    private val outcomes = LinkedHashMap<String, String>()
    private var runningPhase: String? = null
    private class SdpQuery(val address: String) {
        lateinit var timeout: Runnable
    }
    private var sdpQuery: SdpQuery? = null
    private val sdpAddress: String? get() = sdpQuery?.address
    private var adapterState = AdapterState.UNKNOWN
    private var probePausedGateway = false

    private enum class AdapterState { UNKNOWN, ON, OFF }

    val sdpRunning: Boolean get() = sdpAddress != null

    private val snapshotListener: (ProxySnapshot) -> Unit = { next ->
        val structural = !next.structureEquals(snapshot)
        snapshot = next
        if (structural) notifyStructure() else listeners.forEach { it.onLiveChanged(next) }
    }

    private val logListener: () -> Unit = { listeners.forEach { it.onLogChanged() } }

    private fun notifyStructure() {
        listeners.forEach { it.onStructureChanged() }
    }

    /** Finish the exact attempt that produced this result; stale callbacks cannot end a new query. */
    private fun finishSdp(query: SdpQuery, result: String, details: String = "") {
        if (sdpQuery !== query) return
        main.removeCallbacks(query.timeout)
        sdpQuery = null
        if (runningPhase == "SDP") runningPhase = null
        outcomes["SDP"] = result
        appendLog("phase=SDP result=$result$details")
        notifyStructure()
    }

    private fun cancelSdp() {
        sdpQuery?.let { finishSdp(it, "CANCELLED") }
    }

    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                adapterState = when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> AdapterState.ON
                    BluetoothAdapter.STATE_OFF -> AdapterState.OFF
                    else -> return
                }
                if (adapterState == AdapterState.OFF) cancelSdp()
                notifyStructure()
                return
            }
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            if (!hasConnectPermission()) {
                cancelSdp()
                return
            }
            if (intent.action == BluetoothDevice.ACTION_UUID && device.address == sdpAddress) {
                val result = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                val uuids = result?.filterIsInstance<ParcelUuid>()?.map { it.uuid }.orEmpty()
                sdpQuery?.let { query ->
                    finishSdp(query, if (result == null) "FAILED" else "RECEIVED",
                        " targetPresent=${BluetoothProbe.IAP2_UUID in uuids} uuids=$uuids")
                }
            } else if (device.address == selectedAddress) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> appendLog("phase=ACL event=CONNECTED")
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> appendLog("phase=ACL event=DISCONNECTED")
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> appendLog("phase=PAIRING bondState=${device.bondState}")
                }
            }
            notifyStructure()
        }
    }

    private fun registerReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_UUID)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Protected system broadcasts: the screen must not be reachable from other apps.
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    fun addListener(listener: Listener) {
        listeners.add(listener)
        GatewayRuntime.addListener(snapshotListener)
        ProxyLog.addListener(logListener)
        listener.onStructureChanged()
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) {
            GatewayRuntime.removeListener(snapshotListener)
            ProxyLog.removeListener(logListener)
        }
    }

    fun onResume() = refreshEnvironment()

    /** Call from Activity.onRequestPermissionsResult. */
    fun onPermissionsResult() = refreshEnvironment()

    // ---- environment ----

    fun hasConnectPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun bluetoothEnabled(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    private fun localNetwork(): Pair<String, String>? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .map { networkInterface.name to it.hostAddress.orEmpty() }
            }
            .firstOrNull()
    }.getOrNull()

    /**
     * Resolved through the same chain the gateway uses, so the checklist never reports a blocker the
     * proxy itself can satisfy.
     */
    private fun bluetoothAddress(): String? = runCatching { GatewayConfiguration.ensure(context) }.getOrNull()

    fun refreshEnvironment() {
        snapshot = GatewayRuntime.snapshot
        val permitted = hasConnectPermission()
        devices = if (permitted) {
            try {
                adapter?.bondedDevices?.sortedBy { it.name ?: it.address }.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        if (devices.none { it.address == selectedAddress }) {
            val saved = preferences.getString("iphone", null)
            selectedAddress = devices.firstOrNull { it.address == saved }?.address
                ?: devices.firstOrNull { it.name?.contains("iphone", ignoreCase = true) == true }?.address
                ?: devices.firstOrNull()?.address
        }
        if (!permitted || sdpAddress != null && sdpAddress != selectedAddress) cancelSdp()
        adapterState = when {
            !permitted || adapter == null -> AdapterState.UNKNOWN
            bluetoothEnabled() -> AdapterState.ON
            else -> AdapterState.OFF
        }
        if (adapterState == AdapterState.OFF) cancelSdp()
        if (permitted && preferences.getBoolean("auto_start", true)) {
            runCatching { GatewayService.start(context) }
                .onFailure { appendLog("phase=AUTO_GATEWAY result=START_FAILED error=${it.javaClass.simpleName}") }
        }
        notifyStructure()
    }

    fun readiness(): List<ReadinessItem> {
        val permissionMissing = !hasConnectPermission() || !hasScanPermission()
        val paired = devices.size
        val address = bluetoothAddress()
        val network = localNetwork()
        val bluetoothReady = adapterState == AdapterState.ON
        return listOf(
            item(
                ReadinessId.PERMISSION,
                ready = !permissionMissing,
                title = context.getString(R.string.readiness_permission),
                pending = context.getString(R.string.readiness_permission_pending),
                readyValue = context.getString(R.string.readiness_permission_ready),
                action = if (permissionMissing) ReadinessAction.PERMISSION else ReadinessAction.NONE,
                actionLabel = if (permissionMissing) context.getString(R.string.action_grant) else null,
            ),
            item(
                ReadinessId.BLUETOOTH,
                ready = bluetoothReady,
                title = context.getString(R.string.readiness_bluetooth),
                pending = context.getString(
                    if (adapter == null) R.string.readiness_bluetooth_unavailable else R.string.readiness_bluetooth_pending,
                ),
                readyValue = context.getString(R.string.readiness_bluetooth_ready),
                action = if (bluetoothReady) ReadinessAction.NONE else ReadinessAction.BLUETOOTH_SETTINGS,
                actionLabel = if (bluetoothReady) null else context.getString(R.string.action_bluetooth_on),
            ),
            item(
                ReadinessId.PAIRING,
                ready = paired > 0,
                title = context.getString(R.string.readiness_pairing),
                pending = context.getString(R.string.readiness_pairing_pending),
                readyValue = context.getString(R.string.readiness_pairing_ready, paired),
                action = if (paired == 0) ReadinessAction.PAIR_SETTINGS else ReadinessAction.NONE,
                actionLabel = if (paired == 0) context.getString(R.string.action_pair) else null,
            ),
            item(
                ReadinessId.MAC,
                ready = address != null,
                title = context.getString(R.string.readiness_mac),
                pending = context.getString(R.string.readiness_mac_pending),
                readyValue = context.getString(R.string.readiness_mac_ready, address?.takeLast(5).orEmpty()),
                action = if (address == null) ReadinessAction.MAC_INPUT else ReadinessAction.NONE,
                actionLabel = if (address == null) context.getString(R.string.action_fill) else null,
            ),
            item(
                ReadinessId.NETWORK,
                ready = network != null,
                title = context.getString(R.string.readiness_wifi),
                pending = context.getString(R.string.readiness_wifi_pending),
                readyValue = network?.let { context.getString(R.string.readiness_wifi_ready, it.first, it.second) }.orEmpty(),
                action = if (network == null) ReadinessAction.NETWORK_SETTINGS else ReadinessAction.NONE,
                actionLabel = if (network == null) context.getString(R.string.action_wifi) else null,
            ),
        )
    }

    private fun item(
        id: ReadinessId,
        ready: Boolean,
        title: String,
        pending: String,
        readyValue: String,
        action: ReadinessAction,
        actionLabel: String?,
    ) = ReadinessItem(
        id = id,
        state = if (ready) ReadinessState.READY else ReadinessState.BLOCKED,
        title = title,
        value = if (ready) readyValue else pending,
        action = action,
        actionLabel = actionLabel,
    )

    fun isReady(items: List<ReadinessItem> = readiness()) = items.all { it.state == ReadinessState.READY }

    // ---- target selection ----

    fun selectDevice(address: String?) {
        if (selectedAddress == address) return
        cancelSdp()
        selectedAddress = address
        notifyStructure()
    }

    fun deviceLabel(device: BluetoothDevice): String = try {
        device.name ?: "unknown"
    } catch (_: SecurityException) {
        "unknown"
    }

    private fun selectedDevice() = devices.firstOrNull { it.address == selectedAddress }

    // ---- gateway control ----

    /** Matches the old start button: it needs a selected device, nothing more. */
    fun startGateway() {
        cancelSdp()
        val device = selectedDevice()
        if (device == null) {
            cancelSdp()
            appendLog("phase=PAIRING result=NO_DEVICE 请先在系统设置中配对并刷新")
            notifyStructure()
            return
        }
        probe.disconnect()
        probePausedGateway = false
        preferences.edit()
            .putString("iphone", device.address)
            .putBoolean("auto_start", true)
            .apply()
        runCatching { GatewayService.start(context) }
            .onFailure { appendLog("phase=GATEWAY result=START_FAILED message=${it.message}") }
        notifyStructure()
    }

    /** Restart after a diagnostic run; starting restores auto-start, as before. */
    fun resumeGateway() {
        startGateway()
    }

    fun stopGateway() {
        stopGatewayForProbe()
        notifyStructure()
    }

    /** Pausing the proxy is required before any probe: both take the same RFCOMM channel. */
    private fun stopGatewayForProbe() {
        preferences.edit().putBoolean("auto_start", false).apply()
        context.stopService(Intent(context, GatewayService::class.java))
        probePausedGateway = true
        snapshot = GatewayRuntime.snapshot
        notifyStructure()
    }

    val gatewayPausedForDiagnostics: Boolean
        get() = probePausedGateway && !GatewayRuntime.snapshot.serviceRunning

    // ---- diagnostics ----

    fun outcomeOf(phase: String) = outcomes[phase]

    fun isRunning(phase: String) = runningPhase == phase

    fun querySdp() = withSelectedDevice { device ->
        if (sdpRunning) {
            appendLog("phase=SDP result=BUSY 请等待当前查询完成")
            return@withSelectedDevice
        }
        val query = SdpQuery(device.address)
        query.timeout = Runnable {
            finishSdp(query, "TIMEOUT", " (SDP 结果不能单独判定 RFCOMM 可用性)")
        }
        sdpQuery = query
        runningPhase = "SDP"
        appendLog("phase=SDP event=QUERY_STARTED")
        try {
            if (!device.fetchUuidsWithSdp()) {
                finishSdp(query, "REQUEST_REJECTED")
            } else if (sdpQuery === query) {
                main.postDelayed(query.timeout, 12000)
            }
        } catch (error: Exception) {
            finishSdp(query, "ERROR", " ${error.javaClass.simpleName}: ${error.message}")
        }
        notifyStructure()
    }

    fun connectRfcomm() = probeSelectedDevice(handshake = false)

    fun handshakeIap2() = probeSelectedDevice(handshake = true)

    fun disconnectProbe() {
        cancelSdp()
        probe.disconnect()
        notifyStructure()
    }

    private fun probeSelectedDevice(handshake: Boolean) = withSelectedDevice { device ->
        cancelSdp()
        if (hasScanPermission()) {
            runCatching { adapter?.cancelDiscovery() }
        }
        appendLog(
            "phase=PAIRING bondState=${device.bondState} device=${deviceLabel(device)} " +
                "address=**:**:**:${device.address.takeLast(8)}",
        )
        stopGatewayForProbe()
        runningPhase = if (handshake) "IAP2" else "RFCOMM"
        probe.connect(device, handshake = handshake)
        notifyStructure()
    }

    private fun withSelectedDevice(action: (BluetoothDevice) -> Unit) {
        if (!hasConnectPermission()) {
            cancelSdp()
            appendLog("phase=PERMISSION result=MISSING 请先申请蓝牙权限")
            notifyStructure()
            return
        }
        val device = selectedDevice()
        if (device == null) {
            cancelSdp()
            appendLog("phase=PAIRING result=NO_DEVICE 请先在系统设置中配对并刷新")
            notifyStructure()
            return
        }
        try {
            action(device)
        } catch (error: Exception) {
            cancelSdp()
            runningPhase = null
            appendLog("phase=PROBE result=ERROR ${error.javaClass.simpleName}: ${error.message}")
            notifyStructure()
        }
    }

    /** Terminal results feed the inline diagnostics summary, then the line joins the shared log. */
    private fun observeLog(line: String) {
        val phase = KEY_PHASE.find(line)?.groupValues?.get(1)
        val result = KEY_RESULT.find(line)?.groupValues?.get(1)
        if (phase != null && result != null && result !in IN_FLIGHT_RESULTS) {
            outcomes[phase] = summarize(line, result)
            if (runningPhase == phase) runningPhase = null
        }
        appendLog(line)
    }

    private fun summarize(line: String, result: String): String {
        val elapsed = KEY_ELAPSED.find(line)?.groupValues?.get(1)
        val received = KEY_RECEIVED.find(line)?.groupValues?.get(1)?.toLongOrNull()
        return listOfNotNull(
            result,
            elapsed?.let { "${it}ms" },
            received?.let { humanBytes(it) },
        ).joinToString(" · ")
    }

    // ---- log ----

    fun logLines() = ProxyLog.snapshot(context)

    fun filteredLogLines(): List<String> {
        val lines = logLines()
        val filter = logFilter ?: return lines
        return lines.filter { it.contains("phase=$filter") }
    }

    fun copyText(text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("DiPlay proxy", text))
        toast(R.string.log_copied)
    }

    fun copyLog() = copyText(filteredLogLines().joinToString("\n"))

    fun clearLog() {
        ProxyLog.clear(context)
        toast(R.string.log_cleared)
    }

    private fun toast(message: Int) {
        android.widget.Toast.makeText(context, context.getString(message), android.widget.Toast.LENGTH_SHORT).show()
    }

    fun appendLog(message: String) {
        ProxyLog.append(context, message)
    }

    // ---- pairing ----

    fun pairingCode(): String? = runCatching {
        org.json.JSONObject(GatewayConfiguration.pairingInformation(context)).getString("token")
    }.getOrNull()

    fun pairingAddress(): String? = runCatching { bluetoothAddress() }.getOrNull()

    fun saveBluetoothAddress(address: String): Boolean {
        val trimmed = address.trim()
        if (!GatewayConfiguration.validAddress(trimmed)) return false
        File(context.filesDir, "gateway-bluetooth-mac.txt").writeText(trimmed)
        refreshEnvironment()
        return true
    }

    fun requestBluetoothPermissions(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        activity.requestPermissions(
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
            PERMISSION_REQUEST,
        )
    }

    companion object {
        const val PERMISSION_REQUEST = 41

        private var instance: ProxyController? = null

        /** One instance per process: it owns the probe, the receiver and the gateway pause flag. */
        fun get(context: Context): ProxyController = synchronized(this) {
            instance ?: ProxyController(context.applicationContext).also {
                it.registerReceiver()
                instance = it
            }
        }

        private val KEY_PHASE = Regex("phase=(\\w+)")
        private val KEY_RESULT = Regex("result=(\\w+)")
        private val KEY_ELAPSED = Regex("elapsedMs=(\\d+)")
        private val KEY_RECEIVED = Regex("bytes(?:In|FromIphone)=(\\d+)")

        // RFCOMM CONNECTED is a milestone, not the end of an attempt.
        private val IN_FLIGHT_RESULTS = setOf("CONNECTED")

        fun humanBytes(value: Long): String = when {
            value < 1024 -> "$value B"
            value < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", value / 1024.0)
            else -> String.format(Locale.US, "%.1f MB", value / (1024.0 * 1024.0))
        }

        fun duration(startedMs: Long): String {
            if (startedMs <= 0L) return "--:--"
            val seconds = ((SystemClock.elapsedRealtime() - startedMs) / 1000).coerceAtLeast(0)
            return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
        }
    }
}
