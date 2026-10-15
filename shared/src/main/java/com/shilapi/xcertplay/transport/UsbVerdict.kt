package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * Names why Android refused to switch the iPhone to its CarPlay configuration or to claim one of
 * its interfaces, together with the evidence that supports the name.
 *
 * "setConfiguration returned false" and "claimInterface returned false" look identical to an app,
 * and a bug report cannot tell a kernel driver apart from a userspace fd holder without the
 * kernel's own view (#443). Every verdict therefore carries the sysfs bindings, the active
 * configuration read back over the wire, and which forced-claim detach attempts already ran.
 */
object UsbVerdict {
    enum class Pattern(val id: String) {
        /** A kernel driver is bound to an interface of the active configuration: usbfs EBUSY. */
        KERNEL_DRIVER_HOLDS_CONFIG("KERNEL_DRIVER_HOLDS_CONFIG"),

        /** No kernel driver is bound, so a userspace process likely holds an fd on the device. */
        USERSPACE_FD_HOLDER("USERSPACE_FD_HOLDER"),

        /** The kernel's binding evidence could not be read on this unit; do not guess. */
        UNKNOWN("UNKNOWN"),
    }

    data class Evidence(
        val claimInterfaceId: Int?,
        val targetConfiguration: Int?,
        val activeConfiguration: Int?,
        val switchSucceeded: Boolean,
        val boundDrivers: List<String>,
        val detachAttempted: Boolean,
        val detachFailures: List<Int>,
        val sysfsReadable: Boolean,
    )

    data class Verdict(val pattern: Pattern, val line: String, val remedy: String)

    fun verdict(evidence: Evidence): Verdict {
        val pattern = classify(evidence)
        return Verdict(pattern, line(evidence, pattern), remedy(pattern))
    }

    fun classify(evidence: Evidence): Pattern = when {
        !evidence.sysfsReadable -> Pattern.UNKNOWN
        evidence.boundDrivers.isNotEmpty() -> Pattern.KERNEL_DRIVER_HOLDS_CONFIG
        else -> Pattern.UNKNOWN
    }

    /**
     * Collects the kernel and iPhone evidence on a best-effort path; a broken unit must not mask
     * the original USB failure, so nothing here throws.
     */
    fun forUsbFailure(
        device: UsbDevice?,
        connection: UsbDeviceConnection?,
        claimInterfaceId: Int?,
        targetConfiguration: Int?,
        switchSucceeded: Boolean,
        detachAttempted: Boolean = false,
        detachFailures: List<Int> = emptyList(),
    ): Verdict = runCatching {
        val snapshot = captureSnapshot(device)
        val active = connection?.let { readActiveConfiguration(it) } ?: snapshot?.activeConfiguration
        verdict(
            Evidence(
                claimInterfaceId = claimInterfaceId,
                targetConfiguration = targetConfiguration,
                activeConfiguration = active,
                switchSucceeded = switchSucceeded,
                boundDrivers = snapshot?.boundDrivers(active).orEmpty(),
                detachAttempted = detachAttempted,
                detachFailures = detachFailures,
                sysfsReadable = snapshot != null && snapshot.unreadable.isEmpty(),
            ),
        )
    }.getOrElse { error ->
        val fallback = verdict(
            Evidence(
                claimInterfaceId, targetConfiguration, null, switchSucceeded,
                emptyList(), detachAttempted, detachFailures, sysfsReadable = false,
            ),
        )
        fallback.copy(remedy = "${fallback.remedy} (evidence collection failed: $error)")
    }

    /** The kernel's interface bindings for [device], or null when sysfs is unavailable. */
    fun captureSnapshot(device: UsbDevice?): UsbBindingSnapshot.Value? =
        device?.let { runCatching { UsbBindingSnapshot().capture(it) }.getOrNull() }

    /** The iPhone's current configuration value (standard GET_CONFIGURATION), or null if unreadable. */
    fun readActiveConfiguration(connection: UsbDeviceConnection): Int? {
        val value = ByteArray(1)
        val read = connection.controlTransfer(
            USB_STANDARD_DEVICE_IN, GET_CONFIGURATION_REQUEST, 0, 0, value, value.size, CONTROL_TRANSFER_TIMEOUT_MILLIS,
        )
        return if (read == value.size) value[0].toInt() and 0xff else null
    }

    private fun line(evidence: Evidence, pattern: Pattern): String = buildString {
        append("USB_VERDICT: ").append(pattern.id)
        append(" claimIface=").append(evidence.claimInterfaceId ?: "none")
        append(" targetCfg=").append(evidence.targetConfiguration ?: "unknown")
        append(" activeCfg=").append(evidence.activeConfiguration ?: "unknown")
        append(" switch=").append(if (evidence.switchSucceeded) "ok" else "failed")
        append(" drivers=").append(evidence.boundDrivers.joinToString(",").ifEmpty { "none" })
        append(" detach=").append(
            when {
                !evidence.detachAttempted -> "none"
                evidence.detachFailures.isEmpty() -> "attempted&moved"
                else -> "attempted&failed(${evidence.detachFailures.joinToString(",")})"
            },
        )
        if (!evidence.sysfsReadable) append(" sysfs=unreadable")
    }

    private fun remedy(pattern: Pattern): String = when (pattern) {
        Pattern.KERNEL_DRIVER_HOLDS_CONFIG ->
            "a kernel driver is bound to an interface of the active configuration; " +
                "compare the failing interface/configuration and ioctl errno before attributing the failure"
        Pattern.USERSPACE_FD_HOLDER ->
            "no kernel driver is bound, so another process likely holds the USB interface; " +
                "stop other USB apps or reboot the head unit"
        Pattern.UNKNOWN ->
            "USB failure cause is not established; absent driver bindings do not prove a userspace holder; " +
                "collect active configuration and native ioctl errno (kernel logs if readable)"
    }

    private const val USB_STANDARD_DEVICE_IN = 0x80
    private const val GET_CONFIGURATION_REQUEST = 0x08
    private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000
}
