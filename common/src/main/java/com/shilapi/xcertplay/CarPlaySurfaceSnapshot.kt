package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only TCP evidence capture; never runs during timed gesture samples. */
internal object CarPlaySurfaceSnapshot {
    fun capture(surface: Surface?, width: Int, height: Int): ByteArray {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Snapshot requires debug worker" }
        check(Build.VERSION.SDK_INT >= 24 && surface?.isValid == true) { "Video surface unavailable" }
        val bitmap = Bitmap.createBitmap(width.coerceIn(1, 1920), height.coerceIn(1, 1080), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        try {
            PixelCopy.request(checkNotNull(surface), bitmap, { result = it; done.countDown() }, Handler(Looper.getMainLooper()))
        } catch (error: Throwable) { bitmap.recycle(); throw error }
        // On timeout leave recycling to GC after the pending callback; never recycle its target early.
        check(done.await(3, TimeUnit.SECONDS)) { "Video snapshot timed out" }
        try {
            check(result == PixelCopy.SUCCESS) { "Video snapshot failed code=$result" }
            return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
