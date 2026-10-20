package com.example.tvlauncher.data.movies

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "Seerr"

/** Where the request service answers, next to the media server on the Deck. */
const val DEFAULT_SEERR = "http://192.168.1.233:5055"

/** [PROCESSING] is Seerr's own word: the request is with Radarr or Sonarr, which may not have found anything yet. Only [DOWNLOADING] means bytes are arriving. */
enum class Availability { NONE, REQUESTED, PROCESSING, DOWNLOADING, PARTIAL, AVAILABLE }

enum class RequestOutcome { QUEUED, ALREADY, NEEDS_SIGN_IN, FAILED }

/** The plain-Kotlin half of the Seerr client, so request bodies and answers can be tested. */
object SeerrApi {
    fun signInBody(username: String, password: String): String =
        JSONObject().put("username", username).put("password", password).toString()

    /** Films are asked for outright. A series asks for the given [seasons], or for every season when none are named. Seerr cannot ask for single episodes. */
    fun requestBody(kind: Kind, tmdbId: Long, seasons: List<Int>? = null): String {
        val o = JSONObject().put("mediaType", if (kind == Kind.MOVIE) "movie" else "tv").put("mediaId", tmdbId)
        if (kind == Kind.SHOW) o.put("seasons", if (seasons.isNullOrEmpty()) "all" else org.json.JSONArray(seasons))
        return o.toString()
    }

    fun path(kind: Kind, tmdbId: Long) = (if (kind == Kind.MOVIE) "/api/v1/movie/" else "/api/v1/tv/") + tmdbId

    /** Seerr's media status numbers: 1 unknown, 2 pending, 3 processing, 4 partly available, 5 available. */
    fun parseAvailability(json: String): Availability = try {
        val info = JSONObject(json).optJSONObject("mediaInfo")
        fun of(status: Int?, downloading: Boolean) = when (status) {
            2 -> Availability.REQUESTED
            3 -> if (downloading) Availability.DOWNLOADING else Availability.PROCESSING
            4 -> Availability.PARTIAL
            5 -> Availability.AVAILABLE
            else -> Availability.NONE
        }
        of(info?.optInt("status", 1), (info?.optJSONArray("downloadStatus")?.length() ?: 0) > 0)
    } catch (_: Exception) { Availability.NONE }

    /** Where one season stands, from the media record Seerr keeps once something has been asked for. */
    fun seasonAvailability(status: Int): Availability = when (status) { 2 -> Availability.REQUESTED; 3 -> Availability.PROCESSING; 4 -> Availability.PARTIAL; 5 -> Availability.AVAILABLE; else -> Availability.NONE }

    fun outcomeFor(httpCode: Int): RequestOutcome = when (httpCode) {
        200, 201, 202 -> RequestOutcome.QUEUED
        409 -> RequestOutcome.ALREADY
        401, 403 -> RequestOutcome.NEEDS_SIGN_IN
        else -> RequestOutcome.FAILED
    }

    /** The session cookie out of a sign-in answer's Set-Cookie headers, as it should be sent back. */
    fun sessionCookie(setCookie: List<String>?): String? =
        setCookie?.firstOrNull { it.trim().startsWith("connect.sid=", ignoreCase = true) }?.trim()?.substringBefore(';')

    /** Every Set-Cookie value, however the connection spelled the header name (some stacks lower-case them). */
    fun setCookies(headers: Map<String?, List<String>>?): List<String> =
        headers?.entries?.filter { it.key.equals("Set-Cookie", ignoreCase = true) }?.flatMap { it.value }.orEmpty()
}

/**
 * Asks the owner's Seerr to download a title. The TV signs in once with the owner's Jellyfin account, typed on the
 * TV; only the session cookie is kept (private storage), never the password. Every call blocks and none throw.
 */
class SeerrClient(context: Context) {
    private val prefs = context.getSharedPreferences("seerr", Context.MODE_PRIVATE)
    private val base: String get() = prefs.getString("server", null) ?: DEFAULT_SEERR
    val isSignedIn: Boolean get() = !prefs.getString("cookie", null).isNullOrBlank()

    fun signOut() { prefs.edit().remove("cookie").apply() }

