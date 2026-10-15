package com.shilapi.xcertplay.pioneer

import android.content.Context
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

object PioneerLwipRuntime {
    @Volatile var active = false
    @Volatile var summary = "idle"
    @Volatile var firstTxReady = false
    @Volatile var tcpAccepted = false // Only the advertised AirPlay control port.
    @Volatile var controlPort = 0
    @Volatile var controlAcceptedNs = 0L
    @Volatile var startedNs = 0L
    @Volatile var mfiNs = 0L
    private val timeline = java.util.ArrayDeque<String>()
    @Synchronized fun timeline(): List<String> = timeline.toList()
    @Volatile var sessionId = 0L
        private set
    @Volatile var attemptId = 0
    @Volatile var startRequestedNs = 0L
        private set
    @Volatile var setupNs = 0L
        private set
    @Volatile var firstFrameNs = 0L
        private set
    private val ids = AtomicLong()
    val peerUdpPorts = ArrayBlockingQueue<Int>(128)
    val portCommands = ArrayBlockingQueue<PortCommand>(256)
    class PortCommand(val session: Long, val port: Int, val udp: Boolean, val add: Boolean) {
        val done = CountDownLatch(1)
        val createdNs = System.nanoTime()
        @Volatile var cancelled = false
        @Volatile var success = false
    }
    fun enabled(context: Context) = PioneerUsbPolicy.enabled(context) &&
        context.getSharedPreferences("pioneer-debug", Context.MODE_PRIVATE).getString("network_backend", "LWIP") != "VPN"
    @Synchronized fun begin(): Long {
        check(!active) { "Previous lwIP owner is still active" }
        sessionId = ids.incrementAndGet(); attemptId = 1
        firstTxReady = false; tcpAccepted = false; controlPort = 0
        startRequestedNs = 0; setupNs = 0; firstFrameNs = 0; controlAcceptedNs = 0
        timeline.clear(); startedNs = System.nanoTime(); mfiNs = 0
        active = true
        return sessionId
    }
    @Synchronized fun end(id: Long) {
        if (sessionId != id) return
        active = false; firstTxReady = false; tcpAccepted = false
        summary = "stopped sessionId=$id"
        peerUdpPorts.clear()
        while (true) { val c = portCommands.poll() ?: break; c.done.countDown() }
    }
    fun milestone(id: Long, stage: String, atNs: Long = System.nanoTime()): String? = synchronized(this) {
        if (!active || id != sessionId) return null
        val now = atNs
        when (stage) {
            "mfi-complete" -> if (mfiNs == 0L) mfiNs = now
            "start-request" -> if (startRequestedNs == 0L) startRequestedNs = now
            "airplay-control-accepted" -> if (controlAcceptedNs == 0L) controlAcceptedNs = now
            "setup" -> if (setupNs == 0L) setupNs = now
            "first-frame-release" -> if (firstFrameNs == 0L) firstFrameNs = now
        }
        PioneerConnectionTrace.mark(stage, "source=lwip sourceAtNs=$now")
        val line = "LWIP_STAGE runId=${PioneerConnectionTrace.runId} sessionId=$id attemptId=$attemptId stage=$stage atNs=$now"
        if (timeline.size >= 32) timeline.removeFirst()
        timeline.addLast(line)
        line
    }
    /** Lease bound to the owning USB attachment. Ack must precede advertising the port. */
    fun registerPort(port: Int, udp: Boolean, expectedSession: Long = sessionId): Closeable {
        if (!active) return Closeable { }
        val id = sessionId
        check(id == expectedSession) { "Stale lwIP port registration sessionId=$expectedSession current=$id" }
        val command = PortCommand(id, port, udp, true)
        check(port in 1..65535 && portCommands.offer(command)) { "lwIP port registration queue full" }
        if (!command.done.await(2, TimeUnit.SECONDS) || !command.success || !active || sessionId != id) {
            synchronized(command) {
                command.cancelled = true
                if (command.success && active && sessionId == id) portCommands.offer(PortCommand(id,port,udp,false))
            }
            error("lwIP port registration failed sessionId=$id port=$port udp=$udp")
        }
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        return Closeable {
            if (released.compareAndSet(false, true) && active && sessionId == id) {
                val remove = PortCommand(id, port, udp, false)
                check(portCommands.offer(remove)) { "lwIP port removal queue full" }
                // Close may run on a teardown thread. No wait for owner callbacks here.
            }
        }
    }
    fun registerPeerUdp(address: InetAddress, port: Int, expectedSession: Long = sessionId) {
        if (active && expectedSession == sessionId && address.hostAddress == "127.0.0.2" && port in 1..65535)
            check(peerUdpPorts.offer(port)) { "lwIP peer UDP registration queue full" }
    }
}
