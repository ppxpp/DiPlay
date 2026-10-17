package com.shilapi.xcertplay.btprobe

import android.hardware.usb.UsbDeviceConnection

/**
 * 借用 Android 已授权的 usbfs fd，直接走异步 URB 队列验证 HCI 收发。
 * 只借用 fd，不 open 设备路径、也不关闭它（生命周期归 Java 层的 UsbDeviceConnection）。
 */
object UsbBtNative {
    /** 库缺失或未加载时的降级：调用方按"未验证"处理，不当成失败证据。 */
    @Volatile var available: Boolean = false
        private set

    init {
        runCatching { System.loadLibrary("usb_bt_hci") }.onSuccess { available = true }
    }

    private external fun hciProbe(fd: Int, interfaceId: Int, endpointOut: Int, endpointIn: Int): String

    fun probe(connection: UsbDeviceConnection, interfaceId: Int, endpointOut: Int, endpointIn: Int): String? =
        runCatching { hciProbe(connection.fileDescriptor, interfaceId, endpointOut, endpointIn) }
            .getOrNull()

    /** OUT URB 以 status=0 完成 = App 的 URB 通路可用，与模块是否应答无关。 */
    fun outChannelProven(report: String): Boolean = report.contains("OUT_STATUS=0")

    /** 同步 bulk OUT 把 4 字节 HCI 命令送达设备（USB 层已 ACK）。 */
    fun syncOutDelivered(report: String): Boolean = report.contains("SYNC_BULK ret=4")

    /** 同步读 IN 返回 ETIMEDOUT(110)：内核确实去取了，是设备没有数据，不是权限或参数被拒。 */
    fun syncInTimedOut(report: String): Boolean = report.contains("SYNC_IN ret=-1 errno=110")

    /** 异步 URB 提交成功却从不完成：本 App 的 HCI 传输只能走同步路径。 */
    fun asyncUrbStalls(report: String): Boolean = report.contains("OUT_STATUS=none") ||
        report.contains("IN_STATUS=none")

    /** 读到任何 IN 字节 = 模块确实在回 HCI 事件。 */
    fun inDataSeen(report: String): Boolean = "IN_EVENTS=".let { key ->
        val value = report.substringAfter(key, "").takeWhile { it.isDigit() }.toIntOrNull()
        value != null && value > 0
    }
}
