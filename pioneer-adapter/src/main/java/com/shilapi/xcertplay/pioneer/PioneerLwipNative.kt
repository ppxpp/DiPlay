package com.shilapi.xcertplay.pioneer

/** Single owner thread calls all native methods; raw lwIP never runs concurrently. */
object PioneerLwipNative {
    init { System.loadLibrary("pioneer_lwip") }
    external fun create(mac: ByteArray, address: ByteArray, debugPort: Int, diagnosticPath: String)
    external fun diagnostics(enabled: Boolean)
    external fun clockNanos(): Long
    external fun oldestOutputNanos(): Long
    external fun input(frame: ByteArray)
    external fun poll()
    external fun output(): ByteArray?
    external fun messages(): String
    external fun peerUdp(port: Int)
    external fun port(port: Int, udp: Boolean, add: Boolean): Boolean
    external fun close()
}
