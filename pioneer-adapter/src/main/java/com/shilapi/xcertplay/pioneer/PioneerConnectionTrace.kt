package com.shilapi.xcertplay.pioneer

/** Bounded timeline includes work before the lwIP owner exists. No file I/O on protocol threads. */
object PioneerConnectionTrace {
    private var sequence = 0L
    @Volatile var runId = 0L
        private set
    private var startNs = 0L
    private var requested = false
    private val events = java.util.ArrayDeque<String>()

    @Synchronized private fun begin() {
        runId = ++sequence
        startNs = System.nanoTime()
        events.clear()
    }
    @Synchronized fun requestStart() {
        begin()
        requested = true
        mark("command-start-wired")
    }
    @Synchronized fun controllerStart() {
        if (!requested || System.nanoTime() - startNs > 60_000_000_000L) begin()
        requested = false
        mark("controller-start")
    }
    @Synchronized fun mark(stage: String, detail: String = "", expectedRun: Long = runId) {
        if (runId == 0L || expectedRun != runId) return
        val now = System.nanoTime()
        if (events.size >= 128) events.removeFirst()
        events.addLast("CONNECT_STAGE runId=$runId sessionId=${PioneerLwipRuntime.sessionId} " +
            "lwipActive=${PioneerLwipRuntime.active} attemptId=${PioneerLwipRuntime.attemptId} stage=$stage atNs=$now elapsedMs=${(now-startNs)/1_000_000} $detail")
    }
    @Synchronized fun snapshot(): List<String> = events.toList()
}
