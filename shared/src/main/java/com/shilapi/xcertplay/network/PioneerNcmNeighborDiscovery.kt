package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.transport.EthernetIpv6Codec

/** The Pioneer TUN never answered the Ethernet peer's NS. Own only our advertised address. */
internal object PioneerNcmNeighborDiscovery {
    fun reply(frame: ByteArray, hostAddress: ByteArray, hostMac: ByteArray): ByteArray? {
        val ethernet = EthernetIpv6Codec.parseIpv6(frame) ?: return null
        val ip = ethernet.ipv6
        if (hostAddress.size != 16 || ip.size < 64 || ip[0].toInt() ushr 4 and 15 != 6 ||
            ip[6].toInt() and 255 != 58 || ip[7].toInt() and 255 != 255 ||
            ip[40].toInt() and 255 != 135 || ip[41] != 0.toByte()) return null
        val length = ((ip[4].toInt() and 255) shl 8) or (ip[5].toInt() and 255)
        if (length < 24 || 40 + length > ip.size || !validChecksum(ip, length) ||
            !ip.copyOfRange(48, 64).contentEquals(hostAddress)) return null
        val source = ip.copyOfRange(8, 24)
        if (source[0].toInt() and 255 == 255) return null
        val dad = source.all { it == 0.toByte() }
        val destination = if (dad) ByteArray(16).apply { this[0] = 0xff.toByte(); this[1] = 2; this[15] = 1 } else source
        val na = ByteArray(64)
        na[0] = 0x60; na[5] = 24; na[6] = 58; na[7] = 255.toByte()
        hostAddress.copyInto(na, 8); destination.copyInto(na, 24)
        na[40] = 136.toByte(); na[44] = if (dad) 0x20 else 0x60
        hostAddress.copyInto(na, 48)
        // Existing codec appends the 8-byte MAC option and computes the ICMPv6 checksum.
        val payload = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(na, hostMac)
        val destinationMac = EthernetIpv6Codec.multicastDestinationMac(payload) ?: ethernet.sourceMac
        return EthernetIpv6Codec.build(hostMac, destinationMac, payload)
    }

    private fun validChecksum(ip: ByteArray, length: Int): Boolean {
        var sum = length.toLong() + 58
        fun words(start: Int, size: Int) {
            for (i in start until start + size step 2) {
                sum += ((ip[i].toInt() and 255) shl 8) +
                    (if (i + 1 < start + size) ip[i + 1].toInt() and 255 else 0)
            }
        }
        words(8, 32); words(40, length)
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum == 0xffffL
    }
}