    /** Null on success, otherwise a short reason to show the owner. */
    fun signIn(username: String, password: String): String? = try {
        val c = open("POST", "$base/api/v1/auth/jellyfin", withCookie = false)
        c.doOutput = true
        try {
            c.outputStream.use { it.write(SeerrApi.signInBody(username, password).toByteArray()) }
            when (val code = c.responseCode) {
                200 -> SeerrApi.sessionCookie(SeerrApi.setCookies(c.headerFields))?.let { prefs.edit().putString("cookie", it).apply(); null }
                    ?: run { Log.w(TAG, "signed in but no session cookie; headers=${c.headerFields.keys.filterNotNull()} cookies=${SeerrApi.setCookies(c.headerFields).map { it.substringBefore('=') }}"); "Signed in, but no session came back" }
                401, 403 -> "Wrong username or password"
                else -> "Couldn't sign in (HTTP $code)"
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "signIn failed: ${e.javaClass.simpleName}"); "Couldn't reach the request service" }

    /** Asks for a film, or for [seasons] of a series (every season when none are named). */
    fun request(kind: Kind, tmdbId: Long, seasons: List<Int>? = null): RequestOutcome = try {
        val c = open("POST", "$base/api/v1/request")
        c.doOutput = true
        try {
            c.outputStream.use { it.write(SeerrApi.requestBody(kind, tmdbId, seasons).toByteArray()) }
            val code = c.responseCode
            if (code == 401) signOut()
            SeerrApi.outcomeFor(code)
        } finally { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "request failed: ${e.javaClass.simpleName}"); RequestOutcome.FAILED }

    fun availability(kind: Kind, tmdbId: Long): Availability = try {
        val c = open("GET", base + SeerrApi.path(kind, tmdbId))
        try { if (c.responseCode == 200) SeerrApi.parseAvailability(c.inputStream.bufferedReader().use { it.readText() }) else Availability.NONE } finally { c.disconnect() }
    } catch (_: Exception) { Availability.NONE }

    /** Everything the owner has asked for, newest first. Empty when not signed in or Seerr cannot be reached. */
    fun requests(): List<SeerrRequest> = get("/api/v1/request?take=30&skip=0&sort=added")?.let { SeerrRequests.parse(it) }.orEmpty()

    fun seasons(tmdbId: Long): List<SeasonInfo> = get(SeerrApi.path(Kind.SHOW, tmdbId))?.let { SeerrSeasons.parseSeasons(it) }.orEmpty()

    fun episodes(tmdbId: Long, season: Int): List<EpisodeInfo> = get(SeerrApi.path(Kind.SHOW, tmdbId) + "/season/$season")?.let { SeerrSeasons.parseEpisodes(it) }.orEmpty()

    fun titleInfo(kind: Kind, tmdbId: Long): Pair<String, String?>? = get(SeerrApi.path(kind, tmdbId))?.let { SeerrRequests.parseTitle(it) }

    /** Free space where films are stored, in bytes, or null. */
    fun freeSpaceBytes(): Long? = get("/api/v1/service/radarr/0")?.let { SeerrRequests.parseFreeSpace(it) }

    private fun get(path: String): String? = try {
        val c = open("GET", base + path)
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else { if (c.responseCode == 401) signOut(); null } } finally { c.disconnect() }
    } catch (_: Exception) { null }

    private fun open(method: String, url: String, withCookie: Boolean = true): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 6000; c.readTimeout = 12000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "application/json")
        if (withCookie) prefs.getString("cookie", null)?.let { c.setRequestProperty("Cookie", it) }
        return c
    }
}

// --- Requests and where they have got to --------------------------------------------------------

/** One download Radarr or Sonarr is working on, as Seerr reports it. A series can have several (one per episode). */
data class Download(val sizeBytes: Long, val sizeLeftBytes: Long, val etaMs: Long?)

enum class Stage { WAITING_APPROVAL, SEARCHING, DOWNLOADING, READY, DECLINED, FAILED }

/** A request the owner has made, with how far along it is. */
data class SeerrRequest(val id: Int, val kind: Kind, val tmdbId: Long, val stage: Stage, val downloads: List<Download>, val requestedAtMs: Long?) {
    val totalBytes: Long get() = downloads.sumOf { it.sizeBytes }
    val leftBytes: Long get() = downloads.sumOf { it.sizeLeftBytes }

    /** 0..1 across everything being fetched for this title, or null when nothing has started. */
    fun progress(): Float? = if (stage == Stage.READY) 1f else if (totalBytes > 0) ((totalBytes - leftBytes).toFloat() / totalBytes).coerceIn(0f, 1f) else null

    /** The soonest-finishing estimate is no use for a series; the latest one is when it is all done. */
    fun etaMs(): Long? = downloads.mapNotNull { it.etaMs }.maxOrNull()
}

