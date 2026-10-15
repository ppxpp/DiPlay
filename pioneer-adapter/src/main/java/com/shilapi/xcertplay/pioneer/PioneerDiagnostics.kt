package com.shilapi.xcertplay.pioneer

/** Aggregated by the lwIP owner, never writes files on media/USB threads. */
object PioneerDiagnostics {
    @Volatile var enabled = true
    private val queues = LinkedHashMap<String, String>()
    @Synchronized fun queue(session: Long, name: String, depth: Int, bytes: Long, oldestNs: Long, peak: Int) {
        if (!enabled || !PioneerLwipRuntime.active || session != PioneerLwipRuntime.sessionId || queues.size >= 16 && !queues.containsKey(name)) return
        val now = System.nanoTime()
        queues[name] = "queue=$name depth=$depth bytes=$bytes oldestUs=${if (oldestNs == 0L) 0 else (now-oldestNs).coerceAtLeast(0)/1000} sampledPeakDepth=$peak atNs=$now"
    }
    @Synchronized fun queues(): List<String> = queues.values.toList()
    private val slowEvents = java.util.ArrayDeque<String>()
    private var lastSlowNs = 0L
    @Synchronized fun slow(session: Long, detail: String) {
        if (!enabled || !PioneerLwipRuntime.active || session != PioneerLwipRuntime.sessionId) return
        val now = System.nanoTime()
        if (now-lastSlowNs < 1_000_000_000L) return
        lastSlowNs = now
        if (slowEvents.size >= 16) slowEvents.removeFirst()
        slowEvents.addLast("LWIP_SLOW atNs=$now $detail")
    }
    @Synchronized fun drainSlow(): List<String> = slowEvents.toList().also { slowEvents.clear() }
    private val metrics = LinkedHashMap<String, PioneerTiming>()
    @Synchronized fun record(session: Long, name: String, nanos: Long, failed: Boolean = false) {
        if (!enabled || !PioneerLwipRuntime.active || session != PioneerLwipRuntime.sessionId) return
        if (metrics.size >= 64 && !metrics.containsKey(name)) return
        metrics.getOrPut(name) { PioneerTiming(name) }.record(nanos, failed)
    }
    @Synchronized fun drain(): List<String> = metrics.values.map { it.drain() }
    @Synchronized fun clear() { metrics.clear(); queues.clear(); slowEvents.clear(); lastSlowNs = 0 }
}
