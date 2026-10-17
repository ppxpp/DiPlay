package com.shilapi.xcertplay.pioneer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Runs before any activity starts, so [ShortcutBarPolicy] observes the first process ON_START. Keeps
 * the app from needing a custom Application class.
 */
class ShortcutBarForegroundProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { ShortcutBarPolicy.attach(it.applicationContext) }
        return false
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
