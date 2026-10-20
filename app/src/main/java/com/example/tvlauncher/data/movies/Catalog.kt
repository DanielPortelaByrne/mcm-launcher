package com.example.tvlauncher.data.movies

import org.json.JSONArray
import org.json.JSONObject

enum class Kind { MOVIE, SHOW }

/** One film or series as the Movies page shows it, wherever it came from. */
data class Title(
    val key: String,
    val kind: Kind,
    val name: String,
    val year: Int?,
    val overview: String?,
    val runtimeMin: Int?,
    val genres: List<String>,
    val age: String?,
    val imdbScore: Double?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val imdbId: String?,
    val tmdbId: String?
) {
    /** "2019 · 15 · 1h 41m · Horror, Thriller": only what is known. */
    fun metaLine(): String = listOfNotNull(
        year?.toString(), age?.takeIf { it.isNotBlank() },
        runtimeMin?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "$it min" },
        genres.take(3).joinToString(", ").takeIf { it.isNotBlank() }
    ).joinToString("  ·  ")
}

/** A row on the page: a heading and the titles in it. */
data class Shelf(val heading: String, val titles: List<Title>)

/** What to ask JustWatch for, one shelf at a time. */
data class ShelfSpec(val heading: String, val packages: List<String>, val kinds: List<Kind>, val genre: String? = null, val sort: String = "TRENDING")

/**
 * The plain-Kotlin half of the catalogue: how a shelf is requested from JustWatch's public GraphQL endpoint and how
 * its answer is read. JustWatch needs no sign-in and already knows IMDb and TMDB ids, which is what links a title on
 * a streaming service to the same film in the owner's own library.
 */
object Catalog {
    /** Short names JustWatch uses for the services in the UK. */
    const val NETFLIX = "nfx"
    const val HBO_MAX = "mxx"
    const val PARAMOUNT = "pmp"
    const val PRIME = "amp"
    const val DISNEY = "dnp"
    const val APPLE = "atp"
    val MAJOR = listOf(NETFLIX, HBO_MAX, PARAMOUNT, PRIME, DISNEY, APPLE)

    val SHELVES: List<ShelfSpec> = listOf(
        ShelfSpec("Trending now", MAJOR, listOf(Kind.MOVIE, Kind.SHOW)),
        ShelfSpec("Trending films", MAJOR, listOf(Kind.MOVIE)),
        ShelfSpec("Trending series", MAJOR, listOf(Kind.SHOW)),
        ShelfSpec("Documentaries", MAJOR, listOf(Kind.MOVIE, Kind.SHOW), genre = "doc"),
        ShelfSpec("Popular on Netflix", listOf(NETFLIX), listOf(Kind.MOVIE, Kind.SHOW), sort = "POPULAR"),
        ShelfSpec("Popular on HBO Max", listOf(HBO_MAX), listOf(Kind.MOVIE, Kind.SHOW), sort = "POPULAR"),
        ShelfSpec("Popular on Paramount+", listOf(PARAMOUNT), listOf(Kind.MOVIE, Kind.SHOW), sort = "POPULAR"),
        ShelfSpec("Popular on Prime Video", listOf(PRIME), listOf(Kind.MOVIE, Kind.SHOW), sort = "POPULAR"),
        ShelfSpec("Popular on Disney+", listOf(DISNEY), listOf(Kind.MOVIE, Kind.SHOW), sort = "POPULAR"),
        ShelfSpec("Popular on Apple TV+", listOf(APPLE), listOf(Kind.MOVIE, Kind.SHOW), sort = "POPULAR")
    )

    private const val IMAGES = "https://images.justwatch.com"

    private const val QUERY = "query(\$f: TitleFilter!, \$c: Country!, \$l: Language!, \$n: Int!) { popularTitles(country: \$c, filter: \$f, first: \$n, sortBy: %SORT%) { edges { node { id objectType " +
        "content(country: \$c, language: \$l) { title originalReleaseYear shortDescription runtime genres { translation(language: \$l) } ageCertification posterUrl " +
        "backdrops(format: JPG, profile: S1440) { backdropUrl } externalIds { imdbId tmdbId } scoring { imdbScore } } } } } }"

    /** The request body for one shelf. */
    fun requestBody(spec: ShelfSpec, count: Int = 20): String {
        val filter = JSONObject()
            .put("packages", JSONArray(spec.packages))
            .put("objectTypes", JSONArray(spec.kinds.map { if (it == Kind.MOVIE) "MOVIE" else "SHOW" }))
        spec.genre?.let { filter.put("genres", JSONArray().put(it)) }
        return JSONObject()
            .put("query", QUERY.replace("%SORT%", spec.sort))
            .put("variables", JSONObject().put("f", filter).put("c", "GB").put("l", "en").put("n", count))
            .toString()
    }

    fun poster(path: String?): String? = path?.takeIf { it.isNotBlank() }?.replace("{profile}", "s332")?.replace("{format}", "jpg")?.let { IMAGES + it }

    fun backdrop(path: String?): String? = path?.takeIf { it.isNotBlank() }?.let { IMAGES + it }

    fun parse(json: String): List<Title> = try {
        val edges = JSONObject(json).getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges")
        (0 until edges.length()).mapNotNull { i ->
            val node = edges.getJSONObject(i).getJSONObject("node")
            val c = node.optJSONObject("content") ?: return@mapNotNull null
            val name = c.optString("title").ifBlank { return@mapNotNull null }
            val ids = c.optJSONObject("externalIds")
            val genres = c.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.getJSONObject(it).optString("translation").ifBlank { null } } }.orEmpty()
            Title(
                key = node.optString("id"),
                kind = if (node.optString("objectType") == "SHOW") Kind.SHOW else Kind.MOVIE,
                name = name,
                year = c.optInt("originalReleaseYear", 0).takeIf { it > 0 },
                overview = c.optString("shortDescription").ifBlank { null },
                runtimeMin = c.optInt("runtime", 0).takeIf { it > 0 },
                genres = genres,
                age = c.optString("ageCertification").ifBlank { null },
                imdbScore = c.optJSONObject("scoring")?.optDouble("imdbScore", -1.0)?.takeIf { it > 0 },
                posterUrl = poster(c.optString("posterUrl")),
                backdropUrl = backdrop(c.optJSONArray("backdrops")?.optJSONObject(0)?.optString("backdropUrl")),
                imdbId = ids?.optString("imdbId")?.ifBlank { null },
                tmdbId = ids?.optString("tmdbId")?.ifBlank { null }
            )
        }
    } catch (_: Exception) { emptyList() }

    /** A film is often on several services; the first listing wins. */
    fun dedupe(titles: List<Title>): List<Title> = titles.distinctBy { it.imdbId ?: it.key }
}
