package com.shihab.diplay.btproxy

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.shilapi.xcertplay.transport.RemoteRfcommProtocol as Protocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.EOFException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One authenticated peer at a time. Android A never interprets forwarded protocol payloads. */
class RfcommGatewayServer(context: Context, private val directory: File, private val log: (String) -> Unit) {

    /** Byte totals and start time of the relay session that is running, or the last one that ended. */
    data class Session(val bytesFromIphone: Long, val bytesToIphone: Long, val startedMs: Long)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    /** Each start owns its sockets, discovery registration and counters until it is closed. */
    private class Run(val server: ServerSocket) {
        var registration: NsdManager.RegistrationListener? = null
        @Volatile var discovery: DatagramSocket? = null
        @Volatile var client: Socket? = null
        @Volatile var bluetooth: BluetoothSocket? = null
        @Volatile var discoveryPeer: String? = null
        val bytesFromIphone = AtomicLong()
        val bytesToIphone = AtomicLong()
        @Volatile var startedMs = 0L
        fun session() = Session(bytesFromIphone.get(), bytesToIphone.get(), startedMs)
    }

    @Volatile private var current: Run? = null
    @Volatile private var lastSession = Session(0, 0, 0)
    @Volatile private var stage: ProxyStage = ProxyStage.IDLE
    private val listener get() = current?.server
    private val discoverySocket get() = current?.discovery
    private val client get() = current?.client
    private val lastDiscoveryPeer get() = current?.discoveryPeer

    val isRunning: Boolean get() = listener?.isClosed == false
    val currentStage: ProxyStage get() = stage

    /**
     * Structured progress for the screen. Fires on relay threads, so the service posts it to the
     * main thread before it touches [GatewayRuntime].
     */
    @Volatile var stateListener: ((ProxyStage) -> Unit)? = null

    val listeningPort: Int get() = listener?.localPort ?: 0
    val discoveryPort: Int get() = discoverySocket?.localPort ?: 0
    val discoveryPeer: String? get() = lastDiscoveryPeer
    val dataPeer: String? get() = client?.let { "${it.inetAddress.hostAddress}:${it.port}" }

    fun session() = current?.session() ?: lastSession

    @Synchronized
    private fun emit(owner: Run?, next: ProxyStage) {
        if (current !== owner) return
        stage = next
        stateListener?.invoke(next)
    }

    /** Proxy listening ports plus the DiPlay-side source ports seen in traffic, for the development UI. */
    fun endpointSummary(): String? {
        val server = listener ?: return null
        if (server.isClosed) return null
        val discoveryPeer = lastDiscoveryPeer ?: "等待发现"
        val dataPeer = client?.let { "${it.inetAddress.hostAddress}:${it.port}" } ?: "等待连接"
        return "代理 TCP ${server.localPort} · UDP ${discoverySocket?.localPort ?: "-"}\n" +
            "DiPlay 发现 $discoveryPeer · 数据 $dataPeer"
    }

