package com.shilapi.xcertplay.usbcheck

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded local UDP echo through a real TUN, using DiPlay's IPv6 VPN builder settings. */
class VpnDemoService : VpnService() {
    private val running = AtomicBoolean(false)
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var socket: DatagramSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.compareAndSet(false, true)) Thread({ probe(); stopSelf() }, "usbcheck-vpn-demo").start()
        return START_NOT_STICKY
    }

    private fun probe() {
        val result = JSONObject().put("state", "RUNNING").put("established", false)
            .put("tunRead", false).put("tunWrite", false).put("socketEcho", false)
            .put("consentLaunchError", status(this).optString("consentLaunchError"))
            .put("settings", "fe80::2/64 route fe80::/64 mtu=1500 blocking=true allowedApplication=$packageName")
        fun save() = save(this, result)
        save()
        try {
            result.put("authorizedBeforeEstablish", prepare(this) == null); save()
            val descriptor = Builder().addAddress("fe80::2", 64).addRoute("fe80::", 64)
                .setSession("USBCheck VPN demo").setMtu(1500).setBlocking(true)
                .addAllowedApplication(packageName).establish() ?: error("VpnService.establish returned null")
            tun = descriptor
            result.put("established", true); save()
            val hostBytes = InetAddress.getByName("fe80::2").address
            val iface = NetworkInterface.getNetworkInterfaces().toList().firstOrNull { candidate ->
                candidate.name.startsWith("tun") && candidate.inetAddresses.toList().any { it.address.contentEquals(hostBytes) }
            } ?: error("No TUN interface owns fe80::2")
            result.put("interface", iface.name).put("scopeId", iface.index); save()
            val host = Inet6Address.getByAddress(null, hostBytes, iface.index)
            val peer = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1234").address, iface.index)
            val udp = DatagramSocket(null)
            socket = udp
            udp.bind(InetSocketAddress(host, 0))
            udp.soTimeout = 5_000
            val payload = "usbcheck-vpn-echo".toByteArray()
            udp.send(DatagramPacket(payload, payload.size, peer, 19911))
            val poll = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
            val buffer = ByteArray(2048)
            val deadline = System.nanoTime() + 5_000_000_000L
            var matched = false
            while (System.nanoTime() < deadline) {
                if (Os.poll(arrayOf(poll), 500) == 0) continue
                val length = Os.read(descriptor.fileDescriptor, buffer, 0, buffer.size)
                if (length != 48 + payload.size || buffer[6].toInt() != 17 ||
                    !buffer.copyOfRange(48, length).contentEquals(payload)) continue
                result.put("tunRead", true).put("packetBytes", length); save()
                val reply = buffer.copyOf(length)
                buffer.copyInto(reply, 8, 24, 40); buffer.copyInto(reply, 24, 8, 24)
                reply[40] = buffer[42]; reply[41] = buffer[43]
                reply[42] = buffer[40]; reply[43] = buffer[41]
                reply[46] = 0; reply[47] = 0
                val pseudo = reply.copyOfRange(8, 40) + byteArrayOf(0, 0, 0, (length - 40).toByte(), 0, 0, 0, 17) + reply.copyOfRange(40, length)
                var sum = 0L
                for (i in pseudo.indices step 2) sum += ((pseudo[i].toInt() and 255) shl 8) +
                    (if (i + 1 < pseudo.size) pseudo[i + 1].toInt() and 255 else 0)
                while (sum ushr 16 != 0L) sum = (sum and 65535) + (sum ushr 16)
                val checksum = (sum.inv().toInt() and 65535).let { if (it == 0) 65535 else it }
                reply[46] = (checksum ushr 8).toByte(); reply[47] = checksum.toByte()
                check(Os.write(descriptor.fileDescriptor, reply, 0, reply.size) == reply.size) { "Short TUN write" }
                result.put("tunWrite", true); save()
                matched = true
                break
            }
            check(matched) { "Timed out reading matching UDP packet from TUN" }
            val response = DatagramPacket(ByteArray(256), 256)
            udp.receive(response)
            check(response.data.copyOfRange(response.offset, response.offset + response.length).contentEquals(payload)
                && response.address.address.contentEquals(peer.address) && response.port == 19911) { "UDP echo mismatch" }
            result.put("socketEcho", true).put("state", "PASS")
        } catch (error: Throwable) {
            result.put("state", "FAIL").put("error", generateSequence(error) { it.cause }.take(4)
                .joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" })
        } finally {
            close()
            result.put("tunnelClosed", true)
            save()
            running.set(false)
        }
    }

    private fun close() { runCatching { socket?.close() }; socket = null; runCatching { tun?.close() }; tun = null }
    override fun onRevoke() { close(); super.onRevoke() }
    override fun onDestroy() { close(); super.onDestroy() }

    companion object {
        fun status(context: Context): JSONObject = JSONObject(context.getSharedPreferences("vpn-demo", Context.MODE_PRIVATE)
            .getString("result", "{\"state\":\"IDLE\"}")!!)
        fun save(context: Context, result: JSONObject) {
            context.getSharedPreferences("vpn-demo", Context.MODE_PRIVATE).edit().putString("result", result.toString()).commit()
            android.util.Log.i("USBCheckVpn", result.toString())
        }
    }
}
