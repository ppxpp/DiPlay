package com.shilapi.xcertplay.usbcheck

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import org.json.JSONObject

/** Opt-in probe; successful claims stay on this Android connection for the real pipeline. */
object NativeUsbProbe {
    init { System.loadLibrary("usbcheck_probe") }
    private external fun claim(fd: Int, interfaceId: Int): String
    private external fun disconnectAndSelect(fd: Int, configuration: Int, interfaces: IntArray): String
    fun disconnectAndSelect(connection: UsbDeviceConnection, configuration: Int, interfaces: IntArray, log: (String) -> Unit): Boolean {
        val report = disconnectAndSelect(connection.fileDescriptor, configuration, interfaces)
        log("NATIVE_DIRECT_CONFIG $report")
        return JSONObject(report).getBoolean("ok")
    }
    private external fun selectConfiguration(fd: Int, configuration: Int): String
    fun selectConfiguration(connection: UsbDeviceConnection, configuration: Int, log: (String) -> Unit): Boolean {
        val report = selectConfiguration(connection.fileDescriptor, configuration)
        log("NATIVE_USB_CONFIG $report")
        return JSONObject(report).getBoolean("ok")
    }
    fun claim(connection: UsbDeviceConnection, iface: UsbInterface, log: (String) -> Unit): Boolean {
        val report = claim(connection.fileDescriptor, iface.id)
        log("NATIVE_USB $report")
        return JSONObject(report).getBoolean("ok")
    }
}
