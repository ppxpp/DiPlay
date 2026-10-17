package com.shilapi.xcertplay.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class RemoteGatewayConnectionTest {
    @Test fun closeCancelsSilentHelloAndOpenHandshake() {
        for (replyToHello in listOf(false, true)) {
            ServerSocket(0).use { server ->
                val waiting = CountDownLatch(1)
                val release = CountDownLatch(1)
                val peer = thread(isDaemon = true) {
                    server.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        RemoteRfcommProtocol.read(input)
                        if (replyToHello) {
                            RemoteRfcommProtocol.write(DataOutputStream(socket.getOutputStream()),
                                RemoteRfcommProtocol.HELLO, "12:34:56:78:9A:BC".encodeToByteArray())
                            RemoteRfcommProtocol.read(input)
                        }
                        waiting.countDown()
                        release.await(2, TimeUnit.SECONDS)
                    }
                }
                val connection = RemoteGatewayConnection()
                val failure = AtomicReference<Throwable>()
                val worker = thread(isDaemon = true) {
                    try {
                        RemoteRfcommDuplexStream.connect("127.0.0.1", server.localPort,
                            "a".repeat(64), connection = connection).close()
                    } catch (error: Throwable) { failure.set(error) }
                }
                try {
                    assertTrue(waiting.await(1, TimeUnit.SECONDS))
                    connection.close()
                    worker.join(1000)
                    assertFalse("Cancellation must unblock network read", worker.isAlive)
                    assertTrue(failure.get() is IOException)
                } finally {
                    connection.close(); release.countDown(); peer.join(1000)
                }
            }
        }
    }

    @Test fun resourceCreatedAfterCancellationIsClosedImmediately() {
        val connection = RemoteGatewayConnection()
        connection.close()
        var closed = false
        try {
            connection.track(java.io.Closeable { closed = true })
            fail("Cancelled connection accepted a resource")
        } catch (_: IOException) { assertTrue(closed) }
    }
}
