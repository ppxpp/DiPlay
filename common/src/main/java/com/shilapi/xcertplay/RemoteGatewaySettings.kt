package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import com.shilapi.xcertplay.orchestration.RemoteRfcommGateway
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import org.json.JSONObject

/** A saved identity is not a transport selection. Only an explicit proxy connect enables it. */
object RemoteGatewaySettings {
    private fun file(context: Context) = File(context.noBackupFilesDir, "remote-gateway.json")

    @Synchronized fun load(context: Context): RemoteRfcommGateway? = runCatching {
        val stored = file(context)
        if (stored.isFile) {
            val json = JSONObject(stored.readText())
            if (json.optString("token") != com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_TOKEN) {
                json.put("token", com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_TOKEN)
                write(context, json)
            }
            return@runCatching decode(json)
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return@runCatching null
        val legacy = File(context.filesDir, "remote-probe.json")
        if (!legacy.isFile) return@runCatching null
        val json = JSONObject(legacy.readText()).put("token", com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_TOKEN)
        val gateway = decode(json)
        write(context, json)
        legacy.delete()
        gateway
    }.getOrNull()

    /** Proxy currently supports the existing-Wi-Fi deployment only; P2P always uses local Bluetooth. */
    @Synchronized fun loadSelected(context: Context, mode: WirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(context)): RemoteRfcommGateway? {
        if (mode != WirelessHotspotMode.EXISTING_WIFI) return null
        val gateway = load(context) ?: return null
        // Old saved/discovered identities are intentionally not auto-enabled on upgrade.
        return gateway.takeIf { runCatching { JSONObject(file(context).readText()).optBoolean("enabled", false) }.getOrDefault(false) }
    }

    @Synchronized fun useLocalBluetooth(context: Context) {
        val stored = file(context)
        if (stored.isFile) write(context, JSONObject(stored.readText()).put("enabled", false))
    }

    /** Called only when the driver chooses Connect through proxy. */
    @Synchronized fun save(context: Context, mac: String, token: String) {
        val json = JSONObject().put("gatewayBluetoothMac", mac).put("token", token).put("enabled", true)
        decode(json)
        write(context, json)
    }

    @Synchronized fun forget(context: Context) {
        AtomicFile(file(context)).delete()
        File(context.filesDir, "remote-probe.json").delete()
    }

    private fun write(context: Context, json: JSONObject) {
        val atomic = AtomicFile(file(context))
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun decode(json: JSONObject) = RemoteRfcommGateway(
        json.getString("gatewayBluetoothMac"), com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_TOKEN,
        json.optInt("observedHotspotChannel", 0).takeIf { it > 0 },
        json.optString("observedHotspotAddress").takeIf { it.isNotBlank() },
    )
}
