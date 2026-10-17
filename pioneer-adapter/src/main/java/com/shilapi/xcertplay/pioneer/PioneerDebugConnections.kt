package com.shilapi.xcertplay.pioneer

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** No queued sockets. Bulk transfers cannot occupy the control workers. */
internal class PioneerDebugConnections(private val handle: (Socket, JSONObject) -> Unit) {
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(4)
    private val control = pool(3)
    private val bulk = pool(1)
    private val timer = ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var closed = false

    private fun pool(count: Int) = ThreadPoolExecutor(count, count, 0, TimeUnit.MILLISECONDS,
        SynchronousQueue<Runnable>(), ThreadPoolExecutor.AbortPolicy())

    @Synchronized fun accept(socket: Socket) {
        if (closed || !slots.tryAcquire()) { close(socket); return }
        clients.add(socket)
        // All deadlines are absolute; sending one byte periodically cannot extend them.
        val accepted = System.nanoTime()
        val headerDeadline = timer.schedule({ close(socket) }, 5, TimeUnit.SECONDS)
        try {
            control.execute {
                var handedOff = false
                try {
                    socket.soTimeout = 5_000
                    val header = ByteArrayOutputStream()
                    val input = socket.getInputStream()
                    while (true) {
                        val next = input.read()
                        require(next >= 0) { "Incomplete header" }
                        if (next == 10) break
                        require(header.size() < 16_384) { "Header too large" }
                        header.write(next)
                    }
                    val request = JSONObject(header.toString("UTF-8"))
                    val isBulk = request.getString("cmd") in setOf("update", "export", "runtime", "video-snapshot", "logs")
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - accepted)
                    val deadline = timer.schedule({ close(socket) },
                        ((if (isBulk) 120_000L else 10_000L) - elapsed).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    headerDeadline.cancel(false)
                    val task = Runnable {
                        try {
                            socket.soTimeout = 10_000
                            handle(socket, request)
                        } catch (error: Exception) {
                            runCatching {
                                write(socket) { socket.getOutputStream().apply {
                                    write((JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.simpleName).toString() + "\n").toByteArray(Charsets.UTF_8))
                                    flush()
                                } }
                            }
                        } finally { deadline.cancel(false); finish(socket) }
                    }
                    if (isBulk) {
                        try { bulk.execute(task); handedOff = true }
                        catch (_: RejectedExecutionException) { deadline.cancel(false) }
                    } else { handedOff = true; task.run() }
                } catch (_: Exception) {
                    // Invalid, timed out, or over capacity: close immediately without a blocking reply.
                } finally {
                    headerDeadline.cancel(false)
                    if (!handedOff) finish(socket)
                }
            }
        } catch (_: RejectedExecutionException) { headerDeadline.cancel(false); finish(socket) }
    }

    /** Socket SO_TIMEOUT does not cover writes; closing it interrupts a blocked writer. */
    fun write(socket: Socket, action: () -> Unit) {
        val watchdog = timer.schedule({ close(socket) }, 10, TimeUnit.SECONDS)
        try { action() } finally { watchdog.cancel(false) }
    }

    private fun close(socket: Socket) { runCatching { socket.close() } }
    private fun finish(socket: Socket) {
        close(socket)
        if (clients.remove(socket)) slots.release()
    }

    @Synchronized fun close() {
        closed = true
        clients.forEach(::close)
        control.shutdownNow(); bulk.shutdownNow(); timer.shutdownNow()
    }
}
