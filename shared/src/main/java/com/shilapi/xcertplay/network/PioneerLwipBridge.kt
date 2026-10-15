package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime
import com.shilapi.xcertplay.pioneer.PioneerLwipNative
import com.shilapi.xcertplay.pioneer.PioneerLwipRuntime
import com.shilapi.xcertplay.pioneer.PioneerTiming
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Only owner calls raw lwIP. The writer owns immutable frames until completion, in FIFO order. */
internal class PioneerLwipBridge(
    private val ncm: NcmUsbBridge,
    private val mac: ByteArray,
    private val address: ByteArray,
    private val diagnosticPath: String,
    private val log: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    private data class Output(val id: Long, val frame: ByteArray, val queuedNs: Long)
    private data class Completion(val packet: Output, val result: NcmUsbBridge.WriteResult, val retries: Int)
    private val running = AtomicBoolean(false)
    private val ready = CountDownLatch(1)
    private val output = ArrayBlockingQueue<Output>(64)
    private val completed = ArrayBlockingQueue<Completion>(64)
    private val queuedBytes = AtomicLong()
    private val txCount = AtomicLong()
    private val txBlocks = AtomicLong()
    private val retryCount = AtomicLong()
    private val writerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    private val rebuild = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>()
    @Volatile private var rebuildOk = false
    @Volatile private var startupFailure: Throwable? = null
    @Volatile private var inFlight: Output? = null
    private var session = 0L
    private var worker: Thread? = null
    private var writer: Thread? = null
    private val loopTiming = PioneerTiming("bridge-loop")
    private val inputTiming = PioneerTiming("lwip-input")
    private val pollTiming = PioneerTiming("lwip-poll")
    private val queueTiming = PioneerTiming("usb-tx-queue")
    private val buildTiming = PioneerTiming("ntb-build")
    private val writeTiming = PioneerTiming("usb-write")
    fun start() {
        check(ownerLease.compareAndSet(false, true)) { "Previous lwIP owner has not exited" }
        check(running.compareAndSet(false, true))
        worker = Thread({
            try {
                session = PioneerLwipRuntime.begin()
                com.shilapi.xcertplay.pioneer.PioneerDiagnostics.clear()
                PioneerLwipNative.create(mac, address, PioneerDebugRuntime.port, diagnosticPath)
                val clockBefore = System.nanoTime()
                val nativeClock = PioneerLwipNative.clockNanos()
                val clockAfter = System.nanoTime()
                log("LWIP_CLOCK sessionId=$session sameDomain=${nativeClock in clockBefore..clockAfter} javaBeforeNs=$clockBefore nativeNs=$nativeClock javaAfterNs=$clockAfter")
                writer = Thread(::writeLoop, "pioneer-ncm-writer").apply { isDaemon = true; start() }
                PioneerLwipRuntime.summary = "running sessionId=$session"
                ready.countDown()
                var rx = 0L; var requestId = 0L; var peakBytes = 0L
                var lastSummary = System.nanoTime()
                var pending: Output? = null
                var diagnosticsEnabled: Boolean? = null
                while (running.get()) {
                    val started = System.nanoTime()
                    val diagnostics = com.shilapi.xcertplay.pioneer.PioneerDiagnostics.enabled
                    if (diagnosticsEnabled != diagnostics) {
                        PioneerLwipNative.diagnostics(diagnostics)
                        diagnosticsEnabled = diagnostics
                    }
                    writerFailure.get()?.let { throw it }
                    rebuild.get()?.let { latch ->
                        rebuildOk = ncm.rebuildDataInterface()
                        PioneerLwipRuntime.attemptId++
                        rebuild.compareAndSet(latch, null); latch.countDown()
                        log("LWIP_RECOVERY sessionId=$session attemptId=${PioneerLwipRuntime.attemptId} interfaceRebuilt=$rebuildOk")
                    }
                    // Balanced batches: <=32 frames, <=64KiB, <=2ms per RX/TX turn.
                    var bytes = 0; var frames = 0
                    val receiveStart = System.nanoTime()
                    while (frames < 32 && bytes < 65536 && System.nanoTime() - receiveStart < 2_000_000) {
                        val frame = ncm.recv(1) ?: break
                        val before = System.nanoTime(); PioneerLwipNative.input(frame)
                        inputTiming.record(System.nanoTime() - before)
                        rx++; frames++; bytes += frame.size
                    }
                    repeat(16) {
                        PioneerLwipRuntime.portCommands.poll()?.let { c ->
                            synchronized(c) {
                                c.success = !c.cancelled && c.session == session && PioneerLwipNative.port(c.port, c.udp, c.add)
                                com.shilapi.xcertplay.pioneer.PioneerDiagnostics.record(session, "port-registration", System.nanoTime()-c.createdNs, !c.success)
                                c.done.countDown()
                            }
                        }
                        PioneerLwipRuntime.peerUdpPorts.poll()?.let { PioneerLwipNative.peerUdp(it) }
                    }
                    val beforePoll = System.nanoTime(); PioneerLwipNative.poll()
                    pollTiming.record(System.nanoTime() - beforePoll)
                    val sending = System.nanoTime(); bytes = 0
                    for (i in 0 until 32) {
                        if (output.remainingCapacity() == 0 || queuedBytes.get() >= 128 * 1024 ||
                            bytes >= 65536 || System.nanoTime() - sending > 2_000_000) break
                        val packet = pending ?: PioneerLwipNative.output()?.let { Output(++requestId, it, System.nanoTime()) } ?: break
                        // Count before publication; the consumer cannot make the byte counter negative.
                        queuedBytes.addAndGet(packet.frame.size.toLong())
                        if (!output.offer(packet)) { queuedBytes.addAndGet(-packet.frame.size.toLong()); pending = packet; break }
                        pending = null; bytes += packet.frame.size
                    }
                    while (true) {
                        val c = completed.poll() ?: break
                        if (!PioneerLwipRuntime.firstTxReady) {
                            PioneerLwipRuntime.firstTxReady = true
                            PioneerLwipRuntime.milestone(session,"usb-first-complete")?.let(log)
                            log("LWIP_USB_COMPLETE sessionId=$session requestId=${c.packet.id} bytes=${c.result.expected} retries=${c.retries}")
                        }
                    }
                    PioneerLwipNative.messages().lineSequence().filter { it.isNotBlank() }.forEach {
                        // Native acceptance is transport-only. Java accept marks the advertised business socket.
                        log("sessionId=$session attemptId=${PioneerLwipRuntime.attemptId} $it")
                    }
                    checkStageDeadlines()
                    peakBytes = maxOf(peakBytes, queuedBytes.get())
                    loopTiming.record(System.nanoTime() - started)
                    if (System.nanoTime() - lastSummary >= 5_000_000_000L) {
                        val oldest = (inFlight ?: output.peek())?.let { (System.nanoTime() - it.queuedNs) / 1_000_000 } ?: 0
                        PioneerLwipRuntime.summary = "sessionId=$session attemptId=${PioneerLwipRuntime.attemptId} framesRx=$rx framesTxCompleted=${txCount.get()} txBlocks=${txBlocks.get()} txBatchMax=${PioneerDebugRuntime.usbTxBatchFrames} outRetries=${retryCount.get()} txDepth=${output.size} txBytes=${queuedBytes.get()} txPeakBytes=$peakBytes txOldestMs=$oldest"
                        log("LWIP_STATUS ${PioneerLwipRuntime.summary}")
                        if (diagnostics) for (timing in listOf(loopTiming,inputTiming,pollTiming,queueTiming,buildTiming,writeTiming))
                            log("LWIP_TIMING sessionId=$session attemptId=${PioneerLwipRuntime.attemptId} ${timing.drain()}")
                        if (diagnostics) for (metric in com.shilapi.xcertplay.pioneer.PioneerDiagnostics.drain())
                            log("LWIP_TIMING sessionId=$session attemptId=${PioneerLwipRuntime.attemptId} $metric")
                        if (diagnostics) for (queue in com.shilapi.xcertplay.pioneer.PioneerDiagnostics.queues())
                            log("LWIP_QUEUE sessionId=$session attemptId=${PioneerLwipRuntime.attemptId} $queue")
                        if (diagnostics) for (detail in com.shilapi.xcertplay.pioneer.PioneerDiagnostics.drainSlow())
                            log("sessionId=$session attemptId=${PioneerLwipRuntime.attemptId} $detail")
                        peakBytes = queuedBytes.get(); lastSummary = System.nanoTime()
                    }
                }
            } catch (error: Throwable) {
                startupFailure = error; ready.countDown()
                if (running.get()) onError(error)
            } finally {
                running.set(false)
                rebuild.getAndSet(null)?.countDown()
                writer?.interrupt()
                writer?.join(2_000)
                var clean = writer?.isAlive != true
                runCatching { ncm.close() }.onFailure { clean = false; log("LWIP_CLEANUP_FAILED sessionId=$session error=${it.message}") }
                runCatching { PioneerLwipNative.close() }.onFailure { clean = false }

                output.clear(); completed.clear(); queuedBytes.set(0)
                PioneerLwipRuntime.end(session)
                if (clean) ownerLease.set(false)
                log("LWIP_CLEANUP sessionId=$session writerAlive=${writer?.isAlive == true} atNs=${System.nanoTime()}")
            }
        }, "pioneer-lwip-owner").apply { isDaemon = true; start() }
        if (!ready.await(5, TimeUnit.SECONDS)) { close(); error("lwIP startup timed out") }
        startupFailure?.let { throw IllegalStateException("lwIP startup failed", it) }
    }
    private fun writeLoop() {
        try {
            var stallRecoveries = 0
            var interfaceRebuilt = false
            while (running.get()) {
                val packet = output.poll(100, TimeUnit.MILLISECONDS) ?: continue
                inFlight = packet
                val packets = ArrayList<Output>(8).apply { add(packet) }
                var frameBytes = packet.frame.size
                // Aggregation has been validated with active video; leave USB/NCM bring-up unchanged.
                val limit = if (PioneerLwipRuntime.firstFrameNs == 0L) 1 else
                    PioneerDebugRuntime.usbTxBatchFrames.coerceIn(1, 8)
                // Never wait to fill a batch: preserve FIFO and bound the complete NTB to 16KiB.
                while (packets.size < limit) {
                    val next = output.peek() ?: break
                    val estimate = 24 + 4 * (packets.size + 1) + frameBytes + next.frame.size + 3 * packets.size
                    if (estimate > 16_383) break
                    packets.add(checkNotNull(output.poll()))
                    frameBytes += next.frame.size
                }
                packets.forEach { queueTiming.record(System.nanoTime() - it.queuedNs) }
                val frames = packets.map { it.frame }
                var failures = 0
                var failureStart = 0L
                var lastFailureLog = 0L
                while (running.get()) {
                    // 100ms reproduces the successful baseline; it no longer blocks raw lwIP.
                    val result = ncm.sendBatchMeasured(frames, 100)
                    buildTiming.record(result.buildNs)
                    writeTiming.record(result.writeNs, !result.completed)
                    com.shilapi.xcertplay.pioneer.PioneerDiagnostics.record(session,"usb-native-copy",result.copyNs, !result.completed)
                    com.shilapi.xcertplay.pioneer.PioneerDiagnostics.record(session,"usb-ioctl",result.ioctlNs, !result.completed)
                    if (result.completed) {
                        txCount.addAndGet(packets.size.toLong())
                        txBlocks.incrementAndGet()
                        check(completed.offer(Completion(packet,result,failures), 1, TimeUnit.SECONDS)) { "USB completion queue full" }
                        break
                    }
                    if (failureStart == 0L) failureStart = System.nanoTime()
                    failures++; retryCount.incrementAndGet()
                    if (System.nanoTime()-lastFailureLog > 1_000_000_000L) {
                        log("LWIP_USB_ERROR sessionId=$session attemptId=${PioneerLwipRuntime.attemptId} requestId=${packet.id} failure=${result.failure} rc=${result.transferred} expected=${result.expected} writeUs=${result.writeNs/1000} consecutive=$failures noProgressMs=${(System.nanoTime()-failureStart)/1_000_000} queuedBytes=${queuedBytes.get()}")
                        lastFailureLog = System.nanoTime()
                    }
                    when (result.failure) {
                        NcmUsbBridge.WriteFailure.TIMEOUT, NcmUsbBridge.WriteFailure.UNKNOWN -> Unit
                        NcmUsbBridge.WriteFailure.STALL -> {
                            val cleared = if (++stallRecoveries <= 2) ncm.recoverOutHalt() else -1
                            log("LWIP_CLEAR_HALT sessionId=$session requestId=${packet.id} result=$cleared attempt=$stallRecoveries")
                            if (cleared != 0) throw IOException("NCM endpoint halt recovery failed rc=$cleared")
                        }
                        else -> throw IOException("NCM fatal ${result.failure} rc=${result.transferred} bytes=${result.expected}; partial/unknown delivery is never replayed after partial completion")
                    }
                    // Authentication may take time: do not reset NCM before StartCarPlaySession.
                    val start = PioneerLwipRuntime.startRequestedNs
                    val elapsed = System.nanoTime() - maxOf(failureStart, start)
                    if (start != 0L && elapsed > 5_000_000_000L && !interfaceRebuilt) {
                        interfaceRebuilt = true
                        val latch = CountDownLatch(1); rebuildOk = false
                        check(rebuild.compareAndSet(null, latch))
                        check(latch.await(2, TimeUnit.SECONDS) && rebuildOk) { "NCM interface rebuild failed" }
                    }
                    if ((start != 0L && elapsed > 15_000_000_000L) ||
                        (start == 0L && System.nanoTime() - failureStart > 90_000_000_000L))
                        throw IOException("NCM no USB OUT progress after bounded retry/rebuild; rc=${result.transferred} failures=$failures")
                    Thread.sleep(20)
                }
                queuedBytes.addAndGet(-frameBytes.toLong()); inFlight = null
            }
        } catch (error: Throwable) {
            if (running.get()) writerFailure.compareAndSet(null, error)
        }
    }
    private fun checkStageDeadlines() {
        val start = PioneerLwipRuntime.startRequestedNs
        if (start == 0L) {
            val now = System.nanoTime()
            if (PioneerLwipRuntime.mfiNs != 0L && now-PioneerLwipRuntime.mfiNs > 20_000_000_000L)
                throw IOException("lwIP stage deadline: MFi completed but no StartCarPlaySession")
            if (now-PioneerLwipRuntime.startedNs > 90_000_000_000L)
                throw IOException("lwIP stage deadline: authentication/start did not complete")
            return
        }
        val age = System.nanoTime() - start
        val pendingAge = (inFlight ?: output.peek())?.let { System.nanoTime()-it.queuedNs } ?: 0L
        val oldestAge = maxOf(pendingAge, PioneerLwipNative.oldestOutputNanos())
        val controlAge = System.nanoTime() - PioneerLwipRuntime.controlAcceptedNs
        val setupAge = System.nanoTime() - PioneerLwipRuntime.setupNs
        val stage = when {
            PioneerLwipRuntime.firstTxReady && age > 20_000_000_000L && oldestAge > 10_000_000_000L -> "USB output queue stalled"
            !PioneerLwipRuntime.firstTxReady && age > 20_000_000_000L -> "no USB OUT completion"
            !PioneerLwipRuntime.tcpAccepted && age > 25_000_000_000L -> "no AirPlay control TCP"
            PioneerLwipRuntime.controlAcceptedNs != 0L && PioneerLwipRuntime.setupNs == 0L && controlAge > 20_000_000_000L -> "no AirPlay SETUP"
            PioneerLwipRuntime.setupNs != 0L && PioneerLwipRuntime.firstFrameNs == 0L && setupAge > 30_000_000_000L -> "no first video output"
            else -> return
        }
        throw IOException("lwIP stage deadline: $stage sessionId=$session attemptId=${PioneerLwipRuntime.attemptId}")
    }
    override fun close() {
        val started = System.nanoTime()
        running.set(false); writer?.interrupt()
        // Writer finishes its bounded ioctl before the connection can close/release its FD.
        writer?.join(2_000)
        check(writer?.isAlive != true) { "USB writer did not stop; owner lease retained" }
        runCatching { ncm.close() }
        if (Thread.currentThread() !== worker) worker?.join(3_000)
        check(worker?.isAlive != true) { "lwIP worker did not stop; owner lease retained" }
        log("LWIP_STOP sessionId=$session elapsedUs=${(System.nanoTime()-started)/1000}")
    }
    companion object { private val ownerLease = AtomicBoolean(false) }
}
