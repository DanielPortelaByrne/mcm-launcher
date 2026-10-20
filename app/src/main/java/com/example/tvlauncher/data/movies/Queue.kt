package com.example.tvlauncher.data.movies

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "Queue"

enum class QueueSource { SONARR, RADARR }

/**
 * One thing Sonarr or Radarr has handed to the download client. [position] is the place in line among those still
 * waiting (1 is next), worked out from the order they were added, which is the order the download client takes
 * them in unless someone reorders it there; it is 0 for anything not waiting.
 */
data class QueueItem(
    val source: QueueSource, val id: Int, val title: String, val detail: String?, val tmdbId: Long,
    val status: String, val sizeBytes: Long, val sizeLeftBytes: Long, val etaSeconds: Long?, val addedMs: Long?, val position: Int = 0
) {
    val isDownloading: Boolean get() = status == "downloading"
    val isWaiting: Boolean get() = status == "queued" || status == "delayed" || status == "paused"

    /** 0..1 once bytes are known; null for something that has not started (its size is not known yet). */
    fun progress(): Float? = if (sizeBytes > 0) ((sizeBytes - sizeLeftBytes).toFloat() / sizeBytes).coerceIn(0f, 1f) else null
}

object QueueApi {
    /** Reads a queue page fetched with the series or movie and episode included. */
    fun parse(json: String, source: QueueSource): List<QueueItem> = try {
        val records = JSONObject(json).getJSONArray("records")
        (0 until records.length()).map { records.getJSONObject(it) }.map { r ->
            val series = r.optJSONObject("series"); val movie = r.optJSONObject("movie"); val ep = r.optJSONObject("episode")
            val title = (series?.optString("title") ?: movie?.optString("title")).orEmpty().ifBlank { r.optString("title") }
            QueueItem(
                source, r.optInt("id"), title,
                if (ep != null) "S${ep.optInt("seasonNumber")} E${ep.optInt("episodeNumber")}" + ep.optString("title").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() else movie?.optInt("year")?.takeIf { it > 0 }?.toString(),
                (series?.optLong("tmdbId") ?: movie?.optLong("tmdbId") ?: 0L),
                r.optString("status").lowercase(), r.optLong("size", 0L), r.optLong("sizeleft", 0L),
                timeLeftSeconds(r.optString("timeleft")), r.optString("added").takeIf { it.isNotBlank() }?.let { com.example.tvlauncher.data.jellyfin.JellyfinApi.utcMillis(it) }
            )
        }
    } catch (_: Exception) { emptyList() }

    /** "00:12:34" or "1.02:03:04" (days.hours:minutes:seconds) as seconds; null when there is none. */
    fun timeLeftSeconds(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val days = if ('.' in text.substringBefore(':')) text.substringBefore('.').toLongOrNull() ?: 0L else 0L
        val parts = (if (days > 0) text.substringAfter('.') else text).split(':').mapNotNull { it.substringBefore('.').toLongOrNull() }
        if (parts.size != 3) return null
        return days * 86_400 + parts[0] * 3600 + parts[1] * 60 + parts[2]
    }

    /** Numbers the items still waiting, oldest first, from 1; everything else keeps 0. */
    fun withPositions(items: List<QueueItem>): List<QueueItem> {
        val order = items.filter { it.isWaiting }.sortedBy { it.addedMs ?: Long.MAX_VALUE }.map { it.id to it.source }
        return items.map { i -> val at = order.indexOf(i.id to i.source); if (at >= 0) i.copy(position = at + 1) else i }
    }

    /** Downloading first, then waiting in line, then the rest. */
    fun ordered(items: List<QueueItem>): List<QueueItem> =
        items.sortedWith(compareBy<QueueItem> { if (it.isDownloading) 0 else if (it.isWaiting) 1 else 2 }.thenBy { if (it.position > 0) it.position else Int.MAX_VALUE }.thenBy { it.addedMs ?: Long.MAX_VALUE })

    /** Cancelling removes it from the download client too (and its partial files) without blocking the release. */
    fun cancelPath(id: Int) = "/api/v3/queue/$id?removeFromClient=true&blocklist=false"
}

/**
 * The live download queue, read from Sonarr and Radarr using the address and key Seerr already holds for them, and
 * the way to cancel an item. Every call blocks and none throw.
 */
class QueueClient(private val seerr: SeerrClient) {
    private var sonarr: SonarrConfig? = null
    private var radarr: SonarrConfig? = null

    private fun sonarrConfig() = sonarr ?: seerr.sonarrSettings()?.let { SonarrApi.parseConfig(it, seerr.host) }?.also { sonarr = it }
    private fun radarrConfig() = radarr ?: seerr.radarrSettings()?.let { SonarrApi.parseConfig(it, seerr.host) }?.also { radarr = it }

    /** Everything in both queues, numbered and in the order they will be dealt with. Null when neither could be reached. */
    fun items(): List<QueueItem>? {
        val s = sonarrConfig()?.let { c -> arrCall(c, "GET", "/api/v3/queue?pageSize=200&includeSeries=true&includeEpisode=true")?.let { QueueApi.parse(it, QueueSource.SONARR) } }
        val r = radarrConfig()?.let { c -> arrCall(c, "GET", "/api/v3/queue?pageSize=200&includeMovie=true")?.let { QueueApi.parse(it, QueueSource.RADARR) } }
        if (s == null && r == null) return null
        return QueueApi.ordered(QueueApi.withPositions(s.orEmpty() + r.orEmpty()))
    }

    fun cancel(item: QueueItem): Boolean {
        val c = (if (item.source == QueueSource.SONARR) sonarrConfig() else radarrConfig()) ?: return false
        return arrCall(c, "DELETE", QueueApi.cancelPath(item.id)) != null
    }
}

/** One call to Sonarr or Radarr with their key. Returns the body (or "{}" for an empty one), or null on any failure. */
internal fun arrCall(c: SonarrConfig, method: String, path: String, body: String? = null): String? = try {
    val conn = URL(c.baseUrl + path).openConnection() as HttpURLConnection
    conn.requestMethod = method; conn.connectTimeout = 6000; conn.readTimeout = 20000
    conn.setRequestProperty("X-Api-Key", c.apiKey); conn.setRequestProperty("Accept", "application/json")
    if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json") }
    try {
        if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        if (code in 200..299) (conn.inputStream.bufferedReader().use { it.readText() }.ifBlank { "{}" }) else { Log.w(TAG, "$method ${path.substringBefore('?')} -> HTTP $code"); null }
    } finally { conn.disconnect() }
} catch (e: Exception) { Log.w(TAG, "$method ${path.substringBefore('?')} failed: ${e.javaClass.simpleName}"); null }
