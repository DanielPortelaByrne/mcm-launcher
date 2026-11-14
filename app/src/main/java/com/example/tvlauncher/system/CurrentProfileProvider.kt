package com.example.tvlauncher.system

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Tells other apps (Cinema) who was picked on "Who's watching?", so they can open as the same person. Read-only, one
 * row: the account's address as [id], its friendly name, and when it was picked. No row until someone has been picked.
 * Reading needs com.example.tvlauncher.permission.CURRENT_PROFILE.
 */
class CurrentProfileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(arrayOf("id", "name", "changedAt"))
        val ctx = context ?: return cursor
        val repo = AccountsRepository(ctx)
        val picked = repo.picked() ?: return cursor
        cursor.addRow(arrayOf<Any?>(picked.email, picked.label, repo.pickedAt))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.com.example.tvlauncher.profile"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
