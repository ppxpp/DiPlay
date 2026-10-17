package com.shilapi.xcertplay.vehicle

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.vehicle.api.MediaCommand
import com.shilapi.xcertplay.vehicle.api.NowPlayingSnapshot
import com.shilapi.xcertplay.vehicle.runtime.VehicleMediaRuntime

/** The application's only vehicle-extension assembly point. */
internal object DiPlayVehicleIntegration {
    /** One owner for both protection and source recovery; replacement retires both together. */
    private class Binding(
        val controller: CarPlayController,
        val runtime: VehicleMediaRuntime?,
        private val recovery: com.shilapi.xcertplay.PioneerMediaSourceRecovery?,
    ) : java.io.Closeable {
        override fun close() {
            controller.vehicleSessionListener = null
            runtime?.close()
            recovery?.close()
        }
    }
    private var binding: Binding? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (binding?.controller === next) return
        binding?.close()
        binding = null
        val recovery = com.shilapi.xcertplay.PioneerMediaSourceRecovery.create(context, next)
        val extension = try {
            VehicleMediaRuntime.create(context, { command ->
                next.sendMediaButton(when (command) {
                    MediaCommand.PLAY -> CarPlayMediaButton.PLAY
                    MediaCommand.PAUSE -> CarPlayMediaButton.PAUSE
                    MediaCommand.NEXT -> CarPlayMediaButton.NEXT
                    MediaCommand.PREVIOUS -> CarPlayMediaButton.PREVIOUS
                })
            }, { message ->
                Log.e("DiPlay-Vehicle", message)
                com.shilapi.xcertplay.pioneer.PioneerDebugRuntime.log(context, message)
            })
        } catch (error: Throwable) {
            recovery?.close()
            throw error
        }
        binding = Binding(next, extension, recovery)
        next.vehicleSessionListener = extension?.let { selected ->
            { token, active ->
                selected.sessionChanged(token, active)
                if (active) selected.nowPlaying(snapshot(next.nowPlayingSnapshot()))
            }
        }
        next.activeSessionIdentity()?.let { next.vehicleSessionListener?.invoke(it, true) }
    }

    @Synchronized
    fun detach(expected: CarPlayController) {
        val current = binding?.takeIf { it.controller === expected } ?: return
        binding = null
        current.close()
    }

    @Synchronized
    fun nowPlaying(expected: CarPlayController, update: CarPlayNowPlaying) {
        binding?.takeIf { it.controller === expected }?.runtime?.nowPlaying(snapshot(update))
    }

    private fun snapshot(update: CarPlayNowPlaying) = NowPlayingSnapshot(
        title = update.title.orEmpty(), artist = update.artist.orEmpty(), album = update.album.orEmpty(),
        durationMillis = update.durationMillis?.coerceAtLeast(0) ?: 0,
        elapsedMillis = update.elapsedMillis?.coerceAtLeast(0) ?: 0, playing = update.playing,
        positionKnown = update.elapsedMillis != null,
    )
}
