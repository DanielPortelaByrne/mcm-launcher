package com.example.tvlauncher.data.movies

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "Sonarr"

/** Where Sonarr is and how to add a series to it, as Seerr has it configured. */
data class SonarrConfig(val baseUrl: String, val apiKey: String, val profileId: Int, val rootFolder: String, val seasonFolders: Boolean, val seriesType: String)

enum class EpisodeState { NONE, REQUESTED, DOWNLOADED }

enum class EpisodeOutcome { QUEUED, ALREADY, NOT_CONFIGURED, NOT_FOUND, FAILED }

/** The plain-Kotlin half of the Sonarr client: reading its answers and writing its requests. */
object SonarrApi {
    /**
     * Seerr's own Sonarr settings hold the address, key, quality profile and folder it uses, so a request made here lands
     * the same way one made in Seerr does. Seerr sees Sonarr as "localhost"; from the TV that is [seerrHost].
     */
    fun parseConfig(settingsJson: String, seerrHost: String): SonarrConfig? = try {
        val all = JSONArray(settingsJson)
        val servers = (0 until all.length()).map { all.getJSONObject(it) }.filter { !it.optBoolean("is4k") }
        val s = servers.firstOrNull { it.optBoolean("isDefault") } ?: servers.firstOrNull()
        s?.let {
            val host = it.optString("hostname").takeUnless { h -> h.isBlank() || h == "localhost" || h == "127.0.0.1" } ?: seerrHost
            val scheme = if (it.optBoolean("useSsl")) "https" else "http"
            SonarrConfig(
                "$scheme://$host:${it.optInt("port", 8989)}${it.optString("baseUrl")}".trimEnd('/'),
                it.getString("apiKey"), it.optInt("activeProfileId"), it.optString("activeDirectory"),
                it.optBoolean("enableSeasonFolders", true), it.optString("seriesType").ifBlank { "standard" }
            )
        }
    } catch (_: Exception) { null }

    /** Sonarr's own id for the series with this TMDB id, if it already has it. */
    fun findSeries(listJson: String, tmdbId: Long): Int? = try {
        val a = JSONArray(listJson)
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optLong("tmdbId") == tmdbId }?.optInt("id")?.takeIf { it > 0 }
    } catch (_: Exception) { null }

    /**
     * What to POST to add a series that Sonarr has not got yet. Nothing is monitored (`monitor: none`) and nothing is
     * searched for, so adding it does not start fetching every season; only the episodes asked for afterwards are.
     */
    fun addBody(lookup: JSONObject, config: SonarrConfig): String = JSONObject(lookup.toString())
        .put("qualityProfileId", config.profileId).put("rootFolderPath", config.rootFolder).put("monitored", true)
        .put("seasonFolder", config.seasonFolders).put("seriesType", config.seriesType)
        .put("addOptions", JSONObject().put("monitor", "none").put("searchForMissingEpisodes", false).put("searchForCutoffUnmetEpisodes", false))
        .toString()

    /** Sonarr's ids for the episodes of [season] numbered [numbers]. */
    fun episodeIds(episodesJson: String, season: Int, numbers: Collection<Int>): List<Int> = try {
        val a = JSONArray(episodesJson)
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("seasonNumber") == season && it.optInt("episodeNumber") in numbers }.map { it.getInt("id") }
    } catch (_: Exception) { emptyList() }

    /** Which episodes are on disk and which are wanted, keyed by (season, episode). */
    fun episodeStates(episodesJson: String): Map<Pair<Int, Int>, EpisodeState> = try {
        val a = JSONArray(episodesJson)
        (0 until a.length()).map { a.getJSONObject(it) }.associate {
            (it.optInt("seasonNumber") to it.optInt("episodeNumber")) to when {
                it.optBoolean("hasFile") -> EpisodeState.DOWNLOADED
                it.optBoolean("monitored") -> EpisodeState.REQUESTED
                else -> EpisodeState.NONE
            }
        }
    } catch (_: Exception) { emptyMap() }

    fun monitorBody(ids: List<Int>): String = JSONObject().put("episodeIds", JSONArray(ids)).put("monitored", true).toString()

    fun searchBody(ids: List<Int>): String = JSONObject().put("name", "EpisodeSearch").put("episodeIds", JSONArray(ids)).toString()
}

/**
 * Asks Sonarr for single episodes, which Seerr cannot. It reads Sonarr's address and key from Seerr's settings (so the
 * TV needs only the Seerr sign-in it already has), makes sure the series exists, marks just the chosen episodes as
 * wanted and starts a search for them. Every call blocks and none throw.
 */
class SonarrClient(private val seerr: SeerrClient) {
    private var cached: SonarrConfig? = null

    private fun config(): SonarrConfig? = cached ?: seerr.sonarrSettings()?.let { SonarrApi.parseConfig(it, seerr.host) }?.also { cached = it }

    /** The state of every episode Sonarr knows for this series; empty when it does not know the series. */
    fun episodeStates(tmdbId: Long): Map<Pair<Int, Int>, EpisodeState> {
        val c = config() ?: return emptyMap()
        val id = seriesId(c, tmdbId) ?: return emptyMap()
        return call(c, "GET", "/api/v3/episode?seriesId=$id")?.let { SonarrApi.episodeStates(it) }.orEmpty()
    }

    /** Wants the given episodes of one season and searches for them. */
    fun requestEpisodes(tmdbId: Long, season: Int, numbers: List<Int>): EpisodeOutcome {
        val c = config() ?: return EpisodeOutcome.NOT_CONFIGURED
        var id = seriesId(c, tmdbId)
        if (id == null) {
            val found = call(c, "GET", "/api/v3/series/lookup?term=tmdb:$tmdbId") ?: return EpisodeOutcome.FAILED
            val lookup = try { JSONArray(found).optJSONObject(0) } catch (_: Exception) { null } ?: return EpisodeOutcome.NOT_FOUND
            val added = call(c, "POST", "/api/v3/series", SonarrApi.addBody(lookup, c)) ?: return EpisodeOutcome.FAILED
            id = try { JSONObject(added).optInt("id").takeIf { it > 0 } } catch (_: Exception) { null } ?: return EpisodeOutcome.FAILED
        }
        // A series that has only just been added fetches its episode list in the background; give it a moment.
        var ids: List<Int> = emptyList()
        for (attempt in 1..8) {
            ids = call(c, "GET", "/api/v3/episode?seriesId=$id")?.let { SonarrApi.episodeIds(it, season, numbers) }.orEmpty()
            if (ids.size == numbers.size || (ids.isNotEmpty() && attempt >= 4)) break
            Thread.sleep(1000)
        }
        if (ids.isEmpty()) return EpisodeOutcome.NOT_FOUND
        if (call(c, "PUT", "/api/v3/episode/monitor", SonarrApi.monitorBody(ids)) == null) return EpisodeOutcome.FAILED
        return if (call(c, "POST", "/api/v3/command", SonarrApi.searchBody(ids)) == null) EpisodeOutcome.FAILED else EpisodeOutcome.QUEUED
    }

    private fun seriesId(c: SonarrConfig, tmdbId: Long): Int? = call(c, "GET", "/api/v3/series")?.let { SonarrApi.findSeries(it, tmdbId) }

    private fun call(c: SonarrConfig, method: String, path: String, body: String? = null): String? = arrCall(c, method, path, body)
}
