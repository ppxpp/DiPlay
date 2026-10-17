package com.shilapi.xcertplay

/** One bounded recovery per observed departure/return; no timers or Android dependencies. */
internal class PioneerSourceRecoveryPolicy {
    enum class Action { FOCUS, FOCUS_AND_PLAY }
    private var source: Int? = null
    private var previousPlaying = false
    private var previousMedia = false
    private var armed = false
    private var dueAt: Long? = null
    private var attempts = 0

    fun reset() {
        source = null
        previousPlaying = false
        previousMedia = false
        cancel()
    }

    fun cancel() { armed = false; dueAt = null; attempts = 0 }

    fun sample(nextSource: Int, playing: Boolean, mediaActive: Boolean, nowMs: Long): Action? {
        val previousSource = source
        if (previousSource == MEDIA_SOURCE && nextSource != MEDIA_SOURCE) {
            cancel()
            armed = previousPlaying && previousMedia && playing
        }
        if (!playing) cancel() // Without a proven cause, treat a phone pause as the user's choice.
        if (previousSource != null && previousSource != MEDIA_SOURCE && nextSource == MEDIA_SOURCE && armed) {
            dueAt = nowMs + SETTLE_MS
        }
        source = nextSource
        previousPlaying = playing
        previousMedia = mediaActive
        if (nextSource != MEDIA_SOURCE) return null
        val due = dueAt ?: return null
        if (!armed || nowMs < due) return null
        if (mediaActive) {
            cancel()
            return Action.FOCUS
        }
        attempts++
        if (attempts >= MAX_ATTEMPTS) { armed = false; dueAt = null }
        else dueAt = nowMs + RETRY_MS
        return Action.FOCUS_AND_PLAY
    }

    companion object {
        const val MEDIA_SOURCE = 300
        const val SETTLE_MS = 700L
        const val RETRY_MS = 1500L
        const val MAX_ATTEMPTS = 2
    }
}
