package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Looper
import com.shilapi.xcertplay.vehicle.api.AudioRouteController
import com.shilapi.xcertplay.vehicle.api.DefaultAudioRouteController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class VehicleMediaFocusRecoveryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val routing = object : AudioRouteController by DefaultAudioRouteController {
        override val available = true
        override fun streamType(channel: AudioChannel) = AudioManager.STREAM_MUSIC
        override fun focusStream(attributes: AudioAttributes) = AudioManager.STREAM_MUSIC
    }
    private fun coordinator(): AudioFocusCoordinator {
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return AudioFocusCoordinator(context, false, audioRouting = routing)
    }
    private fun track() = AudioTrack(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
        AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(), 8192, AudioTrack.MODE_STREAM,
        AudioManager.AUDIO_SESSION_ID_GENERATE)

    @Test fun temporaryRequestExpiresWhenNoStreamArrives() {
        val c = coordinator()
        try {
            assertTrue(c.recoverVehicleMedia())
            assertNull(shadowOf(manager).lastAbandonedAudioFocusListener)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNotNull(shadowOf(manager).lastAbandonedAudioFocusListener)
        } finally { c.close() }
    }

    @Test fun cancellationReleasesTemporaryRequestImmediately() {
        val c = coordinator()
        try {
            assertTrue(c.recoverVehicleMedia())
            c.cancelVehicleRecovery()
            assertNotNull(shadowOf(manager).lastAbandonedAudioFocusListener)
        } finally { c.close() }
    }

    @Test fun arrivingRendererKeepsFocusWhenTemporaryDeadlinePasses() {
        val c = coordinator(); val t = track()
        try {
            assertTrue(c.recoverVehicleMedia())
            c.acquire(t, AudioChannel.MEDIA, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            c.cancelVehicleRecovery()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNull(shadowOf(manager).lastAbandonedAudioFocusListener)
            c.release(t)
            assertNotNull(shadowOf(manager).lastAbandonedAudioFocusListener)
        } finally { c.close(); t.release() }
    }

    @Test fun activePhonePreventsMediaRecoveryAndClosedCoordinatorNeverAcquires() {
        val c = coordinator(); val t = track()
        try {
            c.acquire(t, AudioChannel.PHONE, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
            assertFalse(c.recoverVehicleMedia())
            c.close()
            assertFalse(c.recoverVehicleMedia())
        } finally { c.close(); t.release() }
    }
}
