package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import com.shilapi.xcertplay.orchestration.RemoteRfcommGateway
import java.io.File
import org.json.JSONObject

/** Saves the selected proxy identity; its current IP is always discovered afresh. */
object RemoteGatewaySettings {
    private fun file(context: Context) = File(context.noBackupFilesDir, "remote-gateway.json")

    @Synchronized fun load(context: Context): RemoteRfcommGateway? = runCatching {
        if (com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_DIRECT_CONNECTION) {
            return@runCatching RemoteRfcommGateway(
                com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_PROXY_BLUETOOTH_MAC,
                com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_TOKEN,
            )
        }
        val stored = file(context)
        if (stored.isFile) {
            val json = JSONObject(stored.readText())
            if (json.optString("token") != com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_TOKEN) {
                json.put("token", com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_TOKEN)
                write(context, json)
            }
            return@runCatching decode(json)
        }
        val legacy = File(context.filesDir, "remote-probe.json")
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || !legacy.isFile) return@runCatching null
        val json = JSONObject(legacy.readText()).put("token", com.shilapi.xcertplay.transport.RemoteRfcommProtocol.DEVELOPMENT_TOKEN)
        val gateway = decode(json)
        write(context, json)
        legacy.delete()
        gateway
    }.getOrNull()

    @Synchronized fun save(context: Context, mac: String, token: String) {
        val json = JSONObject().put("gatewayBluetoothMac", mac).put("token", token)
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
