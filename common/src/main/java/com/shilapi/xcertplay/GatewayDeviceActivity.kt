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
    private lateinit var status: TextView
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
        status = TextView(this).apply { textSize = 18f; setPadding(0, dp(24), 0, dp(24)) }
        content.addView(status)
        fun button(text: Int, click: () -> Unit) = Button(this).apply {
            setText(text); setOnClickListener { click() }
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        searchButton = button(if (RemoteRfcommProtocol.DEVELOPMENT_DIRECT_CONNECTION)
            R.string.gateway_check_connection else R.string.gateway_search) { search() }
        connectButton = button(R.string.gateway_connect) { connect() }.apply { isEnabled = false }
        button(R.string.gateway_forget) {
            AlertDialog.Builder(this).setTitle(R.string.gateway_forget)
                .setMessage(R.string.gateway_forget_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    RemoteGatewaySettings.forget(this)
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
                }.also { found ->
                    RemoteGatewaySettings.save(this, found.gatewayBluetoothMac, RemoteRfcommProtocol.DEVELOPMENT_TOKEN)
                }
            }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                searching = false
                searchButton.isEnabled = true
                result.onSuccess { found ->
                    endpoint = found
                    status.text = if (RemoteRfcommProtocol.DEVELOPMENT_DIRECT_CONNECTION) {
                        getString(R.string.gateway_direct_connected, found.host, found.port)
                    } else getString(R.string.gateway_found_paired, found.gatewayBluetoothMac.takeLast(5))
                    connectButton.setText(R.string.gateway_connect)
                    connectButton.isEnabled = true
                }.onFailure {
                    android.util.Log.e("GatewayDiscovery", "Proxy discovery failed", it)
                    trace("Proxy discovery failed: ${it.javaClass.simpleName}: ${it.message}")
                    status.text = if (RemoteRfcommProtocol.DEVELOPMENT_DIRECT_CONNECTION) {
                        getString(R.string.gateway_direct_failed, RemoteRfcommProtocol.DEVELOPMENT_PROXY_HOST,
                            RemoteRfcommProtocol.DEVELOPMENT_PROXY_PORT, it.message ?: it.javaClass.simpleName)
                    } else getString(R.string.gateway_not_found)
                }
            }
        }, "gateway-ui-discovery").start()
    }

    private fun connect() {
        val found = endpoint ?: return
        if (RemoteGatewaySettings.load(this) == null) {
            RemoteGatewaySettings.save(this, found.gatewayBluetoothMac, RemoteRfcommProtocol.DEVELOPMENT_TOKEN)
        }
        if (AirPlayPersistence.loadExistingWifiSsid(this).isBlank()) {
            status.setText(R.string.gateway_wifi_required)
            return
        }
        AirPlayPersistence.saveWirelessHotspotMode(this, WirelessHotspotMode.EXISTING_WIFI)
        AirPlayPersistence.saveWirelessEnabled(this, true)
        AirPlayPersistence.saveMfiTarget(this, MfiTarget.LOCAL)
        startActivity(Intent(this, CarPlayHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }
}