    @Synchronized
    fun start(device: BluetoothDevice) {
        check(listener == null) { "Gateway already running" }
        val mac = File(directory, "gateway-bluetooth-mac.txt").readText().trim()
        require(mac.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) && mac != "02:00:00:00:00:00") {
            "请先在“配对信息”中配置本机蓝牙地址"
        }
        val token = Protocol.DEVELOPMENT_TOKEN
        File(directory, "gateway-token.txt").writeText(token)
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .map { address -> networkInterface.name to address }
            }
        if (interfaces.isEmpty()) throw IOException("请先让本机连接 Wi-Fi，再启动代理")
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("0.0.0.0", 39877), 1)
        val owner = Run(server)
        current = owner
        log("phase=GATEWAY event=LISTENING bind=0.0.0.0 port=39877 transport=wifi-tcp token=private")
        emit(owner, ProxyStage.LISTENING)
        interfaces.forEach { (name, address) ->
            log("phase=GATEWAY event=INTERFACE name=$name address=${address.hostAddress}")
        }
        runCatching { startUdpDiscovery(owner, mac, server.localPort) }
            .onFailure { log("phase=DISCOVERY event=UDP_START_FAILED error=${it.javaClass.simpleName}") }
        val advertisement = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                log("phase=DISCOVERY event=REGISTERED service=${info.serviceName}")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                log("phase=DISCOVERY event=REGISTRATION_FAILED code=$code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        owner.registration = advertisement
        try {
            nsd.registerService(NsdServiceInfo().apply {
                serviceName = Protocol.serviceName(mac)
                serviceType = Protocol.SERVICE_TYPE
                port = server.localPort
                setAttribute("gateway", mac.uppercase())
                setAttribute("version", "1")
            }, NsdManager.PROTOCOL_DNS_SD, advertisement)
        } catch (error: Exception) {
            log("phase=DISCOVERY event=NSD_REGISTRATION_EXCEPTION error=${error.javaClass.simpleName}")
        }
        Thread({
            try {
                while (current === owner) {
                    val socket = server.accept()
                    synchronized(this) {
                        if (current !== owner) {
                            socket.close()
                            return@Thread
                        }
                        owner.client = socket
                    }
                    try { relay(owner, socket, device, token, mac) }
                    catch (_: EOFException) { /* A peer may close after its bounded verification. */ }
                    catch (error: Exception) {
                        if (current === owner) log("phase=GATEWAY result=FAILED error=${error.javaClass.simpleName} message=${error.message}")
                    } finally {
                        // Never touch another start's sockets, even if it has already accepted a peer.
                        runCatching { owner.bluetooth?.close() }
                        owner.bluetooth = null
                        runCatching { socket.close() }
                        owner.client = null
                    }
                }
            } catch (error: Exception) {
                if (current === owner) log("phase=GATEWAY result=ACCEPT_FAILED error=${error.javaClass.simpleName}")
            }
        }, "rfcomm-gateway").start()
    }

    private fun startUdpDiscovery(owner: Run, mac: String, tcpPort: Int) {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
        }
        owner.discovery = socket
        Thread({
            val buffer = ByteArray(256)
            while (current === owner && !socket.isClosed) {
                try {
                    val request = DatagramPacket(buffer, buffer.size)
                    socket.receive(request)
                    val requestedMac = Protocol.parseDiscoveryRequest(request.data, request.length) ?: continue
                    if (requestedMac != "*" && !requestedMac.equals(mac, ignoreCase = true)) continue
                    synchronized(this) {
                        if (current !== owner) return@Thread
                        owner.discoveryPeer = "${request.address.hostAddress}:${request.port}"
                    }
                    DatagramSocket().use { replySocket ->
                        replySocket.connect(request.address, request.port)
                        val response = Protocol.discoveryResponse(mac, tcpPort)
                        replySocket.send(DatagramPacket(response, response.size, request.address, request.port))
                    }
                    log("phase=DISCOVERY event=UDP_REPLY peer=${request.address.hostAddress}:${request.port}")
                    if (stage == ProxyStage.LISTENING) emit(owner, ProxyStage.DISCOVERED)
                } catch (error: Exception) {
                    if (current === owner && !socket.isClosed) {
                        log("phase=DISCOVERY event=UDP_ERROR error=${error.javaClass.simpleName}")
                    }
                }
            }
        }, "gateway-udp-discovery").start()
        log("phase=DISCOVERY event=UDP_LISTENING port=${Protocol.DISCOVERY_PORT}")
    }

    private fun relay(owner: Run, socket: Socket, device: BluetoothDevice, token: String, mac: String) {
        socket.tcpNoDelay = true
        socket.soTimeout = 10000
        val input = DataInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val hello = Protocol.read(input)
        if (hello.type != Protocol.HELLO || !MessageDigest.isEqual(hello.payload, token.encodeToByteArray())) {
            log("phase=GATEWAY event=AUTH_REJECTED peer=${socket.inetAddress.hostAddress}")
            Protocol.write(output, Protocol.ERROR, "Invalid token".encodeToByteArray())
            return
        }
        log("phase=GATEWAY event=AUTHENTICATED peer=${socket.inetAddress.hostAddress} transport=wifi-tcp")
        emit(owner, ProxyStage.AUTHENTICATED)
        Protocol.write(output, Protocol.HELLO, mac.encodeToByteArray())
        check(Protocol.read(input).type == Protocol.OPEN) { "Expected OPEN" }
        val bt = device.createRfcommSocketToServiceRecord(BluetoothProbe.IAP2_UUID)
        synchronized(this) {
            if (current !== owner || owner.client !== socket) {
                runCatching { bt.close() }
                throw IOException("Gateway cancelled")
            }
            owner.bluetooth = bt
        }
        val timer = Executors.newSingleThreadScheduledExecutor()
        val timeout = timer.schedule({ runCatching { bt.close() } }, 15, TimeUnit.SECONDS)
        try {
            log("phase=GATEWAY event=RFCOMM_CONNECTING")
            emit(owner, ProxyStage.RFCOMM_CONNECTING)
            bt.connect()
            timeout.cancel(false)
            if (current !== owner || owner.client !== socket) throw IOException("Gateway cancelled")
            Protocol.write(output, Protocol.OPEN_OK)
            socket.soTimeout = 0
            owner.bytesFromIphone.set(0)
            owner.bytesToIphone.set(0)
            owner.startedMs = SystemClock.elapsedRealtime()
            log("phase=GATEWAY event=RFCOMM_CONNECTED")
            emit(owner, ProxyStage.RELAYING)
            val reader = Thread({
                try {
                    val buffer = ByteArray(8192)
                    val source = bt.inputStream
                    while (!socket.isClosed) {
                        val count = source.read(buffer)
                        if (count < 0) { Protocol.write(output, Protocol.CLOSE); break }
                        if (count > 0) {
                            Protocol.write(output, Protocol.DATA, buffer.copyOf(count))
                            owner.bytesFromIphone.addAndGet(count.toLong())
                        }
                    }
                } catch (_: Exception) {
                    // Closing either side terminates both relay directions.
                } finally {
                    runCatching { socket.close() }; runCatching { bt.close() }
                }
            }, "gateway-bt-to-tcp").apply { start() }
            try {
                val destination = bt.outputStream
                while (!socket.isClosed) {
                    val frame = Protocol.read(input)
                    when (frame.type) {
                        Protocol.DATA -> {
                            destination.write(frame.payload)
                            owner.bytesToIphone.addAndGet(frame.payload.size.toLong())
                        }
                        Protocol.CLOSE -> break
                        else -> throw IOException("Unexpected relay frame")
                    }
                }
            } finally {
                runCatching { socket.close() }; runCatching { bt.close() }
                reader.join(1000)
                log("phase=GATEWAY event=SESSION_ENDED bytesFromIphone=${owner.bytesFromIphone.get()} bytesToIphone=${owner.bytesToIphone.get()}")
                if (current === owner && !owner.server.isClosed) emit(owner, ProxyStage.LISTENING)
            }
        } catch (error: Exception) {
            runCatching { Protocol.write(output, Protocol.ERROR, (error.javaClass.simpleName + ": " + error.message).take(512).encodeToByteArray()) }
            throw error
        } finally {
            timeout.cancel(false); timer.shutdownNow(); runCatching { bt.close() }
            if (owner.bluetooth === bt) owner.bluetooth = null
        }
    }

    @Synchronized
    fun close() {
        val owner = current
        current = null
        if (owner != null) {
            lastSession = owner.session()
            owner.registration?.let { advertisement -> runCatching { nsd.unregisterService(advertisement) } }
            runCatching { owner.server.close() }
            runCatching { owner.discovery?.close() }
            runCatching { owner.client?.close() }
            runCatching { owner.bluetooth?.close() }
        }
        log("phase=GATEWAY event=STOPPED")
        emit(null, ProxyStage.STOPPED)
    }
}
