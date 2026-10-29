package com.example.tvlauncher.data

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import java.io.File

/**
 * Cinema's own Continue watching, read through the provider the Cinema app publishes. The launcher keeps no copy of
 * these: they are read fresh each time, so they follow Cinema, and a title finished there drops off here too.
 */
object CinemaContinueWatching {
    const val PACKAGE = "com.danielportelabyrne.cinema"
    /** Cinema's permission to read the provider. It is normal-level, so it is granted when the launcher is installed. */
    const val PERMISSION = "$PACKAGE.permission.CONTINUE_WATCHING"
    private const val AUTHORITY = "$PACKAGE.continuewatching"
    private const val ACTION_RESUME = "$PACKAGE.action.RESUME"
    private const val EXTRA_KEY = "key"
    private const val CINEMA_ACTIVITY = "$PACKAGE.CinemaActivity"
    private const val ART_MAX_AGE_MS = 24L * 60 * 60 * 1000

    /** Rows from Cinema, most recent first, or empty when Cinema is not installed, not signed in or not answering. */
    fun read(context: Context): List<ResumeItem> {
        val cursor = try {
            context.contentResolver.query(Uri.parse("content://$AUTHORITY/items"), null, null, null, null)
        } catch (_: SecurityException) { null } catch (_: IllegalArgumentException) { null } ?: return emptyList()
        cursor.use { c ->
            val items = ArrayList<ResumeItem>()
            while (c.moveToNext()) {
                val key = c.getString(0) ?: continue
                val title = c.getString(1) ?: continue
                val art = c.getString(6)?.let { cachedArt(context, key, Uri.parse(it)) }
                items += ResumeItem(
                    title = title, subtitle = c.getString(2), packageName = PACKAGE, imageUri = art,
                    positionMs = c.getLong(3), durationMs = c.getLong(4), intentUri = null, lastEngagedMs = c.getLong(5),
                    payload = null, mediaId = key
                )
            }
            return items
        }
    }

    /** Opens Cinema at a row: the film or episode, or the stream it saved, where it was left. */
    fun resume(context: Context, key: String): Boolean {
        val intent = Intent(ACTION_RESUME)
            .setComponent(ComponentName(PACKAGE, CINEMA_ACTIVITY))
            .putExtra(EXTRA_KEY, key)
        return try { context.startActivity(intent); true } catch (_: Exception) { false }
    }

    /** Opens Cinema's own front page, the launcher's Movies. Returns false when Cinema is not installed. */
    fun open(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return try { context.startActivity(intent); true } catch (_: Exception) { false }
    }

    /** Cinema's artwork, copied into the launcher's cache so the card can draw it like any other picture. */
    private fun cachedArt(context: Context, key: String, uri: Uri): String? {
        val file = File(context.cacheDir, "cinema_art_${key.hashCode()}.jpg")
        if (file.exists() && file.length() > 0 && System.currentTimeMillis() - file.lastModified() < ART_MAX_AGE_MS) return file.absolutePath
        return try {
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            file.takeIf { it.length() > 0 }?.absolutePath
        } catch (_: Exception) { null }
    }
}
