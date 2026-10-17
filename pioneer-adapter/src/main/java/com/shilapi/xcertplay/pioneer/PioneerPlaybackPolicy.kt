package com.shilapi.xcertplay.pioneer

/** Playback defaults are independent of the optional debug service and its main-thread handler. */
object PioneerPlaybackPolicy {
    // Only the verified Pioneer wired lwIP path omits declarations so setNightMode takes effect.
    // Wireless and other firmware retain the upstream appearance declarations.
    val appearanceDeclarationsEnabled: Boolean
        get() = !(PioneerUsbPolicy.matchesDevice() && PioneerLwipRuntime.active)

    // Read on each media write so a diagnostic override can still change a running session.
    @Volatile var audioClockTrimOverride: Boolean? = null
        private set
    var audioClockTrimEnabled: Boolean
        get() = audioClockTrimOverride ?: !(PioneerUsbPolicy.matchesDevice() && PioneerLwipRuntime.active)
        set(value) { audioClockTrimOverride = value }
}
