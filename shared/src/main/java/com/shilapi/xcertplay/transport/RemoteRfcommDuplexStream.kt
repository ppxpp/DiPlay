package com.shilapi.xcertplay.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/** Remote RFCOMM byte stream; preserves the existing bounded blocking receive semantics. */
class RemoteRfcommDuplexStream private constructor(
    private val socket: Socket,
    val gatewayBluetoothMac: String,
    input: DataInputStream,
    output: DataOutputStream,
    private val onDiagnostic: (String) -> Unit,
) : BlockingDuplexByteStream {
    private val received = AtomicLong()
    private val sent = AtomicLong()
    val bytesReceived: Long get() = received.get()
    val bytesSent: Long get() = sent.get()

    private val delegate = BluetoothRfcommDuplexStream(object : BluetoothRfcommSocketAccess {
        override fun inputStream(): InputStream = object : InputStream() {
            private var chunk = byteArrayOf()
            private var offset = 0
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
            }
            override fun read(dst: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (offset == chunk.size) {
                    val frame = RemoteRfcommProtocol.read(input)
                    when (frame.type) {
                        RemoteRfcommProtocol.DATA -> { chunk = frame.payload; offset = 0 }
                        RemoteRfcommProtocol.CLOSE -> return -1
                        RemoteRfcommProtocol.ERROR -> throw IOException("Gateway: ${frame.payload.decodeToString()}")
                        else -> throw IOException("Unexpected gateway frame ${frame.type}")
                    }
                }
                val count = minOf(len, chunk.size - offset)
                chunk.copyInto(dst, off, offset, offset + count)
                offset += count
                received.addAndGet(count.toLong())
                return count
            }
        }
        override fun outputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()))
            override fun write(src: ByteArray, off: Int, len: Int) {
                var position = off
                while (position < off + len) {
                    val end = minOf(off + len, position + RemoteRfcommProtocol.MAX_PAYLOAD)
                    RemoteRfcommProtocol.write(output, RemoteRfcommProtocol.DATA, src.copyOfRange(position, end))
                    sent.addAndGet((end - position).toLong())
                    position = end
                }
            }
        }
        override fun close() = socket.close()
    }, onDiagnostic)

    override fun send(data: ByteArray) = delegate.send(data)
    override fun recv(maxBytes: Int, timeoutMillis: Long) = delegate.recv(maxBytes, timeoutMillis)
    override fun close() {
        delegate.close()
        onDiagnostic("Remote RFCOMM closed bytesIn=$bytesReceived bytesOut=$bytesSent")
    }

    companion object {
        fun connect(host: String, port: Int, token: String, onDiagnostic: (String) -> Unit = {}): RemoteRfcommDuplexStream {
            require(host.isNotBlank() && port in 1..65535 && token.length in 16..256)
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), 10000)
                socket.tcpNoDelay = true
                socket.soTimeout = 20000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                RemoteRfcommProtocol.write(output, RemoteRfcommProtocol.HELLO, token.encodeToByteArray())
                val hello = RemoteRfcommProtocol.read(input)
                if (hello.type != RemoteRfcommProtocol.HELLO) throw IOException("Gateway authentication rejected")
                val mac = hello.payload.decodeToString()
                require(mac.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) && mac != "02:00:00:00:00:00") {
                    "Gateway must provide its real Bluetooth address"
                }
                RemoteRfcommProtocol.write(output, RemoteRfcommProtocol.OPEN)
                val opened = RemoteRfcommProtocol.read(input)
                if (opened.type != RemoteRfcommProtocol.OPEN_OK) throw IOException("Gateway open failed: ${opened.payload.decodeToString()}")
                socket.soTimeout = 0
                onDiagnostic("Remote RFCOMM connected gateway=$host:$port")
                return RemoteRfcommDuplexStream(socket, mac, input, output, onDiagnostic)
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        }
    }
}
