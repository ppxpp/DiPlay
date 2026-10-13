package com.shilapi.xcertplay.btprobe

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2LinkChannel

/** 接到真实字节通道后复用 DiPlay 协议引擎；链路就绪仍不等于 MFi 或 CarPlay 成功。 */
internal object Iap2LinkProbe {
    fun verify(stream: BlockingDuplexByteStream, log: (String) -> Unit): Boolean {
        var received = 0
        val traced = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) {
                log("iAP2 TX ${data.size}B ${hex(data)}")
                stream.send(data)
            }
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
                stream.recv(maxBytes, timeoutMillis)?.also {
                    received += it.size
                    if (it.isNotEmpty()) log("iAP2 RX ${it.size}B ${hex(it)}")
                }
            override fun close() = stream.close()
        }
        val link = Iap2LinkChannel.openWireless(traced)
        try {
            val ready = link.awaitReady(15000)
            val verified = ProbePolicy.iap2Verified(true, received, ready)
            log("iAP2 linkReady=$ready receivedBytes=$received verified=$verified")
            return verified
        } finally { link.close() }
    }
    private fun hex(data: ByteArray) = data.take(48).joinToString(" ") { "%02X".format(it.toInt() and 255) }
}
