package com.shilapi.xcertplay.airplay

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * The Pioneer head unit's carnetserver firewall drops inbound new connections except on a fixed
 * port list, so when [AirPlayConfig.pinInboundPorts] is set every iPhone-initiated CarPlay
 * connection binds to one of these ports instead of an ephemeral one.
 *
 * The main AirPlay control port 8721 is also forwarded but is owned by the VPN service listener.
 */
internal object CarFirewallPorts {
    /**
     * Forwarded inbound TCP. One shared listener serves the event channel and the screen streams;
     * the iAP tunnel takes the next port.
     */
    val TCP = intArrayOf(9911, 9912, 5656)

    /**
     * Forwarded inbound UDP for audio RTP. The timing NTP socket stays ephemeral by preference:
     * the car sends the requests, so the phone's replies return through established flow state.
     */
    val AUDIO_UDP = intArrayOf(9911, 5656)

    /** The one forwarded UDP port timing may take opportunistically (a map app usually holds it). */
    val TIMING_UDP = intArrayOf(9912)

    /**
     * The wildcard of [address]'s family. Pinned listeners bind the family wildcard instead of a
     * dual-stack "::": whether "::" also accepts IPv4 depends on the platform's IPV6_V6ONLY
     * default (AirPlayPortSelector avoids the same assumption with per-family listeners).
     */
    fun familyWildcard(address: InetAddress?): InetAddress =
        if (address is Inet4Address) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("::")

    /**
     * Binds a TCP listener on [address]'s family wildcard to the first free firewall port, or to
     * an ephemeral one when none is free (the connection stays subject to the firewall, but
     * nothing else can be done).
     */
    fun tcpListener(address: InetAddress?, preferred: IntArray?, log: (String) -> Unit): ServerSocket {
        val wildcard = familyWildcard(address)
        if (preferred != null) {
            for (port in preferred) {
                val socket = try {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(wildcard, port))
                    }
                } catch (_: Exception) {
                    continue
                }
                log("airplay listener pinned to firewall port=$port address=$wildcard")
                return socket
            }
            log("no firewall TCP port free; falling back to an ephemeral port")
        }
        return ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(wildcard, 0))
        }
    }

    /**
     * Binds a UDP socket on [address]'s family wildcard to the first free firewall port, or to an
     * ephemeral one. A pinned port is never shared: two CarPlay streams on one port would mix
     * their datagrams.
     */
    fun udpSocket(address: InetAddress?, preferred: IntArray?): DatagramSocket {
        val wildcard = familyWildcard(address)
        if (preferred != null) {
            for (port in preferred) {
                val socket = try {
                    DatagramSocket(null).apply {
                        bind(InetSocketAddress(wildcard, port))
                    }
                } catch (_: Exception) {
                    continue
                }
                return socket
            }
        }
        return DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(wildcard, 0))
        }
    }
}
