package com.shilapi.xcertplay.btprobe

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class ProbeReport(
    context: Context,
    fileName: String = "reports/bluetooth-negotiation.txt",
    externalName: String = "bluetooth-negotiation.txt",
) {
    val file = File(context.filesDir, fileName)
    private val external = context.getExternalFilesDir(null)?.let { File(it, externalName) }
    @Synchronized fun read(): String = if (file.exists()) file.readText() else ""
    @Synchronized fun append(message: String): String {
        file.parentFile!!.mkdirs()
        val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
        val content = (read() + "\n[$time] $message\n").takeLast(131072)
        file.writeText(content)
        runCatching { external?.writeText(content) }
        return content
    }
}
