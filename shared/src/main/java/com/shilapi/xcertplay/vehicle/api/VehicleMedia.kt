package com.shilapi.xcertplay.vehicle.api

import java.io.Closeable

enum class MediaCommand { PLAY, PAUSE, NEXT, PREVIOUS }

data class NowPlayingSnapshot(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMillis: Long = 0,
    val elapsedMillis: Long = 0,
    val playing: Boolean = false,
    val positionKnown: Boolean = false,
)

interface SteeringWheelInputSource {
    fun start(onCommand: (MediaCommand) -> Unit, report: (String) -> Unit)
    fun stop()
}

interface LocalPlaybackGuardClient : Closeable {
    /** True only after the other process confirms its playback gate is active. */
    fun acquire(): Boolean
}

interface InstrumentMediaSink : Closeable {
    /** Accepted by the system interface, not proof of physical display. */
    fun publish(snapshot: NowPlayingSnapshot): Boolean
}
