package com.shilapi.xcertplay

import android.content.Context
import android.os.Build

/**
 * Show once per installation; the display record survives restarts and APK updates.
 */
object Disclaimer {
    private const val PREFS = "diplay"
    private const val KEY_ACCEPTED_VERSION = "disclaimer_accepted_version"
    private const val KEY_SHOWN = "disclaimer_shown"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    /** Existing acceptance from any older build also counts as already shown. */
    fun acceptedForCurrentVersion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHOWN, false) || prefs(context).getLong(KEY_ACCEPTED_VERSION, -1L) >= 0

    fun markShown(context: Context) {
        prefs(context).edit().putBoolean(KEY_SHOWN, true).apply()
    }

    fun accept(context: Context) {
        prefs(context).edit().putBoolean(KEY_SHOWN, true).putLong(KEY_ACCEPTED_VERSION, currentVersionCode(context)).apply()
    }
}
