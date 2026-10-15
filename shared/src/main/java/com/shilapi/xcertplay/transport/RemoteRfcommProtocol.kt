package com.shilapi.xcertplay.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Platform-neutral v1 TCP framing. Payloads are bounded raw RFCOMM chunks. */
object RemoteRfcommProtocol {
    // Temporary development credential shared by the proxy and DiPlay installations.
    const val DEVELOPMENT_TOKEN = "123456"
    // Default pairing identity used when no pairing file exists yet.
    const val DEVELOPMENT_PROXY_BLUETOOTH_MAC = "94:7B:AE:88:66:DE"
    fun validToken(token: String) = token == DEVELOPMENT_TOKEN || token.length in 16..256

    const val SERVICE_TYPE = "_diplay-proxy._tcp."
    fun serviceName(bluetoothMac: String) = "DiPlay-Proxy-${bluetoothMac.replace(":", "").uppercase()}"
    const val DISCOVERY_PORT = 39876
    private const val DISCOVERY_REQUEST_PREFIX = "DIPLAY-PROXY-DISCOVER/1\n"
    private const val DISCOVERY_RESPONSE_PREFIX = "DIPLAY-PROXY-HERE/1\n"

    data class DiscoveryResponse(val gatewayBluetoothMac: String, val tcpPort: Int)

    fun discoveryRequest(gatewayBluetoothMac: String): ByteArray =
        (DISCOVERY_REQUEST_PREFIX + gatewayBluetoothMac.uppercase()).encodeToByteArray()

    fun parseDiscoveryRequest(bytes: ByteArray, length: Int): String? {
        val message = bytes.decodeToString(0, length)
        if (!message.startsWith(DISCOVERY_REQUEST_PREFIX)) return null
        return message.removePrefix(DISCOVERY_REQUEST_PREFIX).trim()
            .takeIf { it == "*" || it.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) }
    }

    fun discoveryResponse(gatewayBluetoothMac: String, tcpPort: Int): ByteArray {
        require(tcpPort in 1..65535)
        return (DISCOVERY_RESPONSE_PREFIX + gatewayBluetoothMac.uppercase() + "\n" + tcpPort)
            .encodeToByteArray()
    }

    fun parseDiscoveryResponse(bytes: ByteArray, length: Int): DiscoveryResponse? {
        val message = bytes.decodeToString(0, length)
        if (!message.startsWith(DISCOVERY_RESPONSE_PREFIX)) return null
        val fields = message.removePrefix(DISCOVERY_RESPONSE_PREFIX).trim().split('\n')
        if (fields.size != 2 || !fields[0].matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return null
        val port = fields[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return DiscoveryResponse(fields[0], port)
    }

    const val HELLO = 1
    const val OPEN = 2
    const val OPEN_OK = 3
    const val DATA = 4
    const val CLOSE = 5
    const val ERROR = 6
    const val MAX_PAYLOAD = 65536
    private const val MAGIC = 0x43504254 // CPBT
    data class Frame(val type: Int, val payload: ByteArray)

    fun read(input: DataInputStream): Frame {
        if (input.readInt() != MAGIC || input.readUnsignedByte() != 1) throw IOException("Invalid gateway header")
        val type = input.readUnsignedByte()
        val length = input.readInt()
        if (type !in HELLO..ERROR || length !in 0..MAX_PAYLOAD) throw IOException("Invalid gateway frame")
        return Frame(type, ByteArray(length).also(input::readFully))
    }

    fun write(output: DataOutputStream, type: Int, payload: ByteArray = byteArrayOf()) {
        require(type in HELLO..ERROR && payload.size <= MAX_PAYLOAD)
        synchronized(output) {
            output.writeInt(MAGIC)
            output.writeByte(1)
            output.writeByte(type)
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }
    }
}
