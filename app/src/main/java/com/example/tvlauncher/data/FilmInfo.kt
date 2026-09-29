package com.example.tvlauncher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Poster art and UK availability for one film. [stream] is included with a subscription or free; [rentBuy] is pay-per-title. */
data class FilmDetails(val posterUrl: String?, val stream: List<String>, val rentBuy: List<String>, val rating: Double? = null, val imdbId: String? = null, val page: FilmPage? = null) {
    /** "4.1" style label for the Letterboxd average (out of 5), or null when unknown. */
    fun ratingLabel(): String? = rating?.let { String.format(java.util.Locale.UK, "%.1f", it) }

    /** Two short lines for the home card, or one honest line when nothing in the UK carries it. */
    fun availabilityLines(): List<String> {
        val lines = mutableListOf<String>()
        if (stream.isNotEmpty()) lines += "Stream: ${summarise(stream)}"
        if (rentBuy.isNotEmpty()) lines += "Rent or buy: ${summarise(rentBuy)}"
        if (lines.isEmpty()) lines += "Not on UK services right now"
        return lines
    }

    private fun summarise(all: List<String>): String {
        // "HBO Max Amazon Channel" is the same service as "HBO Max": list each service once.
        val names = all.distinct().filter { n -> all.none { m -> m != n && n.startsWith(m + " ") } }
        return if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " +${names.size - 3}"
    }
}

/**
 * Looks a watchlist film up on JustWatch's public GraphQL endpoint (the one its own
 * website uses) for UK ("GB") offers and a poster. Results are cached on disk for
 * [TTL_MS]; any failure yields null so the card simply shows without them.
 */
class FilmInfo(context: Context) {
    private val prefs = context.getSharedPreferences("film_info", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    // Background preloading of upcoming picks, and the two lookups of one film running side by side.
    private val prefetcher = Executors.newSingleThreadExecutor()
    private val lookups = Executors.newFixedThreadPool(2)
    private val posterDir = java.io.File(context.cacheDir, "posters").apply { mkdirs() }
    private val posters = android.util.LruCache<String, Bitmap>(6)

    fun load(film: Film, onLoaded: (FilmDetails?, Bitmap?) -> Unit) {
        worker.execute {
            val details = detailsFor(film)
            onLoaded(details, details?.posterUrl?.let { poster(it) })
        }
    }

    /** Fetches details and poster for [film] in the background, so showing it later is instant. */
    fun prefetch(film: Film) {
        prefetcher.execute { detailsFor(film)?.posterUrl?.let { poster(it) } }
    }

    fun close() { worker.shutdownNow(); prefetcher.shutdownNow(); lookups.shutdownNow() }

    private fun detailsFor(film: Film): FilmDetails? = cached(film.path) ?: fetchAll(film)?.also { store(film.path, it) }

    /** Memory, then disk, then the network (saved to disk for next time). */
    private fun poster(url: String): Bitmap? {
        posters.get(url)?.let { return it }
        val file = java.io.File(posterDir, url.hashCode().toUInt().toString() + ".jpg")
        val bitmap = (if (file.exists()) BitmapFactory.decodeFile(file.path) else null) ?: download(url, file)
        bitmap?.let { posters.put(url, it) }
        return bitmap
    }

    // --- Network ------------------------------------------------------------

    private fun fetchAll(film: Film): FilmDetails? {
        val pageLookup = lookups.submit<Triple<Double?, String?, FilmPage?>> { fetchPage(film) }
        val where = fetchDetails(film)
        val (rating, imdb, page) = try { pageLookup.get() } catch (_: Exception) { Triple(null, null, null) }
        return if (where == null && rating == null && imdb == null) null else (where ?: FilmDetails(null, emptyList(), emptyList())).copy(rating = rating, imdbId = imdb, page = page)
    }

    private fun fetchDetails(film: Film): FilmDetails? = try {
        val (title, year) = splitTitle(film.title)
        val body = JSONObject()
            .put("query", QUERY)
            .put("variables", JSONObject()
                .put("first", 6).put("language", "en").put("country", "GB")
                .put("f", JSONObject().put("searchQuery", title).put("objectTypes", JSONArray().put("MOVIE"))))
        val response = post(body.toString())
        val edges = JSONObject(response).getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges")
        val nodes = (0 until edges.length()).map { edges.getJSONObject(it).getJSONObject("node") }
        val match = nodes.firstOrNull { matches(it, title, year) } ?: nodes.firstOrNull { yearOf(it) == year && year != null }
        match?.let { parse(it) }
    } catch (_: Exception) { null }

    /** Letterboxd's community average (0-5), read from the film page's share metadata. */
    private fun fetchPage(film: Film): Triple<Double?, String?, FilmPage?> = try {
        val connection = URL("https://letterboxd.com${film.path}").openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "MCMHome/1.1 (personal TV watchlist)")
        val html = try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
        val rating = Regex("twitter:data2\" content=\"([0-9.]+) out of 5\"").find(html)?.groupValues?.get(1)?.toDoubleOrNull()
        val imdb = Regex("imdb\\.com/title/(tt[0-9]+)").find(html)?.groupValues?.get(1)
        Triple(rating, imdb, LetterboxdParser.parse(html))
    } catch (_: Exception) { Triple(null, null, null) }

