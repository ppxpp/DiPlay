package com.shilapi.xcertplay.usbcheck

import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.net.InetAddress

/** Best-effort raw NCM probe. No VPN, sockets, routing changes or CarPlayStartSession. */
object NcmPacketProbe {
    data class Result(val tx: Int, val rx: Int, val echoReply: Boolean)

    fun run(bridge: NcmUsbBridge, cancelled: () -> Boolean, log: (String) -> Unit): Result {
        val mac = bridge.hostMac ?: error("NCM 未提供 host MAC，不能构造以太网探测帧")
        val host = InetAddress.getByName("fe80::2").address
        val allNodes = InetAddress.getByName("ff02::1").address
        val multicastMac = byteArrayOf(0x33, 0x33, 0, 0, 0, 1)
        val echo = byteArrayOf(128.toByte(), 0, 0, 0, 0x55, 0x43, 0, 1) + "usbcheck".toByteArray()
        var tx = 0
        var rx = 0
        fun send(destination: ByteArray, destinationMac: ByteArray, message: ByteArray, hop: Int = 64) {
            bridge.send(EthernetIpv6Codec.build(mac, destinationMac, packet(host, destination, message, hop)), 1_000)
            tx++
        }
        send(allNodes, multicastMac, echo)
        val deadline = System.nanoTime() + 8_000_000_000L
        var nextEcho = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (cancelled()) throw InterruptedException("NCM 探测已取消")
            if (System.nanoTime() >= nextEcho) { send(allNodes, multicastMac, echo); nextEcho += 2_000_000_000L }
            val raw = bridge.recv(250) ?: continue
            rx++
            val frame = EthernetIpv6Codec.parseIpv6(raw) ?: continue
            val ip = frame.ipv6
            if (ip.size < 48 || ip[6].toInt() and 0xff != 58) continue
            val type = ip[40].toInt() and 0xff
            val source = ip.copyOfRange(8, 24)
            log("NCM_PACKET rx=$rx bytes=${raw.size} icmpv6=$type source=${InetAddress.getByAddress(source).hostAddress}")
            if (type == 129 && ip[44] == 0x55.toByte() && ip[45] == 0x43.toByte() &&
                ip[46] == 0.toByte() && ip[47] == 1.toByte() && ip.size >= 56 &&
                ip.copyOfRange(48, 56).contentEquals("usbcheck".toByteArray())) {
                return Result(tx, rx, true)
            }
            // Answer the peer's request for our link-local MAC so a unicast reply can arrive.
            if (type == 135 && ip.size >= 64 && ip.copyOfRange(48, 64).contentEquals(host)) {
                val dad = source.all { it == 0.toByte() }
                val na = ByteArray(32)
                na[0] = 136.toByte(); na[4] = if (dad) 0x20 else 0x60
                host.copyInto(na, 8); na[24] = 2; na[25] = 1; mac.copyInto(na, 26)
                send(if (dad) allNodes else source, if (dad) multicastMac else frame.sourceMac, na, 255)
            }
        }
        return Result(tx, rx, false)
    }

    private fun packet(source: ByteArray, destination: ByteArray, payload: ByteArray, hop: Int): ByteArray {
        val body = payload.copyOf()
        body[2] = 0; body[3] = 0
        val pseudo = source + destination + byteArrayOf(0, 0, (body.size ushr 8).toByte(), body.size.toByte(), 0, 0, 0, 58) + body
        var sum = 0L
        for (i in pseudo.indices step 2) sum += ((pseudo[i].toInt() and 255) shl 8) + (if (i+1 < pseudo.size) pseudo[i+1].toInt() and 255 else 0)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        val checksum = sum.inv().toInt() and 0xffff
        body[2] = (checksum ushr 8).toByte(); body[3] = checksum.toByte()
        val result = ByteArray(40 + body.size)
        result[0] = 0x60; result[4] = (body.size ushr 8).toByte(); result[5] = body.size.toByte()
        result[6] = 58; result[7] = hop.toByte()
        source.copyInto(result, 8); destination.copyInto(result, 24); body.copyInto(result, 40)
        return result
    }
}
