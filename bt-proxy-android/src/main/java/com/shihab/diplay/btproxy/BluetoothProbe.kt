package com.shihab.diplay.btproxy

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.Iap2LinkConfig
import com.shilapi.xcertplay.transport.Iap2LinkEngine
import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** RFCOMM observation or diagnostic iAP2 link handshake. No identity or authentication. */
class BluetoothProbe(private val log: (String) -> Unit) {
    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var current: Attempt? = null

    private class Attempt(val id: Long, val socket: BluetoothSocket) {
        val ended = AtomicBoolean()
        var connected = false // guarded by lock
        val bytes = AtomicLong()
        val sent = AtomicLong()
        val started = SystemClock.elapsedRealtime()
    }

    fun connect(device: BluetoothDevice, handshake: Boolean = false) {
        disconnect()
        val attempt = try {
            Attempt(SystemClock.elapsedRealtime(), device.createRfcommSocketToServiceRecord(IAP2_UUID))
        } catch (error: Exception) {
            log("phase=RFCOMM result=CREATE_FAILED ${error.javaClass.simpleName}: ${error.message}")
            return
        }
        synchronized(lock) { current = attempt }
        log("session=${attempt.id} phase=RFCOMM event=CONNECTING uuid=$IAP2_UUID timeoutMs=15000")
        val deadline = timer.schedule({
            synchronized(lock) {
                if (!attempt.connected) finish(attempt, "CONNECT_TIMEOUT")
            }
        }, 15, TimeUnit.SECONDS)
        Thread({
            try {
                attempt.socket.connect()
                synchronized(lock) {
                    if (attempt.ended.get()) return@Thread
                    attempt.connected = true
                }
                deadline.cancel(false)
                log("session=${attempt.id} phase=RFCOMM result=CONNECTED elapsedMs=${elapsed(attempt)} " +
                    "socketConnected=${attempt.socket.isConnected} protocol=UNVERIFIED")
                if (handshake) {
                    val handshakeDeadline = timer.schedule({
                        finish(attempt, "IAP2_WATCHDOG_TIMEOUT")
                    }, 25, TimeUnit.SECONDS)
                    try { runHandshake(attempt) }
                    finally { handshakeDeadline.cancel(false) }
                    return@Thread
                }
                val observation = timer.schedule({
                    finish(attempt, if (attempt.bytes.get() == 0L) "CONNECTED_NO_DATA" else "RECEIVED_DATA_PROTOCOL_UNVERIFIED")
                }, 10, TimeUnit.SECONDS)
                try {
                    val buffer = ByteArray(4096)
                    val input = attempt.socket.inputStream
                    while (!attempt.ended.get()) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            finish(attempt, "REMOTE_EOF")
                            break
                        }
                        if (count > 0) {
                            val total = attempt.bytes.addAndGet(count.toLong())
                            log("session=${attempt.id} phase=RFCOMM event=RECEIVED bytes=$count bytesIn=$total bytesOut=0")
                        }
                    }
                } finally {
                    observation.cancel(false)
                }
            } catch (error: Exception) {
                finish(attempt, "${if (attempt.connected) "READ_FAILED" else "CONNECT_FAILED"} " +
                    "errorClass=${error.javaClass.simpleName} message=${error.message}")
            } finally {
                deadline.cancel(false)
                runCatching { attempt.socket.close() }
            }
        }, "rfcomm-probe-${attempt.id}").start()
    }

    fun disconnect() {
        synchronized(lock) { current }?.let { finish(it, "CANCELLED") }
    }

    private fun runHandshake(attempt: Attempt) {
        val stream = BluetoothRfcommDuplexStream(attempt.socket) {
            log("session=${attempt.id} phase=STREAM $it")
        }
        // Exactly the wireless link configuration used by Iap2LinkChannel.openWireless.
        val engine = Iap2LinkEngine(Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2))
        val csm = Iap2CsmFramer()
        var ready = false
        var controlReceived = false
        var previousState = engine.state()
        val end = SystemClock.elapsedRealtime() + 20000
        fun flush() {
            val output = engine.takeOutput()
            if (output.isNotEmpty()) {
                stream.send(output)
                attempt.sent.addAndGet(output.size.toLong())
                log("session=${attempt.id} phase=IAP2 event=TX bytes=${output.size} bytesOut=${attempt.sent.get()}")
            }
        }
        log("session=${attempt.id} phase=IAP2 event=STARTED mode=wireless timeoutMs=20000")
        try {
            engine.start(wiredInitiator = false, nowMillis = SystemClock.elapsedRealtime())
            while (!attempt.ended.get() && SystemClock.elapsedRealtime() < end) {
                engine.advanceTime(SystemClock.elapsedRealtime())
                flush()
                if (engine.state() != previousState) {
                    previousState = engine.state()
                    log("session=${attempt.id} phase=IAP2 event=STATE state=$previousState")
                }
                while (true) {
                    when (val event = engine.pollEvent() ?: break) {
                        is Iap2LinkEngine.Event.Writable -> if (event.value && !ready) {
                            ready = true
                            val peer = engine.peerSynchronization()
                            log("session=${attempt.id} phase=IAP2 result=LINK_READY " +
                                "peerMaxLength=${peer.maxLength} peerSessions=${peer.sessions}")
                        }
                        is Iap2LinkEngine.Event.Control -> {
                            for (frame in csm.offer(event.bytes)) {
                                controlReceived = true
                                val name = when (frame.messageId) {
                                    0x1d00 -> "StartIdentification"
                                    0x1d02 -> "IdentificationAccepted"
                                    0x1d03 -> "IdentificationRejected"
                                    0xaa00 -> "RequestAuthenticationCertificate"
                                    0xaa02 -> "RequestAuthenticationChallengeResponse"
                                    0xaa04 -> "AuthenticationFailed"
                                    0xaa05 -> "AuthenticationSucceeded"
                                    else -> "Unknown"
                                }
                                log("session=${attempt.id} phase=CSM event=RECEIVED " +
                                    "messageId=0x${frame.messageId.toString(16)} name=$name")
                            }
                        }
                        is Iap2LinkEngine.Event.Session -> log("session=${attempt.id} phase=IAP2 " +
                            "event=SESSION sessionId=${event.sessionId} bytes=${event.bytes.size}")
                        is Iap2LinkEngine.Event.Dead -> {
                            finish(attempt, "IAP2_LINK_DEAD reason=${event.reason}")
                            return
                        }
                    }
                }
                // Send generated ACKs before ending this diagnostic session.
                flush()
                if (ready && controlReceived) {
                    log("session=${attempt.id} phase=IAP2 result=BIDIRECTIONAL_LINK_VERIFIED " +
                        "identification=NOT_SENT authentication=NOT_ATTEMPTED")
                    finish(attempt, "IAP2_LINK_AND_CSM_VERIFIED")
                    return
                }
                val received = stream.recv(8192, 100)
                when {
                    received == null -> Unit
                    received.isEmpty() -> engine.feedEof()
                    else -> {
                        attempt.bytes.addAndGet(received.size.toLong())
                        log("session=${attempt.id} phase=IAP2 event=RX bytes=${received.size} bytesIn=${attempt.bytes.get()}")
                        engine.feed(received, SystemClock.elapsedRealtime())
                    }
                }
            }
            finish(attempt, if (ready) "IAP2_LINK_READY_NO_CSM" else "IAP2_HANDSHAKE_TIMEOUT state=${engine.state()}")
        } finally {
            runCatching { stream.close() }
        }
    }

    fun close() {
        disconnect()
        timer.shutdownNow()
    }

    private fun finish(attempt: Attempt, reason: String) {
        if (!attempt.ended.compareAndSet(false, true)) return
        runCatching { attempt.socket.close() }
        synchronized(lock) { if (current === attempt) current = null }
        log("session=${attempt.id} phase=RFCOMM result=$reason elapsedMs=${elapsed(attempt)} " +
            "bytesIn=${attempt.bytes.get()} bytesOut=${attempt.sent.get()}")
    }

    private fun elapsed(attempt: Attempt) = SystemClock.elapsedRealtime() - attempt.started

    companion object {
        // CarPlayController.IAP2_IPHONE_UUID; do not substitute the generic SPP UUID.
        val IAP2_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    }
}
