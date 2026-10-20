package com.example.tvlauncher.data.jellyfin

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val TAG = "Jellyfin"

/** Where the media server answers on the home network. Change it from the connect sheet if the router hands out a new address. */
const val DEFAULT_JELLYFIN_SERVER = "http://192.168.1.233:8096"

/** A Quick Connect attempt in progress: [code] is what to type into an already signed-in Jellyfin. */
data class QuickConnectTicket(val secret: String, val code: String)

/**
 * Talks to the owner's Jellyfin server. Signed in once with Quick Connect (a short code approved from a
 * phone or browser), so no password ever reaches the TV; only the access token is kept, in private storage.
 * Every call here blocks, so callers run it off the main thread, and none of them throw.
 */
class JellyfinClient(context: Context) {
    private val prefs = context.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)

    private val deviceId: String = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    val server: String get() = prefs.getString("server", null) ?: DEFAULT_JELLYFIN_SERVER
    val isConnected: Boolean get() = !prefs.getString("token", null).isNullOrBlank() && !prefs.getString("userId", null).isNullOrBlank()
    private val token: String? get() = prefs.getString("token", null)
    private val userId: String? get() = prefs.getString("userId", null)

    /**
     * What a player needs to fetch a stream: the token travels in a header, never in the address. Jellyfin 12
     * answers 401 to the older X-Emby-Token header and to ?api_key=, so it has to be the Authorization header.
     */
    fun playbackHeaders(): Map<String, String> = token?.let { mapOf("Authorization" to JellyfinApi.authHeader(deviceId, deviceName, it)) }.orEmpty()

    fun streamUrl(item: JellyfinItem) = JellyfinApi.directStreamUrl(JellyfinApi.cleanBase(server), item)
    fun transcodeUrl(item: JellyfinItem) = JellyfinApi.transcodeUrl(JellyfinApi.cleanBase(server), item, deviceId)

    fun disconnect() { prefs.edit().remove("token").remove("userId").apply() }

    // --- Sign in ---------------------------------------------------------------------------------

    /** Null means the server could not be reached or has Quick Connect switched off; [error] says which. */
    fun startQuickConnect(address: String, error: (String) -> Unit): QuickConnectTicket? {
        val base = JellyfinApi.cleanBase(address)
        val enabled = request("GET", "$base/QuickConnect/Enabled", authed = false)
        if (enabled == null) { error("Couldn't reach Jellyfin at $base"); return null }
        if (!enabled.trim().equals("true", ignoreCase = true)) { error("Quick Connect is switched off on the server"); return null }
        val body = request("POST", "$base/QuickConnect/Initiate", authed = false, json = "{}") ?: run { error("Quick Connect didn't start"); return null }
        return try {
            val o = JSONObject(body)
            prefs.edit().putString("server", base).apply()
            QuickConnectTicket(o.getString("Secret"), o.getString("Code"))
        } catch (_: Exception) { error("Quick Connect gave an unexpected answer"); null }
    }

    /** True once the code has been approved on another device. */
    fun isApproved(ticket: QuickConnectTicket): Boolean {
        val body = request("GET", "${JellyfinApi.cleanBase(server)}/QuickConnect/Connect?secret=${java.net.URLEncoder.encode(ticket.secret, "UTF-8")}", authed = false) ?: return false
        return try { JSONObject(body).optBoolean("Authenticated", false) } catch (_: Exception) { false }
    }

    /** Trades an approved ticket for an access token. */
    fun finishQuickConnect(ticket: QuickConnectTicket): Boolean {
        val body = request("POST", "${JellyfinApi.cleanBase(server)}/Users/AuthenticateWithQuickConnect", authed = false,
            json = JSONObject().put("Secret", ticket.secret).toString()) ?: return false
        return try {
            val o = JSONObject(body)
            val newToken = o.getString("AccessToken")
            val id = o.getJSONObject("User").getString("Id")
            prefs.edit().putString("token", newToken).putString("userId", id).apply()
            true
        } catch (_: Exception) { false }
    }

    // --- Library ---------------------------------------------------------------------------------

    @Volatile private var library: List<JellyfinItem> = emptyList()
    @Volatile private var libraryAt = 0L

    /** Every film and series, newest first, kept for a few minutes so flicking through picks costs one request, not many. */
    fun library(force: Boolean = false): List<JellyfinItem> {
        val uid = userId ?: return emptyList()
        if (!force && libraryAt != 0L && System.currentTimeMillis() - libraryAt < LIBRARY_TTL_MS) return library
        val body = request("GET", JellyfinApi.libraryUrl(JellyfinApi.cleanBase(server), uid)) ?: return library
        library = JellyfinApi.parseItems(body); libraryAt = System.currentTimeMillis()
        return library
    }

    fun movies(force: Boolean = false): List<JellyfinItem> = library(force).filter { it.isMovie }

    /** The library copy of a title from elsewhere, matched on IMDb or TMDB id. */
    fun find(imdbId: String?, tmdbId: String?, series: Boolean): JellyfinItem? = JellyfinApi.findByIds(library(), imdbId, tmdbId, series)

    /** The episode to play for a series: the next unwatched one, else the first. Null when it has none or Jellyfin is unreachable. */
    fun episodeToPlay(seriesId: String): JellyfinItem? {
        val uid = userId ?: return null
        val base = JellyfinApi.cleanBase(server)
        val next = request("GET", JellyfinApi.nextUpUrl(base, uid, seriesId))?.let { JellyfinApi.parseItems(it).firstOrNull() }
        return next ?: request("GET", JellyfinApi.firstEpisodeUrl(base, uid, seriesId))?.let { JellyfinApi.parseItems(it).firstOrNull() }
    }

    /** The signed-in user's name, to save typing it into the request service. */
    fun userName(): String? = request("GET", JellyfinApi.cleanBase(server) + "/Users/Me")?.let { try { JSONObject(it).optString("Name").ifBlank { null } } catch (_: Exception) { null } }

    fun backdropUrl(id: String) = JellyfinApi.backdropUrl(JellyfinApi.cleanBase(server), id)

    /** The film in the library with this IMDb id, or null when there is none (or Jellyfin cannot be reached). */
    fun findMovieByImdb(imdbId: String): JellyfinItem? = JellyfinApi.findByImdb(movies(), imdbId)

    // --- Live TV ---------------------------------------------------------------------------------

    /** The owner's live channels with what is on now. Null when Jellyfin could not be reached, so "none yet" and "offline" are told apart. */
    fun liveChannels(): List<LiveChannel>? {
        val uid = userId ?: return null
        val body = request("GET", JellyfinApi.channelsUrl(JellyfinApi.cleanBase(server), uid)) ?: return null
        return JellyfinApi.parseChannels(body)
    }

    /** Opens the channel's stream on the server and returns the HLS address to play, or null. */
    fun liveStreamUrl(channelId: String): String? {
        val uid = userId ?: return null
        val base = JellyfinApi.cleanBase(server)
        val body = request("POST", JellyfinApi.playbackInfoUrl(base, uid, channelId), json = JellyfinApi.PLAYBACK_INFO_BODY) ?: return null
        return JellyfinApi.parseLiveStreamUrl(base, body)
    }

    /** The schedule for every channel between [fromMs] and [toMs]. Empty when there is none or Jellyfin cannot be reached. */
    fun guide(fromMs: Long, toMs: Long): List<Programme> {
        val uid = userId ?: return emptyList()
        val body = request("GET", JellyfinApi.programsUrl(JellyfinApi.cleanBase(server), uid, fromMs, toMs)) ?: return emptyList()
        return JellyfinApi.parsePrograms(body)
    }

    fun imageUrl(id: String, maxHeight: Int = 300) = JellyfinApi.posterUrl(JellyfinApi.cleanBase(server), id, maxHeight)

    private companion object { const val LIBRARY_TTL_MS = 5 * 60_000L }

    // --- Plumbing --------------------------------------------------------------------------------

    /** One quiet retry for reads: the TV's Wi-Fi drops a request now and then. */
    private fun request(method: String, url: String, authed: Boolean = true, json: String? = null): String? =
        requestOnce(method, url, authed, json) ?: if (method == "GET" && (!authed || isConnected)) requestOnce(method, url, authed, json) else null

    private fun requestOnce(method: String, url: String, authed: Boolean, json: String?): String? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 6000
        connection.readTimeout = 10000
        connection.setRequestProperty("Authorization", JellyfinApi.authHeader(deviceId, deviceName, if (authed) token else null))
        connection.setRequestProperty("Accept", "application/json")
        if (json != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json") }
        try {
            if (json != null) connection.outputStream.use { it.write(json.toByteArray()) }
            val code = connection.responseCode
            if (code == 401 && authed) { Log.w(TAG, "Token rejected; signing out"); disconnect(); null }
            else if (code !in 200..299) { Log.w(TAG, "$method ${url.substringBefore('?')} -> HTTP $code"); null }
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "$method ${url.substringBefore('?')} failed: ${e.javaClass.simpleName}"); null }
}
