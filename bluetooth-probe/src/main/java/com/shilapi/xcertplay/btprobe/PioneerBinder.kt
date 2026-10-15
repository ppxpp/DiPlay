package com.shilapi.xcertplay.btprobe

import android.os.Build
import android.os.IBinder
import android.os.Parcel

/** 编号仅适用于已分析的先锋固件。回复是原生状态码与输出，没有 Java AIDL 异常头。 */
internal class PioneerBinder(private val log: (String) -> Unit) {
    companion object {
        const val SERVICE = "jp.pioneer.ceam.Blueforce.manage"
        private const val FIRMWARE =
            "Android/tamago_3T_uart/tamago_3T_uart:7.1.2/2.0.0-rfp-rc6/1:user/release-keys"
        fun firmwareKnown(): Boolean = Build.FINGERPRINT == FIRMWARE
        fun service(name: String): IBinder? = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java).invoke(null, name) as? IBinder
    }
    private fun binder(): IBinder = service(SERVICE)?.also {
        check(it.interfaceDescriptor == SERVICE) { "先锋服务描述符不匹配" }
    } ?: error("先锋蓝牙服务不存在")
    fun firmwareKnown(): Boolean = Companion.firmwareKnown()
    fun query(code: Int, vararg args: Int): List<Int> {
        check(code in setOf(67, 78, 79, 80, 81, 134, 141)) { "不是已确认的查询接口" }
        return transact(code, *args)
    }
    fun startCandidate() = transact(133, 0)
    fun stopCandidate() = transact(135, 0)
    private fun transact(code: Int, vararg args: Int): List<Int> {
        check(firmwareKnown()) { "固件指纹不同，未发送 Binder 调用" }
        val target = binder()
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            request.writeInterfaceToken(SERVICE)
            args.forEach { request.writeInt(it) }
            log("Binder 请求 code=$code args=${args.toList()}")
            check(target.transact(code, request, reply, 0)) { "Binder 调用未受理" }
            reply.setDataPosition(0)
            check(reply.dataAvail() in 4..64 && reply.dataAvail() % 4 == 0) { "回复格式不符合已分析协议" }
            return buildList { while (reply.dataAvail() >= 4) add(reply.readInt()) }.also {
                log("Binder 回复 code=$code values=$it")
            }
        } finally { request.recycle(); reply.recycle() }
    }
    fun output(code: Int, handle: Int): Int? {
        val values = query(code, handle)
        return values.takeIf { it.size == 2 && it[0] == 0 }?.get(1)
    }
}
