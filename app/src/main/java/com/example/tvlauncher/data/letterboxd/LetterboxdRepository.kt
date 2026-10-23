package com.example.tvlauncher.data.letterboxd

import android.app.Activity
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Letterboxd for the Cinema page: who is followed, and every page it shows, kept on disk so a page opens at once from
 * the last copy and is then refreshed in the background (a page behind Letterboxd's browser check takes several
 * seconds to fetch). Pages are stored without their scripts and drawings, which are most of their size.
 */
/** [allowBrowser] false (the player) never starts the hidden browser: plain requests and the saved copies only. */
class LetterboxdRepository(activity: Activity, allowBrowser: Boolean = true) {
    val web = LetterboxdWeb(activity, allowBrowser)
    private val dir = File(activity.cacheDir, "letterboxd").apply { mkdirs() }
    private val prefs = activity.getSharedPreferences("letterboxd_follow", Activity.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)
    private val inFlight = ConcurrentHashMap<String, Boolean>()

    // --- Who is followed ----------------------------------------------------------------------------

    /** Usernames followed here, in the order they were added. Eva and Daniel to begin with. */
    var following: List<String>
        get() = prefs.getString("users", null)?.let { s -> (0 until JSONArray(s).length()).map { JSONArray(s).getString(it) } } ?: DEFAULT
        private set(value) { prefs.edit().putString("users", JSONArray(value).toString()).apply() }

    fun follow(user: String) { if (following.none { it.equals(user, true) }) following = following + user }
    fun unfollow(user: String) { following = following.filterNot { it.equals(user, true) } }

    // --- Pages ----------------------------------------------------------------------------------------

    /**
     * Calls [done] on the main thread with what [parse] makes of [path]: first from the saved copy, if there is one,
     * then again from a fresh copy when the saved one is older than [maxAgeMs] (only if it changed). Null means the
     * page could not be had at all.
     */
    fun <T> load(path: String, maxAgeMs: Long, parse: (String) -> T?, done: (T?) -> Unit) {
        pool.execute {
            val file = cacheFile(path)
            val saved = file.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
            val savedValue = saved?.let { runCatching { parse(it) }.getOrNull() }
            if (savedValue != null) main.post { done(savedValue) }
            val fresh = saved == null || System.currentTimeMillis() - file.lastModified() > maxAgeMs
            if (!fresh) return@execute
            if (inFlight.putIfAbsent(path, true) != null && savedValue != null) return@execute
            try {
                val html = web.page(path)?.let { slim(it) }
                if (html == null) { if (savedValue == null) main.post { done(null) }; return@execute }
                if (html != saved) runCatching { File(dir, file.name + ".tmp").also { it.writeText(html) }.renameTo(file) }
                else file.setLastModified(System.currentTimeMillis())
                val value = runCatching { parse(html) }.getOrNull()
                if (html != saved || savedValue == null) main.post { done(value) }
            } finally { inFlight.remove(path) }
        }
    }

    /** Blocking: the page now, saved copy first when it is recent enough. Null when it cannot be had. */
    fun pageNow(path: String, maxAgeMs: Long): String? {
        val file = cacheFile(path)
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < maxAgeMs) return file.readText()
        val html = web.page(path)?.let { slim(it) } ?: return file.takeIf { it.exists() }?.readText()
        runCatching { File(dir, file.name + ".tmp").also { it.writeText(html) }.renameTo(file) }
        return html
    }

    /** True when [path] is known not to exist (a member's film page they never logged, a username with no account). */
    fun missing(html: String?) = html != null && html.contains("<title>Letterboxd - Not Found</title>")

    // The version is part of the name: when what is kept of a page changes, the copies saved before are left unused.
    private fun cacheFile(path: String) = File(dir, com.example.tvlauncher.ui.ImageCache.sha1("v2:" + path) + ".html")

    // --- Posters --------------------------------------------------------------------------------------

    private val posters = ConcurrentHashMap<String, String>()
    private val posterFile = File(dir, "posters.json")
    @Volatile private var postersLoaded = false

    /** Blocking: a poster address for [slug] (460 x 690), from Letterboxd's small poster lookup. Kept for good. */
    fun posterUrl(slug: String): String? {
        if (!postersLoaded) loadPosters()
        posters[slug]?.let { return it }
        val json = web.json("/film/$slug/poster/std/230/") ?: return null
        val url = runCatching { JSONObject(json).optString("url2x").ifBlank { null } }.getOrNull() ?: return null
        posters[slug] = url
        savePostersSoon()
        return url
    }

    @Synchronized private fun loadPosters() {
        if (postersLoaded) return
        runCatching { val o = JSONObject(posterFile.readText()); o.keys().forEach { posters[it] = o.getString(it) } }
        postersLoaded = true
    }

    private val saveTask = Runnable { pool.execute { runCatching { File(dir, "posters.tmp").also { it.writeText(JSONObject(posters as Map<*, *>).toString()) }.renameTo(posterFile) } } }
    private fun savePostersSoon() { main.removeCallbacks(saveTask); main.postDelayed(saveTask, 2000) }

    fun close() { pool.shutdownNow(); web.close() }

    companion object {
        val DEFAULT = listOf("evacawley", "DanielPortelaB")
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        /** Drops scripts, styles and inline drawings: a review page goes from 1.4 MB to a fraction of that. */
        fun slim(html: String): String = html
            // Every script but the film's data block (its rating, and how many have rated and reviewed it).
            .replace(Regex("<script\\b(?![^>]*application/ld\\+json).*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style\\b.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<svg\\b(?:(?!aria-label=\"(?:[★½]+|Liked)\").)*?</svg>", RegexOption.DOT_MATCHES_ALL), "")
    }
}
