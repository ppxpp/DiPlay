package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Installs the private beta's experimental identity. It has no remote fallback. */
internal object DiPlayBootstrap {
    @Volatile private var ready = false

    @Synchronized fun ensure(context: Context, mfiTarget: MfiTarget) {
        installHotspotDefaults(context)
        if (mfiTarget != MfiTarget.LOCAL) return
        if (ready) return
        val target = File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY)
        if (!target.exists()) {
            val staging = File(context.noBackupFilesDir, "offline-mfi-staging")
            staging.deleteRecursively()
            check(staging.mkdirs()) { "Could not prepare local authentication" }
            staging.setReadable(false, false); staging.setReadable(true, true)
            staging.setExecutable(false, false); staging.setExecutable(true, true)
            try {
                for (name in listOf("identity.pk8", "certificate.p7b")) {
                    val file = File(staging, name)
                    context.assets.open("offline-mfi/$name").use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.setReadable(false, false); file.setReadable(true, true)
                    file.setWritable(false, false); file.setWritable(true, true)
                }
                LocalMfiAuthenticationClient.load(staging)
                check(staging.renameTo(target)) { "Could not install local authentication" }
            } finally {
                staging.deleteRecursively()
            }
        }
        LocalMfiAuthenticationClient.load(target)
        AirPlayPersistence.saveDebugLogsEnabled(context, false)
        ready = true
    }

    /** Optional private APK defaults; never replace a user's saved hotspot. */
    private fun installHotspotDefaults(context: Context) {
        val prefs = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
        if (prefs.getBoolean("bundled_hotspot_applied", false)) return
        val profile = try {
            context.assets.open("network/hotspot.json").bufferedReader().use {
                JSONObject(it.readText())
            }
        } catch (_: FileNotFoundException) {
            return
        }
        val ssid = profile.getString("ssid")
        val passphrase = profile.getString("passphrase")
        val band = ManualHotspotBand.valueOf(profile.getString("band"))
        val security = ManualHotspotSecurity.valueOf(profile.getString("security"))
        val channel = profile.getInt("channel")
        require(ManualHotspotValidation.error(ssid, passphrase) == null && channel in 0..196) {
            "Invalid bundled hotspot configuration"
        }
        require((security == ManualHotspotSecurity.OPEN) == passphrase.isEmpty()) {
            "Invalid bundled hotspot security"
        }
        if (AirPlayPersistence.loadManualHotspotSsid(context).isBlank()) {
            AirPlayPersistence.saveManualHotspotSsid(context, ssid)
            AirPlayPersistence.saveManualHotspotPassphrase(context, passphrase)
            AirPlayPersistence.saveManualHotspotBand(context, band)
            AirPlayPersistence.saveManualHotspotChannel(context, channel)
            AirPlayPersistence.saveManualHotspotSecurity(context, security)
            AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
            AirPlayPersistence.saveWirelessEnabled(context, true)
        }
        prefs.edit().putBoolean("bundled_hotspot_applied", true).apply()
    }

    fun deviceId(identity: AirPlayIdentity): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.publicKey).take(6).toByteArray()
        bytes[0] = ((bytes[0].toInt() and 0xfc) or 0x02).toByte()
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xff) }
    }
}

internal enum class DefaultConnectionMode(val key: String) {
    LAST_USED("last_used"), WIRELESS("wireless"), USB("usb");

    fun wireless(lastUsedWireless: Boolean): Boolean = when (this) {
        LAST_USED -> lastUsedWireless
        WIRELESS -> true
        USB -> false
    }

    companion object {
        fun fromKey(key: String?): DefaultConnectionMode = entries.firstOrNull { it.key == key } ?: LAST_USED
    }
}

internal object DiPlayPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)
    fun phoneAddress(context: Context): String? = prefs(context).getString("phone_address", null)
    fun phoneName(context: Context): String = prefs(context).getString("phone_name", null) ?: "Your iPhone"
    fun savePhone(context: Context, address: String, name: String) {
        prefs(context).edit().putString("phone_address", address).putString("phone_name", name).apply()
    }
    fun defaultConnectionMode(context: Context) =
        DefaultConnectionMode.fromKey(prefs(context).getString("default_connection_mode", null))
    fun saveDefaultConnectionMode(context: Context, mode: DefaultConnectionMode) {
        prefs(context).edit().putString("default_connection_mode", mode.key).apply()
    }
    fun autoConnectWireless(context: Context) =
        defaultConnectionMode(context).wireless(AirPlayPersistence.loadWirelessEnabled(context))
    fun autoConnect(context: Context) = prefs(context).getBoolean("auto_connect", false)
    fun saveAutoConnect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_connect", value).apply()
    }
    fun connectOnPhoneBluetooth(context: Context) = prefs(context).getBoolean("connect_on_phone_bluetooth", false)
    fun saveConnectOnPhoneBluetooth(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("connect_on_phone_bluetooth", value).apply()
    }
}
