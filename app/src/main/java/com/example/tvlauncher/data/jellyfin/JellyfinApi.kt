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

/** One scheduled programme on a live channel. */
data class Programme(val id: String, val channelId: String, val title: String, val overview: String?, val startMs: Long, val endMs: Long, val year: Int?)

/** A live channel from Jellyfin's Live TV (the owner's own Tunarr channels). [nowPlaying] comes from the guide. */
data class LiveChannel(
    val id: String,
    val name: String,
    val number: String?,
    val nowPlaying: String?,
    val nowEndsAtMs: Long?,
    val hasImage: Boolean
)

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

    // --- Programme guide -------------------------------------------------------------------------

    private fun isoUtc(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    /** Everything on any channel that ends after [fromMs] and starts before [toMs], in start order. */
    fun programsUrl(base: String, userId: String, fromMs: Long, toMs: Long): String =
        "$base/LiveTv/Programs?userId=${enc(userId)}&minEndDate=${enc(isoUtc(fromMs))}&maxStartDate=${enc(isoUtc(toMs))}" +
            "&sortBy=StartDate&Fields=Overview&limit=3000"

    fun parsePrograms(json: String): List<Programme> = try {
        val array = JSONObject(json).optJSONArray("Items")
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val o = array!!.getJSONObject(i)
            val channel = o.optString("ChannelId").ifBlank { return@mapNotNull null }
            val start = utcMillis(o.optString("StartDate")) ?: return@mapNotNull null
            val end = utcMillis(o.optString("EndDate")) ?: return@mapNotNull null
            Programme(
                id = o.optString("Id"), channelId = channel, title = o.optString("Name"),
                overview = o.optString("Overview").ifBlank { null }, startMs = start, endMs = end,
                year = o.optInt("ProductionYear", 0).takeIf { it > 0 }
            )
        }.sortedBy { it.startMs }
    } catch (_: Exception) { emptyList() }

    // --- Live TV ---------------------------------------------------------------------------------

    fun channelsUrl(base: String, userId: String): String =
        "$base/LiveTv/Channels?userId=${enc(userId)}&AddCurrentProgram=true&SortBy=SortName"

    fun playbackInfoUrl(base: String, userId: String, channelId: String): String =
        "$base/Items/${enc(channelId)}/PlaybackInfo?UserId=${enc(userId)}&AutoOpenLiveStream=true"

    /** Asks for an HLS copy this TV can decode, which is all a live channel is offered as. */
    val PLAYBACK_INFO_BODY: String = JSONObject()
        .put("AutoOpenLiveStream", true)
        .put("DeviceProfile", JSONObject()
            .put("MaxStreamingBitrate", 20_000_000)
            .put("DirectPlayProfiles", org.json.JSONArray())
            .put("TranscodingProfiles", org.json.JSONArray().put(JSONObject()
                .put("Container", "ts").put("Type", "Video").put("AudioCodec", "aac").put("VideoCodec", "h264")
                .put("Protocol", "hls").put("Context", "Streaming"))))
        .toString()

    /** The playable address Jellyfin hands back for a live channel, made absolute. Null when it offered none. */
    fun parseLiveStreamUrl(base: String, json: String): String? = try {
        val sources = JSONObject(json).optJSONArray("MediaSources")
        val path = (0 until (sources?.length() ?: 0)).firstNotNullOfOrNull { sources!!.getJSONObject(it).optString("TranscodingUrl").ifBlank { null } }
        path?.let { if (it.startsWith("http")) it else base + (if (it.startsWith("/")) it else "/$it") }
    } catch (_: Exception) { null }

    fun parseChannels(json: String): List<LiveChannel> = try {
        val array = JSONObject(json).optJSONArray("Items")
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val o = array!!.getJSONObject(i)
            if (o.optString("Type") != "TvChannel") return@mapNotNull null
            val id = o.optString("Id").ifBlank { return@mapNotNull null }
            val now = o.optJSONObject("CurrentProgram")
            LiveChannel(
                id = id,
                name = o.optString("Name"),
                number = o.optString("Number").ifBlank { null },
                nowPlaying = now?.optString("Name")?.ifBlank { null },
                nowEndsAtMs = now?.optString("EndDate")?.let { utcMillis(it) },
                hasImage = o.optJSONObject("ImageTags")?.has("Primary") == true
            )
        }
    } catch (_: Exception) { emptyList() }

    /** Jellyfin's timestamps look like 2026-10-20T12:51:00.0000000Z; seconds are all the guide needs. */
    fun utcMillis(stamp: String): Long? = try {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        f.parse(stamp.take(19))?.time
    } catch (_: Exception) { null }

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
