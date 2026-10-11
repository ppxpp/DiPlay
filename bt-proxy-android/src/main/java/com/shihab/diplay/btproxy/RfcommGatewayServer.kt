package com.shihab.diplay.btproxy

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.shilapi.xcertplay.transport.RemoteRfcommProtocol as Protocol
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.EOFException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One authenticated peer at a time. Android A never interprets forwarded protocol payloads. */
class RfcommGatewayServer(context: Context, private val directory: File, private val log: (String) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var listener: ServerSocket? = null
    @Volatile private var client: Socket? = null
    @Volatile private var bluetooth: BluetoothSocket? = null

    fun start(device: BluetoothDevice) {
        check(listener == null) { "Gateway already running" }
        val mac = File(directory, "gateway-bluetooth-mac.txt").readText().trim()
        require(mac.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) && mac != "02:00:00:00:00:00") {
            "Configure the real Android A Bluetooth address first"
        }
        val tokenFile = File(directory, "gateway-token.txt")
        if (!tokenFile.exists()) {
            tokenFile.writeText(ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        val token = tokenFile.readText().trim()
        val address = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?: throw IOException("Connect Android A to Wi-Fi before starting the gateway")
        val server = ServerSocket(39877, 1, address)
        listener = server
        log("phase=GATEWAY event=LISTENING bind=${address.hostAddress} port=39877 transport=wifi-tcp token=private")
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
        registration = advertisement
        try {
            nsd.registerService(NsdServiceInfo().apply {
                serviceName = Protocol.serviceName(mac)
                serviceType = Protocol.SERVICE_TYPE
                port = server.localPort
                setAttribute("gateway", mac.uppercase())
                setAttribute("version", "1")
            }, NsdManager.PROTOCOL_DNS_SD, advertisement)
        } catch (error: Exception) {
            close()
            throw error
        }
        Thread({
            try {
                while (listener === server) {
                    val socket = server.accept()
                    client = socket
                    try { relay(socket, device, token, mac) }
                    catch (_: EOFException) { /* A peer may close after its bounded verification. */ }
                    catch (error: Exception) {
                        if (listener === server) log("phase=GATEWAY result=FAILED error=${error.javaClass.simpleName} message=${error.message}")
                    } finally {
                        runCatching { bluetooth?.close() }; bluetooth = null
                        runCatching { socket.close() }; client = null
                    }
                }
            } catch (error: Exception) {
                if (listener === server) log("phase=GATEWAY result=ACCEPT_FAILED error=${error.javaClass.simpleName}")
            }
        }, "rfcomm-gateway").start()
    }

    private fun relay(socket: Socket, device: BluetoothDevice, token: String, mac: String) {
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
        Protocol.write(output, Protocol.HELLO, mac.encodeToByteArray())
        check(Protocol.read(input).type == Protocol.OPEN) { "Expected OPEN" }
        val bt = device.createRfcommSocketToServiceRecord(BluetoothProbe.IAP2_UUID)
        bluetooth = bt
        val timer = Executors.newSingleThreadScheduledExecutor()
        val timeout = timer.schedule({ runCatching { bt.close() } }, 15, TimeUnit.SECONDS)
        try {
            log("phase=GATEWAY event=RFCOMM_CONNECTING")
            bt.connect()
            timeout.cancel(false)
            if (client !== socket || listener == null) throw IOException("Gateway cancelled")
            Protocol.write(output, Protocol.OPEN_OK)
            socket.soTimeout = 0
            val bytesIn = AtomicLong()
            val bytesOut = AtomicLong()
            log("phase=GATEWAY event=RFCOMM_CONNECTED")
            val reader = Thread({
                try {
                    val buffer = ByteArray(8192)
                    val source = bt.inputStream
                    while (!socket.isClosed) {
                        val count = source.read(buffer)
                        if (count < 0) { Protocol.write(output, Protocol.CLOSE); break }
                        if (count > 0) {
                            Protocol.write(output, Protocol.DATA, buffer.copyOf(count))
                            bytesIn.addAndGet(count.toLong())
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
                            bytesOut.addAndGet(frame.payload.size.toLong())
                        }
                        Protocol.CLOSE -> break
                        else -> throw IOException("Unexpected relay frame")
                    }
                }
            } finally {
                runCatching { socket.close() }; runCatching { bt.close() }
                reader.join(1000)
                log("phase=GATEWAY event=SESSION_ENDED bytesFromIphone=${bytesIn.get()} bytesToIphone=${bytesOut.get()}")
            }
        } catch (error: Exception) {
            runCatching { Protocol.write(output, Protocol.ERROR, (error.javaClass.simpleName + ": " + error.message).take(512).encodeToByteArray()) }
            throw error
        } finally {
            timeout.cancel(false); timer.shutdownNow(); runCatching { bt.close() }
        }
    }

    fun close() {
        val advertisement = registration; registration = null
        if (advertisement != null) runCatching { nsd.unregisterService(advertisement) }
        val server = listener; listener = null
        runCatching { server?.close() }
        runCatching { client?.close() }
        runCatching { bluetooth?.close() }
        log("phase=GATEWAY event=STOPPED")
    }
}
