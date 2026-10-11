package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.network.ExistingWifiManager
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import com.shilapi.xcertplay.transport.RemoteRfcommDuplexStream
import org.json.JSONObject
import java.io.File

/** Privileged debug entry point: real phone authentication through the Android A relay. */
class RemoteCarPlayProbeActivity : Activity() {
    private lateinit var status: TextView
    @Volatile private var stream: RemoteRfcommDuplexStream? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("startCarPlay", false)) {
            val mode = com.shilapi.xcertplay.orchestration.WirelessHotspotMode.valueOf(
                intent.getStringExtra("hotspotMode") ?: "EXISTING_WIFI",
            )
            if (mode == com.shilapi.xcertplay.orchestration.WirelessHotspotMode.LOCAL_ONLY_HOTSPOT ||
                (mode == com.shilapi.xcertplay.orchestration.WirelessHotspotMode.MANUAL &&
                    com.shilapi.xcertplay.orchestration.ManualHotspotValidation.error(
                        AirPlayPersistence.loadManualHotspotSsid(this).orEmpty(),
                        AirPlayPersistence.loadManualHotspotPassphrase(this).orEmpty(),
                    ) != null)) {
                startActivity(android.content.Intent(this, DiPlayActivity::class.java))
                finish()
                return
            }
            AirPlayPersistence.saveWirelessHotspotMode(this, mode)
            AirPlayPersistence.saveWirelessEnabled(this, true)
            AirPlayPersistence.saveMfiTarget(this, com.shilapi.xcertplay.orchestration.MfiTarget.LOCAL)
            startActivity(android.content.Intent(this, CarPlayHostActivity::class.java))
            finish()
            return
        }
        status = TextView(this).apply { textSize = 16f; setPadding(24, 24, 24, 24) }
        setContentView(status)
        Thread({
            var phase = "CONFIGURATION"
            try {
                val settings = JSONObject(File(filesDir, "remote-probe.json").readText())
                val ssid = AirPlayPersistence.loadExistingWifiSsid(this)
                require(ssid.isNotBlank()) { "Configure Existing Wi-Fi SSID first" }
                val directory = File(noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY).apply { mkdirs() }
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    assets.open("offline-mfi/$name").use { source ->
                        File(directory, name).outputStream().use { source.copyTo(it) }
                    }
                }
                val authentication = LocalMfiAuthenticationClient.load(directory)
                report("CONFIGURATION_READY wifiConfigured=true authenticationLoaded=true")
                phase = "REMOTE_RFCOMM"
                val remote = com.shilapi.xcertplay.transport.RemoteGatewayDiscovery.connect(this,
                    com.shilapi.xcertplay.orchestration.RemoteRfcommGateway(
                        settings.getString("gatewayBluetoothMac"), settings.getString("token")), ::report)
                stream = remote
                Iap2Session.openWireless(remote, "remote-probe", { report(it.substringBefore('\n')) }).use { session ->
                    phase = "IAP2_LINK"
                    check(session.awaitReady(15000)) { "iAP2 link not ready" }
                    report("IAP2_LINK_VERIFIED")
                    phase = "IDENTIFICATION"
                    Iap2IdentificationClient(session).identify(Iap2IdentificationConfig(
                        name = "DiPlay", modelIdentifier = "AndroidGateway", manufacturer = "DiPlay",
                        serialNumber = "DIPLAY-GATEWAY-POC", firmwareVersion = "0.2.18", hardwareVersion = "1.0",
                        wireless = Iap2WirelessIdentification(remote.gatewayBluetoothMac, ssid),
                    ), 30000, ::report)
                    report("IDENTIFICATION_VERIFIED")
                    phase = "MFI_AUTHENTICATION"
                    Iap2MfiAuthenticationClient(authentication).run(session, 30000, ::report)
                    report("MFI_AUTHENTICATION_VERIFIED bytesIn=${remote.bytesReceived} bytesOut=${remote.bytesSent}")
                }
                stream = null
                phase = "EXISTING_WIFI_NETWORK"
                ExistingWifiManager(this, ssid, AirPlayPersistence.loadExistingWifiPassphrase(this), ::report).use { network ->
                    val info = network.start(10000)
                    report("EXISTING_WIFI_READY interface=${info.interfaceName} address=${info.hostAddress?.hostAddress}")
                    report("AIRPLAY_AND_CARPLAY_NOT_YET_VERIFIED")
                }
            } catch (error: Exception) {
                report("FAILED phase=$phase error=${error.javaClass.simpleName} message=${error.message}")
            } finally {
                stream?.close(); stream = null
            }
        }, "remote-carplay-probe").start()
    }
    private fun report(message: String) {
        Log.i("RemoteCarPlayProbe", message)
        runOnUiThread { status.append(message + "\n") }
    }
    override fun onDestroy() {
        stream?.close()
        super.onDestroy()
    }
}