object SeerrRequests {
    /** Reads Seerr's request list. Request status: 1 awaiting approval, 2 approved, 3 declined, 4 failed, 5 completed. Media status 5 is available. */
    fun parse(json: String): List<SeerrRequest> = try {
        val results = JSONObject(json).getJSONArray("results")
        (0 until results.length()).mapNotNull { i ->
            val r = results.getJSONObject(i)
            val media = r.optJSONObject("media") ?: return@mapNotNull null
            val tmdb = media.optLong("tmdbId", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            val queue = media.optJSONArray("downloadStatus")
            val downloads = (0 until (queue?.length() ?: 0)).map { k ->
                val d = queue!!.getJSONObject(k)
                Download(d.optLong("size", 0L), d.optLong("sizeLeft", 0L), d.optString("estimatedCompletionTime").takeIf { it.isNotBlank() }?.let { com.example.tvlauncher.data.jellyfin.JellyfinApi.utcMillis(it) })
            }
            val stage = when {
                r.optInt("status") == 3 -> Stage.DECLINED
                r.optInt("status") == 4 -> Stage.FAILED
                media.optInt("status") == 5 || r.optInt("status") == 5 -> Stage.READY
                r.optInt("status") == 1 -> Stage.WAITING_APPROVAL
                downloads.isNotEmpty() -> Stage.DOWNLOADING
                else -> Stage.SEARCHING
            }
            SeerrRequest(
                r.optInt("id"), if (r.optString("type") == "tv") Kind.SHOW else Kind.MOVIE, tmdb, stage, downloads,
                r.optString("createdAt").takeIf { it.isNotBlank() }?.let { com.example.tvlauncher.data.jellyfin.JellyfinApi.utcMillis(it) }
            )
        }
    } catch (_: Exception) { emptyList() }

    /** Free space on the first root folder Radarr reports, in bytes. */
    fun parseFreeSpace(json: String): Long? = try {
        val folders = JSONObject(json).optJSONArray("rootFolders")
        folders?.optJSONObject(0)?.optLong("freeSpace", -1L)?.takeIf { it >= 0 }
    } catch (_: Exception) { null }

    /** The title and poster Seerr knows for a TMDB id. */
    fun parseTitle(json: String): Pair<String, String?>? = try {
        val o = JSONObject(json)
        val name = o.optString("title").ifBlank { o.optString("name") }.ifBlank { null }
        name?.let { it to o.optString("posterPath").takeIf { p -> p.isNotBlank() }?.let { p -> "https://image.tmdb.org/t/p/w185$p" } }
    } catch (_: Exception) { null }
}

/** "10.8 GB", "601 GB", "1.2 TB": three digits at most, so it stays small on screen. */
object ByteSize {
    fun format(bytes: Long): String {
        val gb = bytes / 1e9
        return when {
            gb >= 1000 -> String.format(java.util.Locale.UK, "%.1f TB", gb / 1000)
            gb >= 100 -> String.format(java.util.Locale.UK, "%.0f GB", gb)
            gb >= 1 -> String.format(java.util.Locale.UK, "%.1f GB", gb)
            else -> String.format(java.util.Locale.UK, "%.0f MB", bytes / 1e6)
        }
    }
}

// --- Seasons and episodes ----------------------------------------------------------------------

/** A season of a series with where it stands. Season 0 (specials) is left out by [SeerrSeasons.parseSeasons]. */
data class SeasonInfo(val number: Int, val name: String, val episodeCount: Int, val availability: Availability)

data class EpisodeInfo(val season: Int, val number: Int, val name: String, val overview: String?, val airDate: String?)

object SeerrSeasons {
    fun parseSeasons(json: String): List<SeasonInfo> = try {
        val root = JSONObject(json)
        val tracked = root.optJSONObject("mediaInfo")?.optJSONArray("seasons")
        val status = HashMap<Int, Int>()
        for (i in 0 until (tracked?.length() ?: 0)) tracked!!.getJSONObject(i).let { status[it.optInt("seasonNumber")] = it.optInt("status", 1) }
        val seasons = root.getJSONArray("seasons")
        (0 until seasons.length()).map { seasons.getJSONObject(it) }
            .filter { it.optInt("seasonNumber") > 0 }
            .map { SeasonInfo(it.optInt("seasonNumber"), it.optString("name").ifBlank { "Season ${it.optInt("seasonNumber")}" }, it.optInt("episodeCount"), SeerrApi.seasonAvailability(status[it.optInt("seasonNumber")] ?: 1)) }
    } catch (_: Exception) { emptyList() }

    fun parseEpisodes(json: String): List<EpisodeInfo> = try {
        val eps = JSONObject(json).getJSONArray("episodes")
        (0 until eps.length()).map { eps.getJSONObject(it) }.map {
            EpisodeInfo(it.optInt("seasonNumber"), it.optInt("episodeNumber"), it.optString("name").ifBlank { "Episode ${it.optInt("episodeNumber")}" },
                it.optString("overview").ifBlank { null }, it.optString("airDate").ifBlank { null })
        }
    } catch (_: Exception) { emptyList() }
}
