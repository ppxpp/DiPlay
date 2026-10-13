package com.shilapi.xcertplay.vehicle.api

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import com.shilapi.xcertplay.media.AudioChannel

/** Vehicle audio policy; Android rendering and focus ownership remain in the media sink. */
interface AudioRouteController {
    val available: Boolean
    val nonBlockingWrites: Boolean
    fun streamType(channel: AudioChannel): Int?
    fun focusStream(attributes: AudioAttributes): Int?
    fun independentSpeechFocus(attributes: AudioAttributes): Boolean
    fun acceptsLegacyStream(stream: Int): Boolean
    fun selectMediaSource(report: (String) -> Unit)
    fun pauseLocalPlayer(context: Context?, report: (String) -> Unit)
}

/** Standard Android behavior when no vehicle-specific audio policy is available. */
object DefaultAudioRouteController : AudioRouteController {
    override val available = false
    override val nonBlockingWrites = false
    override fun streamType(channel: AudioChannel): Int? = null
    override fun focusStream(attributes: AudioAttributes): Int? = null
    override fun independentSpeechFocus(attributes: AudioAttributes) = false
    override fun acceptsLegacyStream(stream: Int) =
        stream in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY
    override fun selectMediaSource(report: (String) -> Unit) = Unit
    override fun pauseLocalPlayer(context: Context?, report: (String) -> Unit) = Unit
}
