package com.shihab.diplay.btproxy

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProxyLog {
    @Synchronized fun append(context: Context, message: String) {
        val line = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} $message"
        Log.i("BtProxyProbe", line)
        runCatching {
            val file = File(context.filesDir, "probe-log.txt")
            val previous = if (file.isFile) file.readLines().takeLast(199) else emptyList()
            file.writeText((previous + line).joinToString("\n") + "\n")
        }
    }
}
