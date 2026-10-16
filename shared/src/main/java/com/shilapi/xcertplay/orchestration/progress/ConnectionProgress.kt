package com.shilapi.xcertplay.orchestration.progress

/** Display-only timing model. No Android, transport operations, disk I/O or view references. */
enum class ConnectionStep(val key: String) {
    PREPARATION("preparation"), DEVICE("device"), CONTROL("control"),
    NETWORK("network"), SESSION("session"), VIDEO("video"),
}

enum class ConnectionOutcome { CONNECTING, RETRYING, FAILED, COMPLETE, CANCELLED }

data class ConnectionTiming(val durations: Map<ConnectionStep, Long>, val totalMillis: Long)

data class ConnectionProgressSnapshot(
    val steps: List<ConnectionStep>,
    val current: ConnectionStep,
    val durations: Map<ConnectionStep, Long>,
    val visited: Set<ConnectionStep>,
    val elapsedMillis: Long,
    val outcome: ConnectionOutcome,
    val waitingForUser: Boolean,
    val attempt: Int,
    val overallMillis: Long,
    val historySamples: Int,
    val expectedMillis: Long?,
    val remainingMillis: Long?,
)

class ConnectionProgress(
    val profile: String,
    val wireless: Boolean,
    val attempt: Int = 1,
    val overallStartedMillis: Long = monotonicMillis(),
    private val baseline: ConnectionTiming? = null,
    private val historySamples: Int = 0,
    private val clock: () -> Long = ::monotonicMillis,
    private val onCompleted: (ConnectionTiming) -> Unit = {},
) {
    private val started = clock()
    private var entered = started
    private var ended: Long? = null
    private var current = ConnectionStep.PREPARATION
    @Volatile private var outcome = ConnectionOutcome.CONNECTING
    private val durations = linkedMapOf<ConnectionStep, Long>()
    private val visited = linkedSetOf(current)
    private var waiting = false
    private var waitingSince = started
    private var userWaitMillis = 0L
    @Volatile var hasRendered: Boolean = false
        private set

    @Synchronized fun step(next: ConnectionStep, waitingForUser: Boolean = false) {
        if (outcome != ConnectionOutcome.CONNECTING) return
        val now = clock()
        if (waiting) userWaitMillis += (now - waitingSince).coerceAtLeast(0)
        waiting = waitingForUser
        waitingSince = now
        if (next == current) return
        accrue(now)
        current = next
        visited += next
    }

    /** Failure freezes node timing; retry backoff remains visible in the overall wait. */
    @Synchronized fun stop(result: ConnectionOutcome) {
        if (outcome == ConnectionOutcome.CANCELLED) return
        if (outcome == ConnectionOutcome.COMPLETE && result != ConnectionOutcome.RETRYING && result != ConnectionOutcome.CANCELLED) return
        if (outcome == ConnectionOutcome.CONNECTING) {
            val now = clock()
            accrue(now)
            ended = now
        }
        waiting = false
        outcome = result
    }

    fun firstFrame() {
        if (outcome == ConnectionOutcome.CANCELLED) return
        // Output is a fact even if a transient control failure already froze this sample. Never
        // leave the waiting page covering working video just because the timing sample was rejected.
        hasRendered = true
        if (outcome != ConnectionOutcome.CONNECTING) return
        val timing = synchronized(this) {
            if (outcome != ConnectionOutcome.CONNECTING) return
            val now = clock()
            if (waiting) userWaitMillis += (now - waitingSince).coerceAtLeast(0)
            accrue(now)
            ended = now
            waiting = false
            outcome = ConnectionOutcome.COMPLETE
            val total = (now - started).coerceAtLeast(0)
            // Long unplugged/permission waits are not representative automatic connection samples.
            if (userWaitMillis > 15_000 || total !in 1..180_000) null
            else ConnectionTiming(ConnectionStep.entries.associateWith { durations[it] ?: 0 }, total)
        }
        timing?.let(onCompleted)
    }

    @Synchronized fun sessionRequested() {
        if (current != ConnectionStep.VIDEO) step(ConnectionStep.SESSION)
    }

    @Synchronized fun snapshot(): ConnectionProgressSnapshot {
        val now = clock()
        val end = ended ?: now
        val measured = ConnectionStep.entries.associateWith { step ->
            (durations[step] ?: 0) + if (step == current && ended == null) (now - entered).coerceAtLeast(0) else 0
        }
        val expected = baseline?.totalMillis
        val remaining = if (outcome == ConnectionOutcome.CONNECTING) baseline?.let { sample ->
            // Completed steps do not contribute; unvisited and the current step still do.
            ConnectionStep.entries.sumOf { step ->
                if (step in visited && step != current) 0L
                else ((sample.durations[step] ?: 0) - measured.getValue(step)).coerceAtLeast(0)
            }
        } else null
        return ConnectionProgressSnapshot(
            if (wireless) listOf(ConnectionStep.PREPARATION, ConnectionStep.NETWORK, ConnectionStep.DEVICE,
                ConnectionStep.CONTROL, ConnectionStep.SESSION, ConnectionStep.VIDEO) else ConnectionStep.entries,
            current, measured, visited.toSet(), (end - started).coerceAtLeast(0), outcome, waiting,
            attempt, ((if (outcome == ConnectionOutcome.RETRYING && !hasRendered) now else end) - overallStartedMillis).coerceAtLeast(0),
            historySamples, expected, remaining,
        )
    }

    private fun accrue(now: Long) {
        durations[current] = (durations[current] ?: 0) + (now - entered).coerceAtLeast(0)
        entered = now
    }

    companion object {
        fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
    }
}
