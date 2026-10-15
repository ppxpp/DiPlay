package com.shilapi.xcertplay.pioneer

/** Bounded, mergeable histogram. Nanoseconds internally; bucket upper bounds in microseconds. */
class PioneerTiming(private val name: String) {
    private val buckets = LongArray(128)
    private var count = 0L
    private var maxNs = 0L
    private var errors = 0L
    @Synchronized fun record(nanos: Long, failed: Boolean = false) {
        if (!PioneerDiagnostics.enabled) return
        val us = ((nanos.coerceAtLeast(0) + 999) / 1_000).coerceAtLeast(1)
        var slot = 0
        while (slot < buckets.lastIndex && limitUs(slot) < us) slot++
        buckets[slot]++
        count++
        maxNs = maxOf(maxNs, nanos)
        if (failed) errors++
    }
    @Synchronized fun drain(): String {
        fun percentile(p: Int): Long {
            if (count == 0L) return 0
            val target = (count * p + 99) / 100
            var sum = 0L
            for (i in buckets.indices) { sum += buckets[i]; if (sum >= target) return limitUs(i) }
            return maxNs / 1_000
        }
        val result = "$name n=$count p50Us=${percentile(50)} p95Us=${percentile(95)} p99Us=${percentile(99)} maxUs=${maxNs / 1_000} errors=$errors"
        buckets.fill(0); count = 0; maxNs = 0; errors = 0
        return result
    }
    private fun limitUs(slot: Int): Long = (1L shl (slot / 4)) * (4 + slot % 4) / 4
}
