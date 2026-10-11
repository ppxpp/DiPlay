package com.shilapi.xcertplay.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Platform-neutral v1 TCP framing. Payloads are bounded raw RFCOMM chunks. */
object RemoteRfcommProtocol {
    const val SERVICE_TYPE = "_diplay-proxy._tcp."
    fun serviceName(bluetoothMac: String) = "DiPlay-Proxy-${bluetoothMac.replace(":", "").uppercase()}"
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
