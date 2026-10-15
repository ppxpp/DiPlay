package com.shilapi.xcertplay.btprobe

/** 将系统返回值与协议成功证据分开，避免把电话连接或命令受理算作 iAP2 成功。 */
internal object ProbePolicy {
    fun connectionBlock(firmwareKnown: Boolean, selectedMask: Int?, idle: Boolean): String? = when {
        !firmwareKnown -> "当前固件未核对，不能使用已逆向的 Binder 编号"
        selectedMask == null -> "无法读取当前设备配置"
        selectedMask and 0xf0 == 0 -> "当前配置没有原生串口目标；保持电话与音乐配置，等待确认 iAP2 选择入口"
        !idle -> "串口已有状态，不能接管系统会话"
        else -> null
    }
    fun iap2Verified(commandAccepted: Boolean, receivedBytes: Int, linkReady: Boolean): Boolean =
        commandAccepted && receivedBytes > 0 && linkReady

    /**
     * IOSMgr SPP 发起前的门禁。值语义已按本轮实测与符号标定：
     * 67 GetConnectStatus(int) 为 SPP 实时状态，0=未连接；79 GetDevStatus=0 对应 dumpsys 槽 invalid（空闲）。
     * 74 是 wire 上的 IOSDeviceIF::getSppConnectResult(bool&)：对端保留的“上次连接结果”，
     * 非实时忙闲标志（实测其=1 而 67=0、dumpsys 空闲），故仅记录、不作门禁。
     */
    fun sppConnectBlock(
        firmwareKnown: Boolean,
        queriesAccepted: Boolean,
        deviceStatus: Int?,
        connectStatus: Int?,
    ): String? = when {
        !firmwareKnown -> "当前固件未核对，不能使用已逆向的 Binder 编号"
        !queriesAccepted -> "IOSMgr 查询未受理（状态非 0；-1 表示 BTManager 层缺失），先排查可调用性"
        deviceStatus == null || connectStatus == null -> "状态证据不完整，不发起连接"
        deviceStatus != 0 -> "BT 设备槽已有活动设备（值=$deviceStatus），不接管"
        connectStatus != 0 -> "SPP 连接已存在（值=$connectStatus），不接管"
        else -> null
    }
}
