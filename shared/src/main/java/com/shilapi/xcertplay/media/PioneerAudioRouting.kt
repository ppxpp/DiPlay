package com.shilapi.xcertplay.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.view.KeyEvent

/** Pioneer firmware encodes both its output bus and MediaManager token in contentType. */
internal object PioneerAudioRouting {
    val available: Boolean by lazy {
        Build.VERSION.SDK_INT == 25 && runCatching {
            AudioManager::class.java.getField("STREAM_TAI_WECARFLOW_MEDIA").getInt(null) == 401 &&
                AudioManager::class.java.getField("STREAM_AMAP_NAVI_GUIDE").getInt(null) == 400 &&
                AudioTrack::class.java.getMethod("setTokenName", String::class.java) != null
        }.getOrDefault(false)
    }

    fun streamType(channel: AudioChannel): Int? = if (!available) null else when (channel) {
        AudioChannel.MEDIA -> 401 // FRONT bus, com.tencent.wecarflow.media token
        else -> 400 // MIX bus, same navigation token/path as Amap
    }

    fun focusStream(attributes: AudioAttributes): Int? =
        attributes.contentType.takeIf { available && it in 400..401 }

    /** Audio focus alone cannot switch from FM/BT to TAI_AUDIO on this firmware. */
    fun selectMediaSource(report: (String) -> Unit) {
        if (!available) return
        runCatching {
            val controllerClass = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
            val controller = controllerClass.getMethod("getInstance").invoke(null)
            val current = controllerClass.getMethod("getCurSource", Int::class.javaPrimitiveType)
                .invoke(controller, 1) as Int
            if (current != 300) {
                val result = controllerClass.getMethod("change2Source", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType).invoke(controller, 1, 300)
                report("Audio: Pioneer media source previous=$current requested=300 result=$result")
            }
        }.onFailure {
            report("Audio: Pioneer media source selection failed: ${it.javaClass.simpleName}")
        }
    }

    /** The factory player shares source 300; pause it rather than mixing two music players. */
    fun pauseSourcePlayer(context: Context?, report: (String) -> Unit) {
        if (!available || context == null) return
        runCatching {
            context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName("com.tencent.wecarflow",
                    "com.tencent.wecarflow.media.service.MediaButtonIntentReceiver")
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            })
            report("Audio: Pioneer factory media player pause dispatched")
        }.onFailure { report("Audio: Pioneer factory player pause failed: ${it.javaClass.simpleName}") }
    }
}
