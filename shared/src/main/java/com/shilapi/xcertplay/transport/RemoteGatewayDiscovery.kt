package com.shilapi.xcertplay.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.RemoteRfcommGateway
import java.io.IOException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Discover current endpoints without opening Bluetooth or persisting their IP addresses. */
object RemoteGatewayDiscovery {
    data class Endpoint(
        val gatewayBluetoothMac: String,
        val host: String,
        val port: Int,
        val source: String,
        /** Local UDP port that received the discovery reply; 0 when discovery used NSD. */
        val discoveryLocalPort: Int = 0,
    )

    /** Verify first-pairing credentials without opening the iPhone RFCOMM channel. */
    fun authenticate(endpoint: Endpoint, token: String) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), 5000)
            socket.soTimeout = 5000
            RemoteRfcommProtocol.write(DataOutputStream(socket.getOutputStream()), RemoteRfcommProtocol.HELLO,
                token.encodeToByteArray())
            val response = RemoteRfcommProtocol.read(DataInputStream(socket.getInputStream()))
            if (response.type != RemoteRfcommProtocol.HELLO ||
                !response.payload.decodeToString().equals(endpoint.gatewayBluetoothMac, ignoreCase = true)) {
                throw IOException("Gateway pairing rejected")
            }
        }
    }

    fun connect(context: Context, gateway: RemoteRfcommGateway, log: (String) -> Unit): RemoteRfcommDuplexStream {
        val endpoint = find(context, gateway.gatewayBluetoothMac, log)
        val stream = RemoteRfcommDuplexStream.connect(endpoint.host, endpoint.port, gateway.token, log)
        if (!stream.gatewayBluetoothMac.equals(gateway.gatewayBluetoothMac, ignoreCase = true)) {
            stream.close()
            throw IOException("Gateway identity mismatch")
        }
        return stream
    }

    @Suppress("DEPRECATION")
    fun find(context: Context, identity: String? = null, log: (String) -> Unit = {}): Endpoint {
        discoverOverBroadcast(identity, log)?.let { return it }

        val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        val candidates = LinkedBlockingQueue<NsdServiceInfo>()
        val failure = AtomicReference<String?>()
        val expectedName = identity?.let(RemoteRfcommProtocol::serviceName)
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                failure.set("Gateway discovery failed code=$code")
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if ((expectedName == null && info.serviceName.startsWith("DiPlay-Proxy-")) ||
                    info.serviceName == expectedName || (expectedName != null && info.serviceName.startsWith("$expectedName ("))) {
                    candidates.offer(info)
                }
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        log("Gateway discovery started service=$expectedName")
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val multicastLock = wifi.createMulticastLock("DiPlayGatewayDiscovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            nsd.discoverServices(RemoteRfcommProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            while (System.nanoTime() < deadline) {
                failure.get()?.let { throw IOException(it) }
                val candidate = candidates.poll(500, TimeUnit.MILLISECONDS) ?: continue
                val resolved = AtomicReference<NsdServiceInfo?>()
                val done = CountDownLatch(1)
                nsd.resolveService(candidate, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        log("Gateway discovery resolve failed code=$code")
                        done.countDown()
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        resolved.set(info)
                        done.countDown()
                    }
                })
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || !done.await(remaining, TimeUnit.NANOSECONDS)) break
                val info = resolved.get() ?: continue
                val advertisedIdentity = info.attributes["gateway"]?.decodeToString() ?: continue
                if (!advertisedIdentity.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) continue
                if (identity != null && !advertisedIdentity.equals(identity, ignoreCase = true)) continue
                val host = info.host as? Inet4Address ?: continue
                if (info.port !in 1..65535) continue
                log("Gateway discovered address=${host.hostAddress} port=${info.port} source=nsd")
                return Endpoint(advertisedIdentity, host.hostAddress!!, info.port, "nsd")
            }
            throw IOException("Gateway not discovered within 15 seconds; check Android A proxy and Wi-Fi")
        } finally {
            runCatching { nsd.stopServiceDiscovery(discovery) }
            multicastLock.release()
        }
    }

    private fun discoverOverBroadcast(
        identity: String?,
        log: (String) -> Unit,
    ): Endpoint? {
        val targets = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface ->
                    networkInterface.interfaceAddresses.mapNotNull { interfaceAddress ->
                        val address = interfaceAddress.address as? Inet4Address ?: return@mapNotNull null
                        val broadcast = interfaceAddress.broadcast as? Inet4Address ?: return@mapNotNull null
                        if (address.isLoopbackAddress || !address.isSiteLocalAddress) return@mapNotNull null
                        address to broadcast
                    }
                }
                .distinctBy { it.first.hostAddress }
        }.getOrElse {
            log("Gateway broadcast discovery could not enumerate interfaces error=${it.javaClass.simpleName}")
            emptyList()
        }
        if (targets.isEmpty()) return null

        log("Gateway broadcast discovery started interfaces=${targets.size}")
        val query = RemoteRfcommProtocol.discoveryRequest(identity ?: "*")
        for ((localAddress, broadcastAddress) in targets) {
            try {
                openDiscoverySocket(localAddress, log).use { socket ->
                    socket.soTimeout = 500
                    socket.send(DatagramPacket(query, query.size, broadcastAddress,
                        RemoteRfcommProtocol.DISCOVERY_PORT))
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700)
                    val buffer = ByteArray(256)
                    while (System.nanoTime() < deadline) {
                        socket.soTimeout = ((deadline - System.nanoTime()) / 1_000_000L)
                            .toInt().coerceIn(1, 500)
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        }
                        val response = RemoteRfcommProtocol.parseDiscoveryResponse(packet.data, packet.length)
                            ?: continue
                        if (identity != null && !response.gatewayBluetoothMac.equals(identity, ignoreCase = true)) {
                            continue
                        }
                        val host = packet.address.hostAddress ?: continue
                        log("Gateway discovered address=$host port=${response.tcpPort} source=udp-broadcast interface=${localAddress.hostAddress} localPort=${socket.localPort}")
                        return Endpoint(response.gatewayBluetoothMac, host, response.tcpPort, "udp-broadcast",
                            discoveryLocalPort = socket.localPort)
                    }
                }
            } catch (error: Exception) {
                log("Gateway broadcast discovery interface=${localAddress.hostAddress} failed error=${error.javaClass.simpleName}")
            }
        }
        log("Gateway broadcast discovery found no matching service; trying NSD")
        return null
    }

    // The head unit inbound firewall only accepts UDP on 9911/9912/5656 (iptables_rules.sh),
    // and the proxy replies to the request source port, so discovery must hold one of them.
    private val DISCOVERY_SOURCE_PORTS = intArrayOf(9911, 5656, 9912)

    private fun openDiscoverySocket(localAddress: Inet4Address, log: (String) -> Unit): DatagramSocket {
        for (port in DISCOVERY_SOURCE_PORTS) {
            val socket = DatagramSocket(null)
            try {
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(localAddress, port))
                log("Gateway broadcast discovery bound source port=$port interface=${localAddress.hostAddress}")
                return socket
            } catch (error: Exception) {
                runCatching { socket.close() }
                log("Gateway broadcast discovery source port=$port unavailable error=${error.javaClass.simpleName}")
            }
        }
        val socket = DatagramSocket(null)
        socket.reuseAddress = true
        socket.broadcast = true
        socket.bind(InetSocketAddress(localAddress, 0))
        log("Gateway broadcast discovery no firewall port free; using ephemeral port=${socket.localPort} interface=${localAddress.hostAddress}")
        return socket
    }
}
