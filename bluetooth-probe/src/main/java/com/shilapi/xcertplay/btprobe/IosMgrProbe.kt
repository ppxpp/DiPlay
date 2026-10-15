package com.shilapi.xcertplay.btprobe

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

/**
 * android.IIOSMgr 原生 Binder 探针（ServiceManager 名 "IOSMgr"，由车机 SMARTPHONEPlayer 托管）。
 *
 * 线格式来自对车机 libiosmgr.so 的静态解码，各回复值语义尚未全部实测：
 *   data = interfaceToken("android.IIOSMgr") + int32 CLIENT_ID(0..14) + int32 DEV_MODE(0..2) + 载荷
 *   transact(code ∈ 65..82)；reply = int32 状态 [+ 类特定回复]
 *   回复形态由各类 vtable 的 writeReply/readReply 槽确定：
 *     int32（67/79）、单字节（70/71/72/74/82）、String8（75/76）、无（其余）
 *
 * DEV_MODE：0=USB1，1=USB2，2=BT（对应 dumpsys IOSMgr 首部三行状态）。
 * 仅做查询与显式连接/断开动作；不伪造授权或凭据响应；不替换系统 iAP 客户端——
 * 回调注册（77）占用独立的空闲 CLIENT_ID 槽，注销按钮（78）负责清理。
 */
internal class IosMgrProbe(private val log: (String) -> Unit) {

    enum class ReplyKind { NONE, INT32, BYTE, STRING8 }

    data class Result(
        val code: Int,
        val name: String,
        val devMode: Int,
        val status: Int?,
        val value: Int?,
        val text: String?,
        val note: String,
    ) {
        fun describe(): String = buildString {
            append("IOSMgr[$code $name] dev=$devMode 状态=$status")
            value?.let { append(" 值=$it") }
            text?.let { append(" 文本=$it") }
            append(" ($note)")
        }
    }

    companion object {
        const val SERVICE = "IOSMgr"
        const val DESCRIPTOR = "android.IIOSMgr"
        const val PROBE_CLIENT_ID = 0xe
        const val DEV_USB1 = 0
        const val DEV_USB2 = 1
        const val DEV_BT = 2
        fun firmwareKnown(): Boolean = PioneerBinder.firmwareKnown()

        fun name(code: Int): String = when (code) {
            65 -> "ConnectSpp(请求连接手机 SPP)"
            66 -> "DisConnectSpp(断开 SPP)"
            67 -> "GetConnectStatus(SPP 连接状态)"
            68 -> "BTWriteData(SPP 写字节)"
            69 -> "ConnectA2DP"
            70 -> "IsA2DPConnect"
            71 -> "IsHFPConnect"
            72 -> "IsInSiri"
            73 -> "DisConnectA2DP"
            74 -> "GetSppConnectResult(上次连接结果 bool)"
            75 -> "GetBTAddress"
            76 -> "GetBTName"
            77 -> "RegistClient(注册回调)"
            78 -> "UnRegistClient(注销回调)"
            79 -> "GetDevStatus(设备状态)"
            80 -> "Common(诊断)"
            81 -> "ReqDioSession"
            82 -> "IsIAP2HostMode"
            else -> "未知"
        }

        private fun replyKind(code: Int): ReplyKind = when (code) {
            67, 79 -> ReplyKind.INT32
            70, 71, 72, 74, 82 -> ReplyKind.BYTE
            75, 76 -> ReplyKind.STRING8
            else -> ReplyKind.NONE
        }
    }

    /**
     * 回调观察用 binder。与 BnIOSClient 行为一致：对 code 86..90 只读取并分派，不回写 reply。
     * 收到的事件以原始十六进制记录，供后续解析；不实现任何伪造响应。
     */
    val callback: Binder = object : Binder() {
        override fun getInterfaceDescriptor(): String = "android.media.IIOSClient"
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val before = data.dataPosition()
            val available = data.dataAvail()
            val hex = try {
                val bytes = ByteArray(minOf(available, 96))
                var i = 0
                while (i < bytes.size) {
                    bytes[i] = data.readByte()
                    i++
                }
                data.setDataPosition(before)
                bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            } catch (t: Throwable) {
                "解码失败：${t.javaClass.simpleName}"
            }
            log("IIOSClient 回调 code=$code 载荷=${available}B $hex")
            return true
        }
    }

    private fun target(): IBinder? = runCatching {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, SERVICE) as? IBinder
    }.getOrNull()

    fun availableDescription(): String {
        val binder = target() ?: return "IOSMgr 服务不存在（getService 返回空）"
        val descriptor = runCatching { binder.interfaceDescriptor }.getOrNull()
        return "IOSMgr 存在 interfaceDescriptor=$descriptor"
    }

    /**
     * 发送一次请求。载荷：code 68 为 int32 长度+原始字节；77/78 为回调 binder；81 为 int32 标志；
     * 其余为空。quiet 仅控制成功时不写报告（轮询用）。
     */
    fun call(code: Int, devMode: Int, intPayload: Int? = null, bytes: ByteArray? = null, quiet: Boolean = false): Result {
        val opName = name(code)
        val binder = target() ?: return Result(code, opName, devMode, null, null, null, "IOSMgr 不存在")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(PROBE_CLIENT_ID)
            data.writeInt(devMode)
            when (code) {
                68 -> {
                    // writeByteArray = int32 长度 + 原始字节，与服务端 read 的顺序一致
                    val payload = bytes ?: ByteArray(0)
                    data.writeByteArray(payload, 0, payload.size)
                }
                77, 78 -> data.writeStrongBinder(callback)
                81 -> data.writeInt(intPayload ?: 0)
            }
            if (!binder.transact(code, data, reply, 0)) {
                return Result(code, opName, devMode, null, null, null, "transact 未受理")
            }
            reply.setDataPosition(0)
            val status = if (reply.dataAvail() >= 4) reply.readInt() else null
            var value: Int? = null
            var text: String? = null
            when (replyKind(code)) {
                ReplyKind.INT32 -> if (reply.dataAvail() >= 4) value = reply.readInt()
                ReplyKind.BYTE -> if (reply.dataAvail() >= 1) value = reply.readByte().toInt() and 0xff
                ReplyKind.STRING8 -> text = readNativeString8(reply)
                ReplyKind.NONE -> {}
            }
            Result(code, opName, devMode, status, value, text, "剩余=${reply.dataAvail()}")
                .also { if (!quiet) log(it.describe()) }
        } catch (t: Throwable) {
            Result(code, opName, devMode, null, null, null, "异常：${t.javaClass.simpleName}: ${t.message}")
                .also { log(it.describe()) }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** String8：int32 字节数（可能含结尾 NUL）+ 原始字节，无 4 字节对齐填充。 */
    private fun readNativeString8(parcel: Parcel): String? = try {
        if (parcel.dataAvail() < 4) {
            null
        } else {
            val size = parcel.readInt()
            if (size !in 1..512 || parcel.dataAvail() < size) {
                "长度异常(size=$size avail=${parcel.dataAvail()})"
            } else {
                val raw = ByteArray(size)
                for (i in raw.indices) raw[i] = parcel.readByte()
                String(raw, Charsets.UTF_8).trimEnd('\u0000')
            }
        }
    } catch (t: Throwable) {
        "String8 解码失败：${t.javaClass.simpleName}"
    }
}
