package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import java.io.File

/**
 * The kernel's view of which driver is bound to each interface of the iPhone, read from sysfs.
 *
 * Linux refuses SET_CONFIGURATION while any interface of the active configuration is bound to a
 * driver ("usbfs: interface 0 claimed by snd-usb-audio while '…' sets config #6", #443), but
 * Android's USB API never names that holder. The kernel exposes it at
 * `/sys/bus/usb/devices/<port>/<configuration>.<interface>/driver`, which is world-readable.
 */
class UsbBindingSnapshot(private val sysRoot: File = File(SYS_ROOT)) {

    data class Binding(val config: Int, val interfaceId: Int, val driver: String?) {
        fun summary(): String = "$config.$interfaceId:${driver ?: "none"}"
    }

    data class Value(
        val devicePath: String?,
        val activeConfiguration: Int?,
        val bindings: List<Binding>,
        val unreadable: List<String>,
    ) {
        /** Bound drivers of [configured] only; every driver when the active configuration is unknown. */
        fun boundDrivers(configured: Int?): List<String> = bindings
            .filter { it.driver != null && (configured == null || it.config == configured) }
            .map { it.summary() }

        fun summary(): String =
            "path=${devicePath ?: "unknown"} activeCfg=${activeConfiguration ?: "unknown"} " +
                "bindings=[${bindings.joinToString(",") { it.summary() }}] " +
                "unreadable=${unreadable.joinToString(",").ifEmpty { "none" }}"
    }

    fun capture(device: UsbDevice): Value {
        val path = device.deviceName
        val (bus, number) = parseDeviceName(path)
            ?: return Value(path, null, emptyList(), listOf("deviceName=$path"))
        return capture(bus, number, path)
    }

    fun capture(bus: Int, number: Int, devicePath: String? = null): Value {
        val directory = findDeviceDirectory(bus, number)
            ?: return Value(devicePath, null, emptyList(), listOf("no sysfs entry for bus=$bus dev=$number"))
        val unreadable = mutableListOf<String>()
        val activeConfiguration = readInt(File(directory, "bConfigurationValue"))
        if (activeConfiguration == null) unreadable += "bConfigurationValue"
        val entries = directory.listFiles()
        if (entries == null) unreadable += "interface directories"
        val bindings = entries.orEmpty()
            .mapNotNull { file ->
                INTERFACE_DIRECTORY.matchEntire(file.name)?.let { match ->
                    Triple(file, match.groupValues[1].toInt(), match.groupValues[2].toInt())
                }
            }
            .filter { (file, _, _) -> file.isDirectory }
            .map { (file, config, interfaceId) ->
                // readlink distinguishes an absent driver from a link we cannot inspect.
                val driver = try {
                    android.system.Os.readlink(File(file, "driver").absolutePath).substringAfterLast('/')
                } catch (error: android.system.ErrnoException) {
                    if (error.errno != android.system.OsConstants.ENOENT) unreadable += "${file.name}/driver errno=${error.errno}"
                    null
                }
                Binding(config, interfaceId, driver)
            }
            .sortedWith(compareBy(Binding::config, Binding::interfaceId))
        return Value(devicePath, activeConfiguration, bindings, unreadable)
    }

    private fun findDeviceDirectory(bus: Int, number: Int): File? {
        val matches = sysRoot.listFiles().orEmpty().filter { entry ->
            entry.isDirectory &&
                readInt(File(entry, "busnum")) == bus &&
                readInt(File(entry, "devnum")) == number
        }
        // Root-hub entries are named usbN; a device path contains '-'.
        return matches.firstOrNull { it.name.contains('-') } ?: matches.firstOrNull()
    }

    private fun driverName(interfaceDirectory: File): String? =
        runCatching { File(interfaceDirectory, "driver").takeIf { it.exists() }?.canonicalFile?.name }.getOrNull()

    private fun readInt(file: File): Int? = runCatching { file.readText().trim().toInt() }.getOrNull()

    companion object {
        const val SYS_ROOT = "/sys/bus/usb/devices"
        private val INTERFACE_DIRECTORY = Regex("""(?:[^:]+:)?(\d+)\.(\d+)""")

        /** "/dev/bus/usb/001/002" yields bus 1 and device number 2; null for anything else. */
        fun parseDeviceName(name: String): Pair<Int, Int>? {
            val parts = name.trim().split('/')
            if (parts.size < 2) return null
            val bus = parts[parts.size - 2].toIntOrNull() ?: return null
            val number = parts.last().toIntOrNull() ?: return null
            return bus to number
        }
    }
}
