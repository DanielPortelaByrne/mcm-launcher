package com.example.tvlauncher.data.movies

import org.json.JSONArray

/** The plain-Kotlin half of removing a title: finding its id in Radarr or Sonarr and the call that deletes it. */
object LibraryApi {
    /** Radarr's id for the movie with this TMDB id, if it has it. */
    fun radarrMovieId(listJson: String, tmdbId: Long): Int? = try {
        val a = JSONArray(listJson)
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optLong("tmdbId") == tmdbId }?.optInt("id")?.takeIf { it > 0 }
    } catch (_: Exception) { null }

    /**
     * Deletes the files and the entry together. Without removing the entry the download client would fetch the title again,
     * and the import exclusion keeps a list from adding it back.
     */
    fun deletePath(kind: Kind, id: Int): String =
        if (kind == Kind.SHOW) "/api/v3/series/$id?deleteFiles=true&addImportListExclusion=false"
        else "/api/v3/movie/$id?deleteFiles=true&addImportExclusion=false"
}

/** Removes a title from Radarr or Sonarr, which deletes its files too. Blocks and never throws. */
class LibraryRemover(private val seerr: SeerrClient) {
    /** True when the title was found and removed. */
    fun delete(kind: Kind, tmdbId: Long): Boolean {
        return if (kind == Kind.SHOW) {
            val c = seerr.sonarrSettings()?.let { SonarrApi.parseConfig(it, seerr.host) } ?: return false
            val id = arrCall(c, "GET", "/api/v3/series")?.let { SonarrApi.findSeries(it, tmdbId) } ?: return false
            arrCall(c, "DELETE", LibraryApi.deletePath(kind, id)) != null
        } else {
            val c = seerr.radarrSettings()?.let { SonarrApi.parseConfig(it, seerr.host) } ?: return false
            val id = arrCall(c, "GET", "/api/v3/movie")?.let { LibraryApi.radarrMovieId(it, tmdbId) } ?: return false
            arrCall(c, "DELETE", LibraryApi.deletePath(kind, id)) != null
        }
    }
}