    private fun parse(node: JSONObject): FilmDetails {
        val stream = linkedSetOf<String>()
        val rentBuy = linkedSetOf<String>()
        val offers = node.optJSONArray("offers") ?: JSONArray()
        for (i in 0 until offers.length()) {
            val offer = offers.getJSONObject(i)
            val name = friendly(offer.getJSONObject("package").getString("clearName"))
            when (offer.getString("monetizationType")) {
                "FLATRATE", "FREE", "ADS", "FLATRATE_AND_BUY" -> stream += name
                "RENT", "BUY" -> rentBuy += name
            }
        }
        rentBuy.removeAll(stream)
        val poster = node.getJSONObject("content").optString("posterUrl").takeIf { it.isNotBlank() }
            ?.replace("{profile}", "s332")?.replace("{format}", "jpg")
            ?.let { "https://images.justwatch.com$it" }
        return FilmDetails(poster, stream.toList(), rentBuy.toList())
    }

    private fun post(json: String): String {
        val connection = URL("https://apis.justwatch.com/graphql").openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", "MCMHome/1.1 (personal TV)")
        return try {
            connection.outputStream.use { it.write(json.toByteArray()) }
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun download(url: String, file: java.io.File): Bitmap? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        val bytes = try { connection.inputStream.use { it.readBytes() } } finally { connection.disconnect() }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also {
            try { file.writeBytes(bytes); trimPosters() } catch (_: Exception) {}
        }
    } catch (_: Exception) { null }

    /** Keeps the poster cache to the 60 most recent. */
    private fun trimPosters() {
        val files = posterDir.listFiles() ?: return
        if (files.size > 60) files.sortedBy { it.lastModified() }.take(files.size - 60).forEach { it.delete() }
    }

    // --- Cache --------------------------------------------------------------

    private fun cached(path: String): FilmDetails? {
        val json = prefs.getString(path, null) ?: return null
        return try {
            val o = JSONObject(json)
            if (o.optInt("v", 1) < 5 || System.currentTimeMillis() - o.getLong("t") > TTL_MS) return null
            FilmDetails(o.optString("poster").takeIf { it.isNotBlank() }, strings(o.getJSONArray("stream")), strings(o.getJSONArray("rent")), o.optDouble("rating", -1.0).takeIf { it >= 0 }, o.optString("imdb").ifBlank { null }, o.optJSONObject("page")?.takeIf { it.length() > 0 }?.let { FilmPage.fromJson(it) })
        } catch (_: Exception) { null }
    }

    private fun store(path: String, d: FilmDetails) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("poster", d.posterUrl ?: "")
            .put("stream", JSONArray(d.stream)).put("rent", JSONArray(d.rentBuy)).put("rating", d.rating ?: -1.0).put("imdb", d.imdbId ?: "").put("page", d.page?.toJson() ?: JSONObject()).put("v", 5)
        prefs.edit().putString(path, o.toString()).apply()
    }

    private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }

    // --- Matching -----------------------------------------------------------

    private fun matches(node: JSONObject, title: String, year: Int?): Boolean {
        val found = node.getJSONObject("content").getString("title")
        return normalise(found) == normalise(title) && (year == null || kotlin.math.abs((yearOf(node) ?: year) - year) <= 1)
    }

    private fun yearOf(node: JSONObject): Int? = node.getJSONObject("content").optInt("originalReleaseYear", 0).takeIf { it > 0 }

    private fun normalise(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun friendly(name: String): String = when {
        name.startsWith("Amazon Prime Video") -> "Prime Video"
        name == "Amazon Video" -> "Prime Video"
        name == "Apple TV Store" || name == "Apple TV Plus" -> "Apple TV"
        name.endsWith(" with Ads") -> name.removeSuffix(" with Ads")
        else -> name
    }

    private fun splitTitle(full: String): Pair<String, Int?> {
        val m = Regex("^(.*)\\s\\((\\d{4})\\)$").find(full.trim()) ?: return full.trim() to null
        return m.groupValues[1] to m.groupValues[2].toInt()
    }

    private companion object {
        const val TTL_MS = 12L * 60 * 60 * 1000
        const val QUERY = "query Search(\$f: TitleFilter!, \$country: Country!, \$language: Language!, \$first: Int!) { popularTitles(country: \$country, filter: \$f, first: \$first) { edges { node { id objectType content(country: \$country, language: \$language) { title originalReleaseYear posterUrl } offers(country: \$country, platform: WEB) { monetizationType package { clearName } } } } } }"
    }
}
