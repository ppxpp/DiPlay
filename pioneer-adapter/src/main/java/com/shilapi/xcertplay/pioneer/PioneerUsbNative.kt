package com.shilapi.xcertplay.pioneer

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import org.json.JSONObject

/** Uses Android's authorized FD; ownership and close remain with the original transport. */
object PioneerUsbNative {
    init { System.loadLibrary("pioneer_usb") }
    private external fun claim(fd: Int, interfaceId: Int): String
    private external fun disconnectAndSelect(fd: Int, configuration: Int, interfaces: IntArray): String
    external fun bulkWriteMeasured(fd: Int, endpoint: Int, packet: ByteArray, timeoutMs: Int): LongArray
    external fun bulkWrite(fd: Int, endpoint: Int, packet: ByteArray, timeoutMs: Int): Int
    external fun clearHalt(fd: Int, endpoint: Int): Int
    external fun setAlternate(fd: Int, iface: Int, alternate: Int): Int
    external fun resetDevice(fd: Int): Int
    external fun readNotification(fd: Int, endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int
    fun claim(connection: UsbDeviceConnection, iface: UsbInterface, log: (String) -> Unit): Boolean {
        val report = claim(connection.fileDescriptor, iface.id)
        log("PIONEER_USB_CLAIM $report")
        return JSONObject(report).getBoolean("ok")
    }
    fun select(connection: UsbDeviceConnection, configuration: Int, interfaces: IntArray, log: (String) -> Unit): Boolean {
        val report = disconnectAndSelect(connection.fileDescriptor, configuration, interfaces)
        log("PIONEER_USB_DIRECT_CONFIG $report")
        return JSONObject(report).getBoolean("ok")
    }
}
