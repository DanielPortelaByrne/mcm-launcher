package com.example.tvlauncher.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.tvlauncher.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the parents do with MCM Home, sent to Daniel's private Google Sheet (tools/usage-log/Code.gs) so he
 * can see from London which sections they find, open, or pass by, and refine the home around that.
 *
 * Events wait in a file on the TV and go up in batches every few minutes, so nothing is lost while the
 * internet or the Sheet is down. The Sheet's address and key are provisioned like the feed address
 * (parents-config.json: "logUrl", "logKey"); without them nothing is recorded at all.
 */
object UsageLog {
    private const val TAG = "UsageLog"
    private const val FLUSH_MINUTES = 10L
    private const val LOOK_MS = 2500L          // focus held this long = she looked at it
    private const val MAX_QUEUED = 5000        // keep at most this many lines while offline

    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var app: Context? = null
    @Volatile private var started = false

    /** Starts the log (idempotent). Cheap: reads two preferences. */
    fun start(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        worker.scheduleWithFixedDelay({ flush() }, 1, FLUSH_MINUTES, TimeUnit.MINUTES)
    }

    /** One thing that happened. [where] is the part of the home it happened in, [what] the thing itself. */
    fun event(type: String, where: String? = null, what: String? = null, app: String? = null, seconds: Long? = null, detail: String? = null) {
        val context = this.app ?: return
        val line = JSONObject().apply {
            put("t", System.currentTimeMillis()); put("type", type)
            where?.let { put("where", it.take(80)) }; what?.let { put("what", it.take(160)) }
            app?.let { put("app", it) }; seconds?.let { put("seconds", it) }; detail?.let { put("detail", it.take(200)) }
        }.toString()
        worker.execute {
            try { File(context.filesDir, "usage.jsonl").appendText(line + "\n") } catch (e: Exception) { Log.w(TAG, "Not saved: ${e.javaClass.simpleName}") }
        }
    }

    // --- Home visits -------------------------------------------------------------------------------------

    private var homeSince = 0L
    private var away: Triple<String, String?, Long>? = null   // what was opened, its package, when
    private val seenThisVisit = mutableSetOf<String>()

    fun homeShown() {
        homeSince = SystemClock.elapsedRealtime()
        seenThisVisit.clear()
        val a = away
        away = null
        if (a != null) event("returned", what = a.first, app = a.second, seconds = (SystemClock.elapsedRealtime() - a.third) / 1000)
        else event("home_shown")
    }

    fun homeLeft() {
        if (homeSince == 0L) return
        event("home_left", seconds = (SystemClock.elapsedRealtime() - homeSince) / 1000, detail = seenThisVisit.joinToString(", ").ifBlank { null })
        homeSince = 0L
        flushSoon()
    }

    /** Something was opened from the home: an app, a family card, a programme. */
    fun opened(where: String?, what: String, pkg: String?) {
        event("open", where = where, what = what, app = pkg)
        away = Triple(what, pkg, SystemClock.elapsedRealtime())
    }

    /** A family section came into view; logged once per home visit. */
    fun sectionSeen(title: String) { if (seenThisVisit.add(title)) event("section_seen", where = title) }

    // --- Looking without opening ---------------------------------------------------------------------------

    private var focused: View? = null
    private var focusedAt = 0L

    /** From a global focus listener: a card that kept focus a while was looked at, opened or not. */
    fun focusMoved(next: View?) {
        val prev = focused
        val held = SystemClock.elapsedRealtime() - focusedAt
        if (prev != null && held >= LOOK_MS) label(prev)?.let { event("looked_at", where = whereOf(prev), what = it, seconds = held / 1000) }
        focused = next; focusedAt = SystemClock.elapsedRealtime()
    }

    /** The part of the home [view] sits in: the nearest ancestor tagged with R.id.usage_where. */
    fun whereOf(view: View?): String? {
        var v: View? = view
        while (v != null) { (v.getTag(R.id.usage_where) as? String)?.let { return it }; v = v.parent as? View }
        return null
    }

    private fun label(view: View): String? {
        view.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (view is TextView) return view.text?.toString()?.takeIf { it.isNotBlank() }
        if (view is ViewGroup) for (i in 0 until view.childCount) label(view.getChildAt(i))?.let { return it }
        return null
    }

    // --- What played -----------------------------------------------------------------------------------------

    private val playing = mutableMapOf<String, Pair<String, Long>>()   // package -> title, since

    /** From the media watcher: [title] is playing in [pkg] (null: it stopped). Logged when it ends, if it lasted. */
    @Synchronized fun nowPlaying(pkg: String, title: String?) {
        val current = playing[pkg]
        if (current?.first == title) return
        if (current != null) {
            val seconds = (SystemClock.elapsedRealtime() - current.second) / 1000
            if (seconds >= 30) event("playing", what = current.first, app = pkg, seconds = seconds)
        }
        if (title == null) playing.remove(pkg) else playing[pkg] = title to SystemClock.elapsedRealtime()
    }

    // --- Sending -------------------------------------------------------------------------------------------

    private fun flushSoon() = worker.schedule({ flush() }, 20, TimeUnit.SECONDS)

    /** Worker thread. Sends what is queued; keeps it if the Sheet cannot be reached. */
    private fun flush() {
        val context = app ?: return
        val prefs = context.getSharedPreferences("parents_config", Context.MODE_PRIVATE)
        val url = prefs.getString("logUrl", null)?.takeIf { it.startsWith("https://") } ?: return
        val key = prefs.getString("logKey", null) ?: return
        val file = File(context.filesDir, "usage.jsonl")
        if (!file.exists()) return
        val lines = file.readLines().filter { it.isNotBlank() }.takeLast(MAX_QUEUED)
        if (lines.isEmpty()) return
        val sent = lines.chunked(200).takeWhile { batch -> post(url, key, batch) }.sumOf { it.size }
        val rest = lines.drop(sent)
        if (rest.isEmpty()) file.delete() else file.writeText(rest.joinToString("\n", postfix = "\n"))
        if (sent > 0) Log.i(TAG, "Sent $sent events" + if (rest.isNotEmpty()) ", ${rest.size} waiting" else "")
    }

    private fun post(url: String, key: String, batch: List<String>): Boolean = try {
        val body = JSONObject().put("key", key).put("device", "parents-firetv")
            .put("events", JSONArray().apply { batch.forEach { put(JSONObject(it)) } }).toString().toByteArray()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.instanceFollowRedirects = false   // Apps Script answers with a redirect once the rows are written
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            // The script's answer ({"ok":true,...}) is at the redirect's address; a wrong key says ok:false.
            val answer = when (code) {
                in 300..399 -> c.getHeaderField("Location")?.let { URL(it).readText() }
                in 200..299 -> c.inputStream.bufferedReader().readText()
                else -> null
            }
            if (answer?.contains("\"ok\":true") != true) Log.w(TAG, "Sheet refused the batch: HTTP $code ${answer?.take(80)}")
            answer?.contains("\"ok\":true") == true
        } finally { c.disconnect() }
    } catch (e: Exception) {
        Log.w(TAG, "Sheet not reached: ${e.javaClass.simpleName}")
        false
    }
}
