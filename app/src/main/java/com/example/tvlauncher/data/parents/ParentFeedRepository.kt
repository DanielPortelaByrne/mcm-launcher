package com.example.tvlauncher.data.parents

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val TAG = "ParentFeed"

/** Where the last good feed is kept. A file in the app's private storage on the TV; in memory in tests. */
interface FeedStore {
    fun read(): String?
    /** Must replace the old copy all at once: a crash mid-write may never leave half a feed. */
    fun write(json: String)
    var etag: String?
    var checkedAt: Long
}

/** Fetches the feed. Returns null when the server says it has not changed since [etag]. */
fun interface FeedFetcher {
    fun fetch(url: String, etag: String?): Fetched?
}

data class Fetched(val body: String, val etag: String?)

/**
 * Stale-while-revalidate for the parents' feed: [cached] is whatever was last known good (instant, no
 * network); [refresh] fetches in the background and only replaces it with a feed that parses and
 * validates. A failed or malformed fetch leaves the cached feed exactly as it was.
 */
class ParentFeedRepository(
    private val store: FeedStore,
    private val fetcher: FeedFetcher,
    private val feedUrl: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis
) {
    @Volatile var cached: ParentFeed? = null
        private set

    /** Reads the stored feed. Blocking: call off the main thread. A corrupt file is treated as no feed. */
    fun load(): ParentFeed? {
        cached = store.read()?.let { runCatching { ParentFeedParser.parse(it) }.onFailure { Log.w(TAG, "Stored feed unreadable: ${it.message}") }.getOrNull() }
        return cached
    }

    enum class Outcome { UPDATED, UNCHANGED, SKIPPED, NOT_CONFIGURED, FAILED, REJECTED }

    /** Blocking. Checks at most every [minIntervalMs] unless [force]. */
    fun refresh(force: Boolean = false, minIntervalMs: Long = 10 * 60_000L): Outcome {
        val url = feedUrl() ?: return Outcome.NOT_CONFIGURED
        if (!force && cached != null && clock() - store.checkedAt < minIntervalMs) return Outcome.SKIPPED
        val fetched = try {
            fetcher.fetch(url, if (cached != null) store.etag else null)
        } catch (e: Exception) {
            Log.w(TAG, "Feed fetch failed: ${e.javaClass.simpleName}; keeping cached feed")
            return Outcome.FAILED
        }
        store.checkedAt = clock()
        if (fetched == null) return Outcome.UNCHANGED
        val feed = try { ParentFeedParser.parse(fetched.body) } catch (e: InvalidFeedException) {
            Log.w(TAG, "Feed rejected (${e.message}); keeping cached feed")
            return Outcome.REJECTED
        }
        if (cached != null && feed.updatedAt == cached!!.updatedAt) { store.etag = fetched.etag; return Outcome.UNCHANGED }
        store.write(fetched.body)
        store.etag = fetched.etag
        cached = feed
        Log.i(TAG, "Feed updated: ${feed.danielLately.size} photos, ${feed.comingUp.size} events")
        return Outcome.UPDATED
    }
}

/** The feed lives in the app's private files, so it survives restarts and reboots. */
class FileFeedStore(context: Context) : FeedStore {
    private val dir = File(context.filesDir, "parents").apply { mkdirs() }
    private val file = File(dir, "feed.json")
    private val prefs = context.getSharedPreferences("parent_feed", Context.MODE_PRIVATE)

    override fun read(): String? = file.takeIf { it.exists() }?.readText()

    override fun write(json: String) {
        val tmp = File(dir, "feed.json.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "could not replace feed" } }
    }

    override var etag: String?
        get() = prefs.getString("etag", null)
        set(value) { prefs.edit().putString("etag", value).apply() }

    override var checkedAt: Long
        get() = prefs.getLong("checkedAt", 0)
        set(value) { prefs.edit().putLong("checkedAt", value).apply() }
}

object HttpFeedFetcher : FeedFetcher {
    override fun fetch(url: String, etag: String?): Fetched? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "MCMHome/1.1 (parents' TV)")
        if (etag != null) c.setRequestProperty("If-None-Match", etag)
        return try {
            when (c.responseCode) {
                304 -> null
                200 -> Fetched(c.inputStream.bufferedReader().use { it.readText() }, c.getHeaderField("ETag"))
                else -> error("HTTP ${c.responseCode}")
            }
        } finally { c.disconnect() }
    }
}

/**
 * Where this TV reads its feed from. The address is never in the APK or in Git: it is pushed to the
 * device once over ADB (see README, "Parents' home") into the app's external files folder, moved into
 * private preferences on the next start, and the external copy is deleted.
 */
class ParentsConfig(private val context: Context) {
    private val prefs = context.getSharedPreferences("parents_config", Context.MODE_PRIVATE)

    /** True once a newly pushed address has been taken in, so the caller fetches from it straight away. */
    @Volatile var justProvisioned = false

    /** Blocking (touches storage). */
    fun feedUrl(): String? {
        importProvisioned()
        return prefs.getString("feedUrl", null)?.takeIf { it.startsWith("https://") }
    }

    private fun importProvisioned() {
        val file = File(context.getExternalFilesDir(null) ?: return, PROVISION_FILE)
        if (!file.exists()) return
        try {
            val json = org.json.JSONObject(file.readText())
            val url = json.optString("feedUrl")
            if (url.startsWith("https://")) { prefs.edit().putString("feedUrl", url).apply(); justProvisioned = true; Log.i(TAG, "Feed address provisioned") }
            else Log.w(TAG, "Provisioned config has no https feedUrl; ignored")
            // The usage log's Sheet (UsageLog): optional, kept beside the feed address, never in Git.
            val logUrl = json.optString("logUrl"); val logKey = json.optString("logKey")
            if (logUrl.startsWith("https://") && logKey.isNotBlank()) { prefs.edit().putString("logUrl", logUrl).putString("logKey", logKey).apply(); Log.i(TAG, "Usage log provisioned") }
        } catch (e: Exception) {
            Log.w(TAG, "Provisioned config unreadable: ${e.javaClass.simpleName}")
        } finally { file.delete() }
    }

    companion object { const val PROVISION_FILE = "parents-config.json" }
}

/** One worker for everything the parents' home loads, so feed and images never touch the main thread. */
object ParentsWork {
    val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "parents-feed").apply { priority = Thread.MIN_PRIORITY + 1 } }
}
