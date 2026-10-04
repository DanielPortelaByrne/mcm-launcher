package com.example.tvlauncher.data

import android.content.Context
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class Film(val title: String, val path: String)

/** A popular public Letterboxd list ("Movies everyone should watch at least once"). A complete fetch replaces the last known good cache atomically. */
class Watchlist(context: Context) {
    private val prefs = context.getSharedPreferences("letterboxd", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var loading = false
    private var lastPath = ""
    // The saved list is ~1,700 films in SharedPreferences; reading it on the main thread cost ~2 s at every
    // cold start. It loads on the worker instead, and [whenLoaded] runs once it is in.
    @Volatile var films: List<Film> = emptyList()
        private set
    @Volatile private var loaded = false
    private var onLoaded: (() -> Unit)? = null

    init {
        worker.execute {
            films = decode(prefs.getString("films", "[]") ?: "[]")
            val callback = synchronized(this) { loaded = true; onLoaded.also { onLoaded = null } }
            callback?.invoke()
        }
    }

    /** Runs [action] once the saved list has loaded: now if it already has, otherwise from the worker thread. */
    fun whenLoaded(action: () -> Unit) {
        val now = synchronized(this) { if (loaded) true else { onLoaded = action; false } }
        if (now) action()
    }

    fun pick(): Film? = (films.filter { it.path != lastPath }.ifEmpty { films }).randomOrNull()?.also { lastPath = it.path }

    fun refresh(force: Boolean = false, onResult: (String) -> Unit) {
        if (loading) return
        loading = true
        worker.execute {
            if (!force && films.isNotEmpty() && System.currentTimeMillis() - prefs.getLong("updated", 0) < 6 * 60 * 60 * 1000) { loading = false; return@execute }
            try {
                val collected = linkedMapOf<String, Film>()
                var page = 1
                while (true) {
                    val html = fetch("https://letterboxd.com/$LIST_PATH/page/$page/")
                    val entries = parse(html)
                    if (entries.isEmpty()) error("Watchlist page $page could not be read")
                    entries.forEach { collected[it.path] = it }
                    val next = Regex("class=\"next\"[^>]*href=\"/$LIST_PATH/page/(\\d+)/\"").find(html)
                    if (next == null) break
                    val nextPage = next.groupValues[1].toInt()
                    check(nextPage == page + 1 && nextPage <= 500) { "Unexpected watchlist pagination" }
                    page = nextPage
                    Thread.sleep(350)
                }
                val result = collected.values.toList()
                val array = JSONArray()
                result.forEach { array.put(JSONObject().put("title", it.title).put("path", it.path)) }
                prefs.edit().putString("films", array.toString()).putLong("updated", System.currentTimeMillis()).apply()
                films = result
                onResult("${result.size} films from Letterboxd")
            } catch (_: Exception) {
                onResult(if (films.isEmpty()) "Watchlist unavailable · select to retry" else "${films.size} saved films · refresh unavailable")
            } finally { loading = false }
        }
    }

    fun close() { worker.shutdownNow() }

    companion object {
        private const val LIST_PATH = "fcbarcelona/list/movies-everyone-should-watch-at-least-once"
        fun parse(html: String): List<Film> = Regex("<div\\b[^>]*data-item-name=\"([^\"]+)\"[^>]*data-item-link=\"(/film/[^\"]+/)\"[^>]*>").findAll(html).map {
            Film(Html.fromHtml(it.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString(), it.groupValues[2])
        }.distinctBy { it.path }.toList()

        fun fetch(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("User-Agent", "MCMHome/1.1 (personal TV watchlist)")
            return try {
                check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally { connection.disconnect() }
        }

        private fun decode(json: String): List<Film> = runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { val f = array.getJSONObject(it); Film(f.getString("title"), f.getString("path")) }
        }.getOrDefault(emptyList())
    }
}
