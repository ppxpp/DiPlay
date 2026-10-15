package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.os.Build

/** Narrow firmware default, with an explicit override for device comparison. */
object PioneerUsbPolicy {
    fun matchesDevice(): Boolean = Build.VERSION.SDK_INT == 25 &&
        Build.MODEL.replace('-', '_').equals("SABRESD_MX6DQ", true) &&
        Build.DEVICE.startsWith("tamago", ignoreCase = true)

    fun enabled(context: Context): Boolean = when (prefs(context).getString("usb_mode", "AUTO")) {
        "ON" -> true
        "OFF" -> false
        else -> matchesDevice()
    }
    fun mode(context: Context): String = prefs(context).getString("usb_mode", "AUTO")!!
    fun setMode(context: Context, mode: String) {
        require(mode in listOf("AUTO", "ON", "OFF")) { "usb_mode must be AUTO, ON or OFF" }
        prefs(context).edit().putString("usb_mode", mode).apply()
    }
    fun configurationMode(context: Context): String = prefs(context).getString("configuration_mode", "REINITIALIZE")!!
    fun setConfigurationMode(context: Context, mode: String) {
        require(mode in listOf("REINITIALIZE", "REUSE")) { "mode must be REINITIALIZE or REUSE" }
        check(prefs(context).edit().putString("configuration_mode", mode).commit()) { "Could not save configuration mode" }
    }
    private fun prefs(context: Context) = context.getSharedPreferences("pioneer-adapter", Context.MODE_PRIVATE)

    fun claimOverride(context: Context, log: (String) -> Unit): ((UsbDeviceConnection, UsbInterface) -> Boolean)? =
        if (!enabled(context)) null else { connection, iface -> PioneerUsbNative.claim(connection, iface, log) }

    fun configurationOverride(context: Context, log: (String) -> Unit): ((UsbDeviceConnection, UsbConfiguration) -> Boolean)? =
        if (!enabled(context)) null else { connection, target ->
            // Read active IDs from descriptors on this live, authorized connection, not target IDs.
            val active = ByteArray(1)
            check(connection.controlTransfer(0x80, 8, 0, 0, active, 1, 1_000) == 1) { "Cannot read active USB configuration" }
            val activeId = active[0].toInt() and 255
            val raw = connection.rawDescriptors ?: error("USB descriptors unavailable")
            val ids = linkedSetOf<Int>()
            var config = -1
            var offset = 0
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 255
                if (length < 2 || offset + length > raw.size) error("Invalid USB descriptor length")
                when (raw[offset + 1].toInt() and 255) {
                    2 -> if (length >= 9) config = raw[offset + 5].toInt() and 255
                    4 -> if (length >= 9 && config == activeId) ids.add(raw[offset + 2].toInt() and 255)
                }
                offset += length
            }
            check(ids.isNotEmpty()) { "No interfaces found for active USB configuration $activeId" }
            log("PIONEER_USB active=$activeId target=${target.id} activeInterfaces=${ids.joinToString()}")
            val configurationMode = configurationMode(context)
            val reuse = activeId == target.id && configurationMode == "REUSE"
            PioneerConnectionTrace.mark("usb-configuration", "mode=$configurationMode active=$activeId target=${target.id} reuse=$reuse")
            if (reuse) {
                log("PIONEER_USB_CONFIG_REUSE active=$activeId; keep configuration, claim interfaces normally")
            } else if (activeId == target.id) {
                // Reapply before claiming this session's interfaces, matching the lwip3
                // successful baseline. Merely selecting data alt 0/1 did not restore OUT.
                log("PIONEER_USB_CONFIG_REINITIALIZE active=$activeId; reapply before claiming session interfaces")
            }
            if (!reuse) check(PioneerUsbNative.select(connection, target.id, ids.toIntArray(), log)) { "Pioneer USB direct configuration switch failed; see errno in diagnostics" }
            check(connection.controlTransfer(0x80, 8, 0, 0, active, 1, 1_000) == 1 && (active[0].toInt() and 255) == target.id) {
                "Pioneer USB target configuration readback failed"
            }
            log("PIONEER_USB_CONFIG_OK active=${target.id}")
            true
        }
}
