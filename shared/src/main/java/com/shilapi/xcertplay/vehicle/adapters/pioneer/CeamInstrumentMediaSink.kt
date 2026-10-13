package com.shilapi.xcertplay.vehicle.adapters.pioneer

import com.shilapi.xcertplay.vehicle.api.InstrumentMediaSink
import com.shilapi.xcertplay.vehicle.api.NowPlayingSnapshot

/** Direct CEAM candidate. No source map changes or invented playlist/progress fields. */
internal class CeamInstrumentMediaSink(private val report: (String) -> Unit) : InstrumentMediaSink {
    private var failed = false
    private var lastText: List<String>? = null

    override fun publish(snapshot: NowPlayingSnapshot): Boolean {
        if (failed) return false
        val text = listOf(snapshot.title, snapshot.artist, snapshot.album)
        if (text == lastText) return true
        return try {
            val controllerType = Class.forName("jp.pioneer.ceam.MeterService.MeterServiceController")
            val infoType = Class.forName("jp.pioneer.ceam.MeterService.MeterAudioIQTInfo")
            val info = infoType.getConstructor().newInstance()
            infoType.getMethod("setTrackName", String::class.java).invoke(info, snapshot.title)
            infoType.getMethod("setArtistName", String::class.java).invoke(info, snapshot.artist)
            infoType.getMethod("setAlbumName", String::class.java).invoke(info, snapshot.album)
            val controller = controllerType.getMethod("getInstance").invoke(null)
            val accepted = controllerType.getMethod("setAudioIQTInfo", infoType).invoke(controller, info) == true
            if (accepted) lastText = text
            report("Vehicle: CEAM song text accepted=$accepted title=${snapshot.title}; display unverified")
            if (!accepted) failed = true
            accepted
        } catch (error: Exception) {
            failed = true
            report("Vehicle: CEAM instrument unavailable ${error.javaClass.simpleName}: ${error.message}")
            false
        }
    }

    override fun close() {
        if (lastText != null) publish(NowPlayingSnapshot())
        lastText = null
    }
}
