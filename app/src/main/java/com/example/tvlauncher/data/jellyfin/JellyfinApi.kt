package com.example.tvlauncher.data.jellyfin

import org.json.JSONObject
import java.net.URLEncoder

/** One film or show in the Jellyfin library. [resumeMs] is where the signed-in user left off (0 when unwatched). */
data class JellyfinItem(
    val id: String,
    val name: String,
    val year: Int?,
    val type: String,
    val mediaSourceId: String?,
    val resumeMs: Long = 0L,
    val played: Boolean = false,
    val imdbId: String? = null
) {
    /** Jellyfin names a single-file film's source after the film itself, so that is the safe default. */
    val sourceId: String get() = mediaSourceId ?: id

    val isMovie: Boolean get() = type == "Movie"
}

/**
 * The plain-Kotlin half of the Jellyfin client: how requests are written and responses read. Everything that
 * touches the network or storage lives in [JellyfinClient], so this part can be unit tested.
 */
object JellyfinApi {
    const val CLIENT = "MCM Home"
    const val VERSION = "1.0"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** The Authorization header Jellyfin expects. [token] is omitted before sign-in. */
    fun authHeader(deviceId: String, deviceName: String, token: String? = null): String =
        "MediaBrowser Client=\"$CLIENT\", Device=\"${deviceName.replace('"', '\'')}\", DeviceId=\"$deviceId\", Version=\"$VERSION\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    fun cleanBase(server: String): String {
        val t = server.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "http://$t"
    }

    /**
     * Every film that has an IMDb id, with its provider ids. Matching happens on the TV ([findByImdb]) because the
     * server's own provider-id filter (AnyProviderIdEquals) is ignored by Jellyfin 12.2: asking for one film
     * returns the whole library, so trusting its first answer would "find" the wrong film every time.
     */
    fun libraryMoviesUrl(base: String, userId: String): String =
        "$base/Items?userId=${enc(userId)}&Recursive=true&IncludeItemTypes=Movie&HasImdbId=true&Fields=ProviderIds&Limit=5000"

    /** The film with this IMDb id, or null. Never falls back to a title match or "the first result". */
    fun findByImdb(items: List<JellyfinItem>, imdbId: String): JellyfinItem? =
        items.firstOrNull { it.isMovie && it.imdbId.equals(imdbId, ignoreCase = true) }

    /** The raw bytes of the film as stored. The token rides in a header, never in the address. */
    fun directStreamUrl(base: String, item: JellyfinItem): String =
        "$base/Videos/${item.id}/stream?static=true&mediaSourceId=${enc(item.sourceId)}"

    /** A copy Jellyfin re-encodes for this TV, used when the original file will not play as stored. */
    fun transcodeUrl(base: String, item: JellyfinItem, deviceId: String): String =
        "$base/Videos/${item.id}/master.m3u8?DeviceId=${enc(deviceId)}&MediaSourceId=${enc(item.sourceId)}" +
            "&VideoCodec=h264&AudioCodec=aac&MaxStreamingBitrate=20000000&TranscodingMaxAudioChannels=6&SegmentContainer=ts"

    fun posterUrl(base: String, id: String, maxHeight: Int = 450): String = "$base/Items/${enc(id)}/Images/Primary?maxHeight=$maxHeight&quality=90"

    fun parseItems(json: String): List<JellyfinItem> = try {
        val array = JSONObject(json).optJSONArray("Items")
        (0 until (array?.length() ?: 0)).mapNotNull { parseItem(array!!.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun parseItem(o: JSONObject): JellyfinItem? {
        val id = o.optString("Id").ifBlank { return null }
        val sources = o.optJSONArray("MediaSources")
        val source = if (sources != null && sources.length() > 0) sources.optJSONObject(0)?.optString("Id")?.ifBlank { null } else null
        val data = o.optJSONObject("UserData")
        return JellyfinItem(
            id = id,
            name = o.optString("Name"),
            year = o.optInt("ProductionYear", 0).takeIf { it > 0 },
            type = o.optString("Type"),
            mediaSourceId = source,
            // Jellyfin counts in 100-nanosecond ticks.
            resumeMs = (data?.optLong("PlaybackPositionTicks", 0L) ?: 0L) / 10_000L,
            played = data?.optBoolean("Played", false) ?: false,
            imdbId = o.optJSONObject("ProviderIds")?.optString("Imdb")?.ifBlank { null }
        )
    }
}
