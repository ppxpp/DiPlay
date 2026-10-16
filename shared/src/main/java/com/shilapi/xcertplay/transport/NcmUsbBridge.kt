package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    private val onDiagnostic: (String) -> Unit = {},
) : Closeable {
    private val connectionRunId = com.shilapi.xcertplay.pioneer.PioneerConnectionTrace.runId
    private var lastNetworkValue = -1
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var cleanupComplete = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private var writeDiagnosticBudget = 16
    private var readQueuedSession = 0L
    private var readQueuedNs = 0L
    private val frameTimes = ArrayDeque<Long>()
    private var bulkOutAccepted = false
    private var notReadyFrames = 0
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalShortPacketPad = false
    // A full-speed link uses 64-byte packets; the short-packet pad follows the real packet size.
    private val usbPacketSize = inEndpoint.maxPacketSize.takeIf { it > 0 } ?: USB_PACKET_SIZE
    private val resyncTimes = ArrayDeque<Long>()
    private var resyncCount = 0
    private var resyncReason: String? = null
    private var resyncSkipped = 0
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // Bulk IN uses one persistent async request: bulkTransfer() pins its byte[] in a JNI critical
    // section for the whole wait, which blocks ART's GC thread flip and, with it, every other USB
    // transfer (seen as ~0.8 s stalls of video and audio). A timed-out request stays queued, so no
    // data is lost between calls. This is the only requestWait() user on this connection.
    private val directReadBuffer = ByteBuffer.allocateDirect(READ_CHUNK_BYTES)
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val readQueuePolicy = UsbReadQueuePolicy.forCurrentPlatform()
    private val readRequests = UsbRequestQueue(connection, "ncm-read-reaper")
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    enum class WriteFailure { NONE, TIMEOUT, DISCONNECTED, STALL, MEMORY, PARTIAL, UNKNOWN }
    data class WriteResult(val transferred: Int, val expected: Int, val failure: WriteFailure,
        val buildNs: Long, val writeNs: Long, val copyNs: Long = 0, val ioctlNs: Long = 0) {
        val completed: Boolean get() = failure == WriteFailure.NONE
    }
    /** Legacy VPN callers retain their Boolean contract; partial writes remain fatal. */
    fun send(frame: ByteArray, timeoutMillis: Int): Boolean {
        val result = sendMeasured(frame, timeoutMillis)
        if (result.failure == WriteFailure.PARTIAL) throw IphoneUsbException.DeviceUnavailable(
            "NCM partial NTB ${result.transferred}/${result.expected}; restarting required")
        return result.completed
    }
    fun sendMeasured(frame: ByteArray, timeoutMillis: Int): WriteResult = sendBatchMeasured(listOf(frame), timeoutMillis)

    fun sendBatchMeasured(frames: List<ByteArray>, timeoutMillis: Int): WriteResult = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0)
        val started = System.nanoTime()
        val seq = synchronized(stateLock) { checkOpenLocked(); sequence.also { sequence = (sequence + 1) and 0xffff } }
        val block = Ntb16Codec.buildBatch(frames, seq)
        val writing = System.nanoTime()
        val native = com.shilapi.xcertplay.pioneer.PioneerUsbPolicy.matchesDevice()
        // Use upstream's short startup wait for both framework and native writes. Keep the old
        // native caller-timeout alternative in source, disabled until comparative evidence exists.
        val legacyNativeWait = native && com.shilapi.xcertplay.pioneer.LocalConnectionExperiments.NATIVE_LONG_INITIAL_WRITE
        val writeTimeout = if (!legacyNativeWait && !bulkOutAccepted)
            minOf(timeoutMillis, NOT_READY_WRITE_TIMEOUT_MILLIS) else timeoutMillis
        val detail = if (native && com.shilapi.xcertplay.pioneer.PioneerDiagnostics.enabled)
            com.shilapi.xcertplay.pioneer.PioneerUsbNative.bulkWriteMeasured(connection.fileDescriptor, outEndpoint.address, block, writeTimeout) else null
        val transferred = detail?.get(0)?.toInt() ?: if (native)
            com.shilapi.xcertplay.pioneer.PioneerUsbNative.bulkWrite(connection.fileDescriptor, outEndpoint.address, block, writeTimeout)
        else connection.bulkTransfer(outEndpoint, block, block.size, writeTimeout)
        val classification = when {
            transferred == block.size -> WriteFailure.NONE
            transferred > 0 -> WriteFailure.PARTIAL
            transferred == -110 || transferred == 0 -> WriteFailure.TIMEOUT
            transferred == -19 || transferred == -9 || transferred == -108 -> WriteFailure.DISCONNECTED
            transferred == -32 -> WriteFailure.STALL
            transferred == -12 -> WriteFailure.MEMORY
            else -> WriteFailure.UNKNOWN // Android -1 does not expose errno.
        }
        if (transferred <= 0) {
            if (!bulkOutAccepted) notReadyFrames += frames.size
            if (!loggedWriteTimeout) {
                loggedWriteTimeout = true
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
            }
        } else {
            if (!bulkOutAccepted) {
                bulkOutAccepted = true
                runCatching { onDiagnostic("NCM bulk-out ready after $notReadyFrames unaccepted frames") }
            }
            if (loggedWriteTimeout) {
                loggedWriteTimeout = false
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
            }
        }
        if (native && writeDiagnosticBudget-- > 0) runCatching { onDiagnostic(
            "NCM_TX transferred=$transferred expected=${block.size} frames=${frames.size} frameBytes=${frames.sumOf { it.size }} timeoutMs=$writeTimeout failure=$classification") }
        WriteResult(transferred, block.size, classification, writing - started, System.nanoTime() - writing, detail?.get(1) ?: 0, detail?.get(2) ?: 0)
    }
    /** Only the OUT endpoint; caller serializes with the single USB writer. */
    fun recoverOutHalt(): Int = synchronized(writeLock) {
        checkOpen()
        com.shilapi.xcertplay.pioneer.PioneerUsbNative.clearHalt(connection.fileDescriptor, outEndpoint.address)
    }
    /** IN is quiesced/cancelled before alternate changes; no USB read races with this operation. */
    fun rebuildDataInterface(): Boolean = synchronized(readLock) { synchronized(writeLock) rebuild@{
        checkOpen()
        val data = claimedInterfaces.firstOrNull { iface ->
            (0 until iface.endpointCount).any { iface.getEndpoint(it).address == outEndpoint.address }
        }
            ?: return@rebuild false
        synchronized(stateLock) {
            readRequest?.cancel()
        }
        if (readQueued) {
            try {
                val completed = readRequests.await(500)
                if (completed == null || completed !== readRequest) return@rebuild false
                readQueued = false
            } catch (_: Exception) { return@rebuild false }
        }
        val zero = com.shilapi.xcertplay.pioneer.PioneerUsbNative.setAlternate(connection.fileDescriptor, data.id, 0)
        val one = if (zero == 0) com.shilapi.xcertplay.pioneer.PioneerUsbNative.setAlternate(connection.fileDescriptor, data.id, data.alternateSetting) else zero
        runCatching { onDiagnostic("NCM_INTERFACE_REBUILD iface=${data.id} alt0=$zero alt1=$one") }
        bufferedSize = 0; optionalShortPacketPad = false; frames.clear(); frameTimes.clear(); queuedBytes = 0
        zero == 0 && one == 0
    } }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                val parsing = System.nanoTime()
                drainFrames()
                com.shilapi.xcertplay.pioneer.PioneerDiagnostics.record(readQueuedSession, "ntb-parse", System.nanoTime()-parsing)
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    @Synchronized override fun close() {
        statusRunning.set(false)
        val requestToClose = synchronized(stateLock) {
            if (closed) {
                check(cleanupComplete) { "NCM previous cleanup incomplete" }
                return
            }
            closed = true
            readRequest
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        synchronized(writeLock) {
            for (usbInterface in claimedInterfaces.asReversed()) {
                try {
                    connection.releaseInterface(usbInterface)
                } catch (_: RuntimeException) {
                    // Best-effort release; the connection close below is authoritative.
                }
            }
            readRequests.close()
            connection.close()
            runCatching { requestToClose?.close() }
            check(readRequests.awaitStopped(1_000)) { "NCM read reaper did not stop" }
            cleanupComplete = true
        }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        var diagnosticBudget = 8
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                if (com.shilapi.xcertplay.pioneer.PioneerUsbPolicy.matchesDevice())
                    com.shilapi.xcertplay.pioneer.PioneerUsbNative.readNotification(
                        connection.fileDescriptor, endpoint.address, buffer, STATUS_POLL_TIMEOUT_MILLIS)
                else connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (transferred >= 8 && (buffer[1].toInt() and 255) == 0) {
                val value = readU16(buffer, 2)
                if (value != lastNetworkValue) {
                    lastNetworkValue = value
                    com.shilapi.xcertplay.pioneer.PioneerConnectionTrace.mark("ncm-network-notification",
                        "value=$value interface=${readU16(buffer, 4)}", connectionRunId)
                }
            }
            if (diagnosticBudget-- > 0 && transferred >= 8) {
                onDiagnostic("NCM_NOTIFICATION bytes=$transferred type=${buffer[1].toInt() and 255} " +
                    "value=${readU16(buffer, 2)} interface=${readU16(buffer, 4)} dataLength=${readU16(buffer, 6)}")
            }
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            // wBlockLength describes the complete NTB. A packet-aligned USB transfer may end
            // with a ZLP (no byte in the buffer), or carry one zero byte to force a short packet.
            // Do not wait for that optional byte or consume the next NTB's header as padding.
            // Keep this state when the pad/header arrives in a later USB completion.
            if (optionalShortPacketPad && bufferedSize > 0) {
                optionalShortPacketPad = false
                when (buffered[0].toInt() and 0xff) {
                    0 -> discard(1)
                    Ntb16Codec.NTH16_SIG and 0xff -> Unit // The next header is validated below.
                    else -> if (!resynchronize("Invalid NTB16 short-packet pad")) return
                }
            }
            if (bufferedSize < NTH16_LENGTH) return
            val blockLength = readU16(buffered, 8)
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                if (!resynchronize("NCM read buffer does not begin with an NTB16 header")) return
                continue
            }
            if (blockLength < 28) {
                if (!resynchronize("Invalid NTB16 block length $blockLength")) return
                continue
            }
            if (bufferedSize < blockLength) return
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            discard(blockLength)
            optionalShortPacketPad = blockLength % usbPacketSize == 0
            finishResync()
        }
    }

    /**
     * Android reaps a bulk transfer that failed part way (babble, CRC, a dropped packet) as a
     * normal completion with whatever bytes arrived, so a damaged NTB can leave the stream
     * misaligned. Like Linux cdc_ncm, drop the damaged bytes and continue at the next valid NTB16
     * header; TCP resends what was lost. Returns false while no header is buffered yet. Damage
     * that keeps recurring still fails the session, as before.
     */
    private fun resynchronize(reason: String): Boolean {
        if (resyncReason == null) {
            val now = System.nanoTime()
            while (resyncTimes.isNotEmpty() && now - resyncTimes.first() > RESYNC_WINDOW_NANOS) resyncTimes.removeFirst()
            if (resyncTimes.size >= MAX_RESYNCS_PER_WINDOW) throw failSession("$reason (repeated)")
            resyncTimes.addLast(now)
            resyncCount++
            resyncReason = reason
            resyncSkipped = 0
        }
        val next = (1..bufferedSize - NTH16_LENGTH).firstOrNull(::plausibleHeaderAt)
        // Without a header, keep the bytes that could still be the start of one.
        val skipped = next ?: (bufferedSize - (NTH16_LENGTH - 1)).coerceAtLeast(0)
        discard(skipped)
        resyncSkipped += skipped
        return next != null
    }

    private fun finishResync() {
        val reason = resyncReason ?: return
        resyncReason = null
        if (resyncCount <= LOGGED_RESYNCS || resyncCount % 50 == 0) {
            runCatching { onDiagnostic("NCM resynchronized after $reason; skipped=$resyncSkipped resyncs=$resyncCount") }
        }
    }

    /** Stricter than the normal path, so payload bytes are unlikely to pass as a header. */
    private fun plausibleHeaderAt(offset: Int): Boolean {
        if (readU32(buffered, offset) != Ntb16Codec.NTH16_SIG) return false
        val headerLength = readU16(buffered, offset + 4)
        val blockLength = readU16(buffered, offset + 8)
        val ndpIndex = readU16(buffered, offset + 10)
        return headerLength == NTH16_LENGTH && blockLength >= 28 &&
            ndpIndex >= NTH16_LENGTH && ndpIndex % 4 == 0 && ndpIndex + 8 <= blockLength
    }

    private fun discard(count: Int) {
        if (count <= 0) return
        buffered.copyInto(buffered, 0, count, bufferedSize)
        bufferedSize -= count
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        frameTimes.addLast(System.nanoTime())
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        val queued = frameTimes.removeFirst()
        com.shilapi.xcertplay.pioneer.PioneerDiagnostics.record(readQueuedSession, "ncm-rx-frame-wait", System.nanoTime()-queued)
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            // Publish and queue atomically with close(), so detach cannot miss a new request.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession("Android could not initialize the NCM read request")
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    val queued = readQueuePolicy.queue(directReadBuffer, ::checkOpenLocked) { readRequests.queue(current, it) }
                    if (!queued.queued) throw failSession(
                        "Android could not queue the NCM read request (api=${Build.VERSION.SDK_INT} " +
                            "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                            "fallbackBytes=${queued.fallbackBytes ?: "not_attempted"})",
                    )
                    readQueued = true
                    readQueuedNs = System.nanoTime()
                    readQueuedSession = com.shilapi.xcertplay.pioneer.PioneerLwipRuntime.sessionId
                    if (queued.fallbackBytes != null) acceptedFallback = queued
                }
                current
            }
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        // Emit outside stateLock; diagnostic callbacks must not affect queue or close behavior.
        acceptedFallback?.let { queued ->
            runCatching {
                onDiagnostic(
                    "NCM read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                        "fallbackBytes=${queued.fallbackBytes}",
                )
            }
        }
        try {
            val completed = try {
                readRequests.await(timeoutMillis.coerceAtLeast(1))
            } catch (_: TimeoutException) {
                // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                // authoritative detach/failure detection for the same phone.
                return null
            } ?: throw failSession("Android returned no NCM read request")
            if (completed !== request) throw failSession("Android completed an unexpected NCM request")
            readQueued = false
            com.shilapi.xcertplay.pioneer.PioneerDiagnostics.record(readQueuedSession, "usb-rx-inflight-includes-idle", System.nanoTime()-readQueuedNs)
            val transferred = directReadBuffer.position()
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val NTH16_LENGTH = 12
        private const val MAX_RESYNCS_PER_WINDOW = 16
        private const val RESYNC_WINDOW_NANOS = 10_000_000_000L
        private const val LOGGED_RESYNCS = 5
        internal const val USB_SETUP_ATTEMPTS = 5
        private const val USB_SETUP_RETRY_MILLIS = 100L
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        internal const val NOT_READY_WRITE_TIMEOUT_MILLIS = 20
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /**
         * Retries a USB setup call that a vendor kernel can lose to its own cdc_ncm driver, which may
         * rebind between Android's forced detach and the claim (#518). Returns the successful attempt.
         */
        internal fun retryUsbSetup(
            attempts: Int = USB_SETUP_ATTEMPTS,
            pause: (Long) -> Unit = Thread::sleep,
            action: () -> Boolean,
        ): Int? {
            for (attempt in 1..attempts) {
                if (action()) return attempt
                if (attempt < attempts) pause(USB_SETUP_RETRY_MILLIS)
            }
            return null
        }

        /**
         * Builds the claim-failure verdict while the connection is still open, records it, and
         * returns the exception that carries it. The claim uses `force`, so a refused claim after
         * every retry is also a refused driver detach.
         */
        private fun claimFailure(
            device: UsbDevice?,
            connection: UsbDeviceConnection,
            interfaceId: Int,
            path: String,
        ): IphoneUsbException {
            val verdict = UsbVerdict.forUsbFailure(
                device = device,
                connection = connection,
                claimInterfaceId = interfaceId,
                targetConfiguration = device?.let { IphoneCarPlayConfiguration.find(it)?.id },
                switchSucceeded = true,
                detachAttempted = true,
                detachFailures = listOf(interfaceId),
            )
            UsbSessionDiagnostics.recordVerdict(verdict)
            UsbVerdict.captureSnapshot(device)?.let { UsbSessionDiagnostics.recordSnapshot(it.summary()) }
            return IphoneUsbException.DeviceUnavailable(
                "Android could not claim the $path interface $interfaceId; ${verdict.line}",
            )
        }

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            device: UsbDevice? = null,
            diagnosticClaim: ((UsbDeviceConnection, UsbInterface) -> Boolean)? = null,
            onDiagnostic: (String) -> Unit = {},
        ): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = retryUsbSetup { diagnosticClaim?.invoke(connection, first) ?: connection.claimInterface(first, true) }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${first.alternateSetting} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=${firstClaimed != null}" +
                        " attempts=${firstClaimed ?: USB_SETUP_ATTEMPTS}",
                )
                if (firstClaimed == null) {
                    throw claimFailure(device, connection, first.id, "NCM")
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = retryUsbSetup { diagnosticClaim?.invoke(connection, function.data) ?: connection.claimInterface(function.data, true) }
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${function.data.alternateSetting}" +
                            " class=${function.data.interfaceClass} ok=${dataClaimed != null}" +
                            " attempts=${dataClaimed ?: USB_SETUP_ATTEMPTS}",
                    )
                    if (dataClaimed == null) {
                        throw claimFailure(device, connection, function.data.id, "NCM data")
                    }
                    claimed.add(function.data)
                }
                if (com.shilapi.xcertplay.pioneer.PioneerUsbPolicy.matchesDevice()) {
                    // Observe the NCM function's negotiated state before enabling data.
                    val alt0 = com.shilapi.xcertplay.pioneer.PioneerUsbNative.setAlternate(
                        connection.fileDescriptor, function.data.id, 0)
                    val parameters = ByteArray(28)
                    val got = connection.controlTransfer(0xa1, 0x80, 0, function.control.id,
                        parameters, parameters.size, 1_000)
                    val format = ByteArray(2)
                    val formatRead = connection.controlTransfer(0xa1, 0x83, 0, function.control.id,
                        format, format.size, 1_000)
                    val inputSize = ByteArray(4)
                    val inputRead = connection.controlTransfer(0xa1, 0x85, 0, function.control.id,
                        inputSize, inputSize.size, 1_000)
                    // Directed, broadcast, all-multicast: includes IPv6 neighbor discovery.
                    val filter = connection.controlTransfer(0x21, 0x43, 0x0e, function.control.id,
                        null, 0, 1_000)
                    fun u16(bytes: ByteArray, offset: Int) =
                        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
                    fun u32(bytes: ByteArray, offset: Int) =
                        (u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16))
                    onDiagnostic("NCM_CLASS_SETUP alt0=$alt0 paramsRc=$got formats=${u16(parameters, 2)} " +
                        "inMax=${u32(parameters, 4)} outMax=${u32(parameters, 16)} " +
                        "outDivisor=${u16(parameters, 20)} outRemainder=${u16(parameters, 22)} " +
                        "outAlignment=${u16(parameters, 24)} formatRc=$formatRead format=${u16(format, 0)} " +
                        "inputRc=$inputRead inputSize=${u32(inputSize, 0)} packetFilterRc=$filter")
                }
                val altSelected = retryUsbSetup { connection.setInterface(function.data) }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${function.data.alternateSetting} ok=${altSelected != null}" +
                        " attempts=${altSelected ?: USB_SETUP_ATTEMPTS}",
                )
                if (altSelected == null) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    // Drain CDC notifications as in the working baseline; native polling avoids
                    // pinning ART's heap during the target firmware's synchronous USB wait.
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    onDiagnostic,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
