package com.example.tvlauncher.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a still for a video from identifiers an app's media session exposes. YouTube serves thumbnails
 * for any video id at a fixed address, so no API key or account is needed. Returns nothing rather than guessing.
 */
object Thumbnails {
    private val youtubeId = Regex("(?:[?&]v=|youtu\\.be/|/vi/|/embed/|/shorts/|/live/)([A-Za-z0-9_-]{11})")
    private val bareId = Regex("^[A-Za-z0-9_-]{11}$")

    /** Image addresses to try, best first, from every id/uri the session gave. */
    fun candidates(ids: List<String>): List<String> {
        val out = mutableListOf<String>()
        ids.flatMap { it.lines() }.forEach { raw ->
            val v = raw.trim()
            if (v.startsWith("http") && !youtubeId.containsMatchIn(v) && Regex("\\.(jpe?g|png|webp)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(v)) out += v
            val id = youtubeId.find(v)?.groupValues?.get(1) ?: v.takeIf { bareId.matches(it) }
            if (id != null) { out += "https://i.ytimg.com/vi/$id/maxresdefault.jpg"; out += "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
        }
        return out.distinct()
    }

    /** Blocking. The first address that returns a real picture (not YouTube's tiny "no such size" placeholder). */
    fun download(urls: List<String>): Bitmap? {
        for (u in urls) try {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            val bmp = try { if (c.responseCode == 200) c.inputStream.use { BitmapFactory.decodeStream(it) } else null } finally { c.disconnect() }
            if (bmp != null && bmp.width >= 320) return bmp
        } catch (_: Exception) { /* try the next */ }
        return null
    }
}
