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
}
