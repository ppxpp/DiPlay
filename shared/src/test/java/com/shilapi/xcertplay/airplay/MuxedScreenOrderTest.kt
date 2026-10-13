package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pinned mux hands screen sockets to stream claims strictly in admission order, and claims
 * complete in ticket order even when their threads start out of order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MuxedScreenOrderTest {
    @Test
    fun claimsTakeAdmittedSocketsInOrderAndGatedTicketTimesOut() {
        val session = session()
        val (clientA, serverA) = connectedPair()
        val (clientB, serverB) = connectedPair()
        try {
            assertEquals(0, session.nextMuxScreenTicket())
            assertEquals(1, session.nextMuxScreenTicket())

            enqueue(session, serverA)
            enqueue(session, serverB)

            // Ticket 1 cannot overtake ticket 0: it times out without consuming the queue.
            assertNull(session.claimMuxedScreenConnection(1, 150))

            val first = session.claimMuxedScreenConnection(0, 1_000)
            assertSame(serverA, first?.socket)

            val second = session.claimMuxedScreenConnection(1, 1_000)
            assertSame(serverB, second?.socket)
        } finally {
            session.close()
            clientA.close()
            clientB.close()
            serverA.close()
            serverB.close()
        }
    }

    @Test
    fun claimOnClosedSessionReturnsNull() {
        val session = session()
        session.close()
        assertNull(session.claimMuxedScreenConnection(0, 100))
    }

    private fun enqueue(session: AirPlaySession, socket: Socket) {
        AirPlaySession::class.java
            .getDeclaredMethod("enqueueMuxedScreenConnection", Socket::class.java)
            .apply { isAccessible = true }
            .invoke(session, socket)
    }

    private fun connectedPair(): Pair<Socket, Socket> {
        val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        try {
            val client = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            return client to listener.accept()
        } finally {
            listener.close()
        }
    }

    private fun session(): AirPlaySession = AirPlaySession(
        socket = object : Socket() {
            override fun getRemoteSocketAddress(): SocketAddress =
                InetSocketAddress(InetAddress.getLoopbackAddress(), 1234)
        },
        config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            pinInboundPorts = true,
        ),
        identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
        listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
    )
}
