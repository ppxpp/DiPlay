package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.RemoteRfcommProtocol
import com.shilapi.xcertplay.transport.RemoteGatewayDiscovery

/** Native first-pairing and discovery entry point; it does not open RFCOMM during discovery. */
class GatewayDeviceActivity : Activity() {
    private lateinit var transportStatus: TextView
    private lateinit var status: TextView
    private lateinit var ports: TextView
    private lateinit var searchButton: Button
    private lateinit var connectButton: Button
    private var endpoint: RemoteGatewayDiscovery.Endpoint? = null
    private var searching = false
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0C111B.toInt())
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        content.addView(TextView(this).apply { setText(R.string.gateway_title); textSize = 26f })
        content.addView(TextView(this).apply { setText(R.string.gateway_description); textSize = 16f })
        transportStatus = TextView(this).apply { textSize = 16f }
        content.addView(transportStatus)
        updateTransportStatus()
        status = TextView(this).apply { textSize = 18f; setPadding(0, dp(24), 0, dp(24)) }
        content.addView(status)
        ports = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(16)) }
        content.addView(ports)
        fun button(text: Int, click: () -> Unit) = Button(this).apply {
            setText(text); setOnClickListener { click() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        button(R.string.gateway_use_local_bluetooth) {
            RemoteGatewaySettings.useLocalBluetooth(this)
            updateTransportStatus()
        }
        searchButton = button(R.string.gateway_search) { search() }
        connectButton = button(R.string.gateway_connect) { connect() }.apply { isEnabled = false }
        button(R.string.gateway_forget) {
            AlertDialog.Builder(this).setTitle(R.string.gateway_forget)
                .setMessage(R.string.gateway_forget_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    RemoteGatewaySettings.forget(this)
                    updateTransportStatus()
                    endpoint = null
                    search()
                }.show()
        }
        button(R.string.back) { finish() }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0C111B.toInt())
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(scroll)
        search()
    }

    private fun search() {
        if (searching) return
        searching = true
        endpoint = null
        searchButton.isEnabled = false
        connectButton.isEnabled = false
        status.setText(R.string.gateway_searching)
        ports.text = ""
        val paired = RemoteGatewaySettings.load(this)
        Thread({
            val discoveryReport = java.io.File(filesDir, "gateway-discovery.log")
            fun trace(message: String) {
                android.util.Log.w("GatewayDiscovery", message)
                runCatching { discoveryReport.appendText("$message\n") }
            }
            runCatching { discoveryReport.writeText("Proxy search started\n") }
            val result = runCatching {
                RemoteGatewayDiscovery.find(this, paired?.gatewayBluetoothMac) { message ->
                    trace(message)
                }
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                searching = false
                searchButton.isEnabled = true
                result.onSuccess { found ->
                    endpoint = found
                    status.text = getString(R.string.gateway_found_paired, found.gatewayBluetoothMac.takeLast(5))
                    ports.text = if (found.discoveryLocalPort > 0) {
                        getString(R.string.gateway_ports_broadcast, found.discoveryLocalPort,
                            RemoteRfcommProtocol.DISCOVERY_PORT, found.host, found.port)
                    } else {
                        getString(R.string.gateway_ports_nsd, found.host, found.port)
                    }
                    connectButton.setText(R.string.gateway_connect)
                    connectButton.isEnabled = true
                }.onFailure {
                    android.util.Log.e("GatewayDiscovery", "Proxy discovery failed", it)
                    trace("Proxy discovery failed: ${it.javaClass.simpleName}: ${it.message}")
                    status.text = getString(R.string.gateway_not_found)
                }
            }
        }, "gateway-ui-discovery").start()
    }

    private fun connect() {
        val found = endpoint ?: return
        if (AirPlayPersistence.loadExistingWifiSsid(this).isBlank()) {
            status.setText(R.string.gateway_wifi_required)
            return
        }
        RemoteGatewaySettings.save(this, found.gatewayBluetoothMac, RemoteRfcommProtocol.DEVELOPMENT_TOKEN)
        AirPlayPersistence.saveWirelessHotspotMode(this, WirelessHotspotMode.EXISTING_WIFI)
        updateTransportStatus()
        AirPlayPersistence.saveWirelessEnabled(this, true)
        AirPlayPersistence.saveMfiTarget(this, MfiTarget.LOCAL)
        connectButton.isEnabled = false
        CarPlayBackgroundSession.stop {
            runOnUiThread {
                if (!destroyed) startActivity(Intent(this, CarPlayHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
    }

    private fun updateTransportStatus() {
        transportStatus.setText(if (RemoteGatewaySettings.loadSelected(this) != null)
            R.string.gateway_transport_proxy else R.string.gateway_transport_local)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }
}
