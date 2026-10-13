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
    private var controller: CarPlayController? = null
    private var runtime: VehicleMediaRuntime? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller === next) return
        controller?.vehicleSessionListener = null
        runtime?.close()
        controller = next
        val extension = VehicleMediaRuntime.create(context, { command ->
            next.sendMediaButton(when (command) {
                MediaCommand.PLAY -> CarPlayMediaButton.PLAY
                MediaCommand.PAUSE -> CarPlayMediaButton.PAUSE
                MediaCommand.NEXT -> CarPlayMediaButton.NEXT
                MediaCommand.PREVIOUS -> CarPlayMediaButton.PREVIOUS
            })
        }, { message -> Log.e("DiPlay-Vehicle", message) })
        runtime = extension
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
        if (controller !== expected) return
        expected.vehicleSessionListener = null
        controller = null
        runtime?.close()
        runtime = null
    }

    @Synchronized
    fun nowPlaying(expected: CarPlayController, update: CarPlayNowPlaying) {
        if (controller === expected) runtime?.nowPlaying(snapshot(update))
    }

    private fun snapshot(update: CarPlayNowPlaying) = NowPlayingSnapshot(
        title = update.title.orEmpty(), artist = update.artist.orEmpty(), album = update.album.orEmpty(),
        durationMillis = update.durationMillis?.coerceAtLeast(0) ?: 0,
        elapsedMillis = update.elapsedMillis?.coerceAtLeast(0) ?: 0, playing = update.playing,
        positionKnown = update.elapsedMillis != null,
    )
}
