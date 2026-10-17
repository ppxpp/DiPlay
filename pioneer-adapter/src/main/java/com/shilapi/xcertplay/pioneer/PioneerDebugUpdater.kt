package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.Socket
import java.security.MessageDigest

/** Bounded APK upload and validation; the two allowed packages retain installed-signature checks. */
internal class PioneerDebugUpdater(private val context: Context) {
    fun receive(socket: Socket, request: JSONObject): JSONObject {
        val size = request.getLong("size")
        require(size in 1..128L * 1024 * 1024) { "APK size invalid" }
        val token = PioneerDebugUpdateGuard.begin()
        var file: File? = null
        val handoffLock = Any()
        var handoffAllowed = true
        var installerStarted = false
        try {
            val directory = File(context.cacheDir, "pioneer-update").apply { mkdirs() }
            directory.listFiles()?.filter { it.name.endsWith(".apk") && PioneerDebugUpdateGuard.canDelete(it) && System.currentTimeMillis() - it.lastModified() > 86_400_000L }
                ?.forEach { if (it.delete()) File(it.path + ".installing").delete() }
            val upload = File.createTempFile("diplay-", ".apk", directory).also { file = it }
            val sha = MessageDigest.getInstance("SHA-256")
            upload.outputStream().use { output ->
                val input = socket.getInputStream()
                val buffer = ByteArray(65536)
                var remaining = size
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    require(count > 0) { "APK upload interrupted" }
                    output.write(buffer, 0, count); sha.update(buffer, 0, count); remaining -= count
                }
            }
            // A local/UI start during upload is also caught, even if it bypasses debug control.
            PioneerDebugUpdateGuard.requireIdle()
            val manager = context.packageManager
            val info = manager.getPackageArchiveInfo(upload.absolutePath, PackageManager.GET_SIGNATURES) ?: error("Invalid APK")
            require(info.packageName in listOf(context.packageName, "com.tencent.wecarflow")) { "APK package mismatch" }
            val current = manager.getPackageInfo(info.packageName, PackageManager.GET_SIGNATURES)
            require(!info.signatures.isNullOrEmpty() && info.signatures?.toList() == current.signatures?.toList()) { "APK signature mismatch" }
            check(!socket.isClosed) { "Update request expired" }
            PioneerDebugUpdateGuard.handoff(token, upload) {
                synchronized(handoffLock) {
                    check(handoffAllowed && !socket.isClosed) { "Update request expired" }
                    context.startActivity(Intent(context, PioneerDebugActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("install_path", upload.absolutePath))
                    installerStarted = true
                }
            }
            PioneerDebugRuntime.log(context, "APK uploaded package=${info.packageName}; owner confirmation requested")
            return JSONObject().put("ok", true).put("package", info.packageName).put("versionCode", info.versionCode)
                .put("sha256", sha.digest().joinToString("") { "%02x".format(it) })
                .put("message", "APK 已完整接收并校验，请在车机确认安装；WeCarFlow装好后点完成，不打开主界面")
        } finally {
            synchronized(handoffLock) {
                // A timed-out main-thread task must never launch an APK already removed by cleanup.
                handoffAllowed = false
                if (!installerStarted) { file?.delete(); PioneerDebugUpdateGuard.release(token) }
            }
        }
    }
}
