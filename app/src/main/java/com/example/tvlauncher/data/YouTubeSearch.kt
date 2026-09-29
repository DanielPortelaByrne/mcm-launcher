package com.example.tvlauncher.data

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Finds a thumbnail for a YouTube video from its title using the YouTube Data API. Used when an app
 * (SmartTube) reports what is playing but not which video it is. Needs a free API key, kept on the
 * TV only (see README); without one it does nothing.
 */
class YouTubeSearch(context: Context) {
    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)

    val hasKey: Boolean get() = !prefs.getString("apiKey", null).isNullOrBlank()

    /** Blocking. Returns the picture, or null when there is no key, no confident match, or the API refuses. */
    fun thumbnailFor(title: String, channel: String?): Bitmap? = find(title, channel)?.let { Thumbnails.download(listOf(it.thumbnailUrl)) }

    /** Blocking. The video that really is [title], or null. */
    fun find(title: String, channel: String?): VideoMatch? {
        val key = prefs.getString("apiKey", null)?.takeIf { it.isNotBlank() } ?: return null
        val url = "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=5&q=" +
            URLEncoder.encode(title, "UTF-8") + "&key=" + URLEncoder.encode(key, "UTF-8")
        val body = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 15000
            try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null } finally { c.disconnect() }
        } catch (_: Exception) { null } ?: return null
        return pickMatch(body, title, channel)
    }

    companion object {
        private val sizes = listOf("maxres", "standard", "high", "medium", "default")

        data class VideoMatch(val id: String, val thumbnailUrl: String)

        fun pickThumbnail(json: String, title: String, channel: String?): String? = pickMatch(json, title, channel)?.thumbnailUrl

        /** The thumbnail address of the result that really is [title] (and, when known, from [channel]); null if none is convincing. */
        fun pickMatch(json: String, title: String, channel: String?): VideoMatch? = try {
            val items = JSONObject(json).getJSONArray("items")
            val wanted = normalise(title)
            val want = channel?.let { normalise(it) }
            var best: Pair<Int, VideoMatch>? = null
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val videoId = item.optJSONObject("id")?.optString("videoId")?.takeIf { it.isNotBlank() }
                val snippet = item.optJSONObject("snippet") ?: continue
                val found = normalise(snippet.optString("title"))
                var score = when {
                    found == wanted -> 100
                    found.isNotEmpty() && (wanted.startsWith(found) || found.startsWith(wanted)) -> 70
                    else -> 0
                }
                if (score == 0) continue
                if (want != null && normalise(snippet.optString("channelTitle")) == want) score += 20
                val thumbs = snippet.optJSONObject("thumbnails") ?: continue
                val address = sizes.firstNotNullOfOrNull { thumbs.optJSONObject(it)?.optString("url")?.takeIf { u -> u.startsWith("http") } } ?: continue
                if (best == null || score > best.first) best = score to VideoMatch(videoId ?: continue, address)
            }
            best?.second
        } catch (_: Exception) { null }

        /** Lower-case, no accents, only letters and digits, so "Café  Tour!" matches "cafe tour". */
        fun normalise(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }
}
