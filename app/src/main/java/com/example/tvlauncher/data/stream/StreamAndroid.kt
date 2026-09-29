package com.example.tvlauncher.data.stream

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import android.view.Display
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

private const val TAG = "SmartStream"

// ---------------------------------------------------------------------------------------------
// Device
// ---------------------------------------------------------------------------------------------

/** Asks the real hardware what it can decode and display. */
object DeviceProfile {
    @Volatile private var cached: DeviceCapabilities? = null

    @Suppress("DEPRECATION")
    fun detect(context: Context): DeviceCapabilities = cached ?: synchronized(this) {
        cached ?: build(context).also { cached = it }
    }

    @Suppress("DEPRECATION")
    private fun build(context: Context): DeviceCapabilities {
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        // Physical panel size: the launcher runs at a 1080p override on this TV, but the panel is 4K.
        val maxHeight = display?.supportedModes?.maxOfOrNull { max(it.physicalHeight, it.physicalWidth * 9 / 16) } ?: 1080
        val hdr = try { display?.hdrCapabilities?.supportedHdrTypes?.toSet() } catch (_: Exception) { null }.orEmpty()

        val decoders = try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { !it.isEncoder }
        } catch (_: Exception) { emptyList() }
        fun decodes(mime: String, hardwareOnly: Boolean) = decoders.any { info ->
            info.supportedTypes.any { it.equals(mime, true) } &&
                (!hardwareOnly || android.os.Build.VERSION.SDK_INT < 29 || info.isHardwareAccelerated)
        }
        val codecs = buildSet {
            add(VideoCodec.H264)
            if (decodes("video/hevc", true)) add(VideoCodec.HEVC)
            if (decodes("video/av01", true)) add(VideoCodec.AV1)
        }
        val dv = HDR_DV in hdr && decodes("video/dolby-vision", false)
        val hdr10 = HDR_HDR10 in hdr && VideoCodec.HEVC in codecs
        val hdr10Plus = HDR_HDR10_PLUS in hdr && VideoCodec.HEVC in codecs

        val audio = buildSet {
            add(AudioFormat.AAC); add(AudioFormat.AC3); add(AudioFormat.EAC3)
            try {
                val caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
                if (caps.supportsEncoding(C.ENCODING_E_AC3_JOC)) add(AudioFormat.ATMOS)
                if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) { add(AudioFormat.TRUEHD) }
                if (caps.supportsEncoding(C.ENCODING_DTS_HD)) add(AudioFormat.DTS_HD)
                if (caps.supportsEncoding(C.ENCODING_DTS)) add(AudioFormat.DTS)
            } catch (e: Exception) { Log.w(TAG, "Audio capability probe failed: ${e.message}") }
        }
        return DeviceCapabilities(maxHeight, codecs, dv, hdr10, hdr10Plus, audio).also { Log.i(TAG, "Device: $it") }
    }

    private const val HDR_DV = 1
    private const val HDR_HDR10 = 2
    private const val HDR_HDR10_PLUS = 4
}

// ---------------------------------------------------------------------------------------------
// Network
// ---------------------------------------------------------------------------------------------

/**
 * A rolling bandwidth estimate. It is fed by real observations (player throughput, tiny ranged
 * downloads of a candidate) and blended toward Android's own link estimate when it has gone stale.
 */
class NetworkEstimator(private val context: Context) {
    private val prefs = context.getSharedPreferences("network_estimate", Context.MODE_PRIVATE)

    fun current(now: Long = System.currentTimeMillis()): NetworkEstimate {
        val measured = prefs.getLong("bps", 0L)
        val at = prefs.getLong("at", 0L)
        val prior = linkPrior()
        if (measured <= 0L) return NetworkEstimate(prior, Long.MAX_VALUE, "Android link estimate")
        val age = now - at
        // Trust observations for a couple of days, then drift back toward the prior.
        val freshness = (1.0 - age / (3 * 86_400_000.0)).coerceIn(0.0, 1.0)
        return NetworkEstimate((measured * freshness + prior * (1 - freshness)).toLong(), age, "measured")
    }

    /** Folds a new observation into the rolling average. Observations under ~1 Mbps are ignored as noise. */
    fun record(bps: Long, now: Long = System.currentTimeMillis()) {
        if (bps < 1_000_000L) return
        val old = prefs.getLong("bps", 0L)
        val next = if (old <= 0L) bps else (old * 0.6 + bps * 0.4).toLong()
        prefs.edit().putLong("bps", next).putLong("at", now).apply()
    }

    fun isStale(maxAgeMs: Long = 15 * 60_000L, now: Long = System.currentTimeMillis()) = now - prefs.getLong("at", 0L) > maxAgeMs

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** [alive] is false when the link is dead (expired, 4xx/5xx, unreachable); [bps] is the observed speed when measured. */
    data class Probe(val alive: Boolean, val bps: Long?)

    /**
     * Checks a stream link before we recommend it. With [measure] it also downloads about 2 MB to gauge
     * speed; without, it fetches a single byte, which is enough to catch expired or dead links.
     */
    fun probe(url: String, headers: Map<String, String> = emptyMap(), measure: Boolean): Probe {
        if (!measure) return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("Range", "bytes=0-0")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try { Probe(c.responseCode in 200..299, null) } finally { c.disconnect() }
        } catch (e: Exception) { Log.w(TAG, "Probe failed: ${e.javaClass.simpleName}"); Probe(false, null) }
        return sampleProbe(url, headers)
    }

    private fun sampleProbe(url: String, headers: Map<String, String>): Probe {
        val bps = sample(url, headers)
        return Probe(alive = lastSampleAlive, bps = bps)
    }

    @Volatile private var lastSampleAlive = true

    /** Downloads about 2 MB of [url] and returns the observed speed. Also proves the link is alive. */
    fun sample(url: String, headers: Map<String, String> = emptyMap()): Long? = try {
        lastSampleAlive = true
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("Range", "bytes=0-${SAMPLE_BYTES - 1}")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        val start = System.nanoTime()
        var total = 0L
        try {
            if (c.responseCode !in 200..299) { lastSampleAlive = false; throw IllegalStateException("HTTP ${c.responseCode}") }
            c.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (total < SAMPLE_BYTES) { val n = input.read(buffer); if (n < 0) break; total += n }
            }
        } finally { c.disconnect() }
        val seconds = (System.nanoTime() - start) / 1e9
        if (total < 256 * 1024 || seconds <= 0) null else (total * 8 / seconds).toLong()
    } catch (e: Exception) { Log.w(TAG, "Throughput sample failed: ${e.message}"); if (e !is IllegalStateException) lastSampleAlive = false; null }

    private fun linkPrior(): Long {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return DEFAULT_PRIOR_BPS)
        val kbps = caps?.linkDownstreamBandwidthKbps ?: 0
        // Android's figure is a link rate, not what you get: be sceptical, and never claim more than 100 Mbps unmeasured.
        return if (kbps <= 0) DEFAULT_PRIOR_BPS else (kbps * 1000L / 2).coerceIn(10_000_000L, 100_000_000L)
    }

    private companion object {
        const val SAMPLE_BYTES = 2_000_000L
        const val DEFAULT_PRIOR_BPS = 25_000_000L
    }
}

// ---------------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------------

/** The only thing persisted about viewing: one small record per playback session, capped. */
class PlaybackStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_history", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<PlaybackRecord> = try {
        val a = JSONArray(prefs.getString("records", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            PlaybackRecord(
                o.getString("id"), o.getString("provider"),
                enumOr(o.optString("res"), Resolution.UNKNOWN), enumOr(o.optString("codec"), VideoCodec.UNKNOWN), enumOr(o.optString("src"), SourceType.UNKNOWN),
                o.optLong("bitrate", -1).takeIf { it > 0 }, o.optLong("startup", -1).takeIf { it >= 0 }, o.optLong("observed", -1).takeIf { it > 0 },
                o.optInt("bufN"), o.optLong("bufMs"), o.optLong("playMs"), o.optBoolean("done"), o.optBoolean("failed"), o.optLong("t")
            )
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun add(r: PlaybackRecord) {
        val list = (all() + r).takeLast(MAX_RECORDS)
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.contentId).put("provider", it.provider).put("res", it.resolution.name).put("codec", it.codec.name)
                .put("src", it.source.name).put("bitrate", it.estimatedBitrateBps ?: -1).put("startup", it.startupTimeMs ?: -1)
                .put("observed", it.observedThroughputBps ?: -1).put("bufN", it.bufferEventCount).put("bufMs", it.totalBufferDurationMs)
                .put("playMs", it.playDurationMs).put("done", it.completedNormally).put("failed", it.failed).put("t", it.timestampMs))
        }
        prefs.edit().putString("records", a.toString()).apply()
    }

    fun statsByProvider(now: Long = System.currentTimeMillis()): Map<String, ProviderStats> =
        all().groupBy { it.provider }.mapValues { ProviderStatsCalculator.compute(it.value, now) }

    private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T = enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object { const val MAX_RECORDS = 200 }
}

// ---------------------------------------------------------------------------------------------
// Sources: Stremio-compatible addons
// ---------------------------------------------------------------------------------------------

data class AddonConfig(val name: String, val baseUrl: String)

/**
 * Talks to the stream addons the owner configured, using the standard Stremio addon protocol
 * (`<addon>/stream/movie/<imdbId>.json`). Addon URLs can contain secrets (a debrid key, say), so they
 * are read from `files/stream_addons.json` on the device first and only then from the (empty)
 * `assets/home/streams.json`, and are never logged.
 */
class StreamSources(private val context: Context) {
    private val account = StremioAccount(context)

    val stremioConnected: Boolean get() = account.isConnected

    /** A hand-written `files/stream_addons.json` overrides the Stremio account (for development); otherwise Stremio is the source. */
    fun addons(): List<AddonConfig> {
        val manual = manualAddons()
        return if (manual.isNotEmpty()) manual else account.addons()
    }

    private fun manualAddons(): List<AddonConfig> {
        val text = try {
            java.io.File(context.filesDir, "stream_addons.json").takeIf { it.exists() }?.readText()
                ?: context.assets.open("home/streams.json").bufferedReader().use { it.readText() }
        } catch (_: Exception) { return emptyList() }
        return try {
            val a = JSONObject(text).optJSONArray("addons") ?: return emptyList()
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val url = o.optString("url").trim().removeSuffix("/manifest.json").removeSuffix("/")
                if (url.startsWith("http")) AddonConfig(o.optString("name").ifBlank { hostOf(url) }, url) else null
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Queries every addon in parallel; a slow or broken addon costs at most [TIMEOUT_S] seconds and never the others. */
    fun query(imdbId: String, addons: List<AddonConfig>, engineUp: Boolean = false): List<StreamCandidate> {
        val pool = Executors.newFixedThreadPool(addons.size.coerceIn(1, 6))
        return try {
            pool.invokeAll(addons.map { addon -> Callable { fetch(addon, imdbId, engineUp) } }, TIMEOUT_S + 2L, TimeUnit.SECONDS)
                .flatMap { f -> try { if (f.isCancelled) emptyList() else f.get() } catch (_: Exception) { emptyList() } }
                .distinctBy { it.url ?: it.infoHash ?: it.id }
        } finally { pool.shutdownNow() }
    }

    private fun fetch(addon: AddonConfig, imdbId: String, engineUp: Boolean): List<StreamCandidate> = try {
        val c = URL("${addon.baseUrl}/stream/movie/$imdbId.json").openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_S * 1000; c.readTimeout = TIMEOUT_S * 1000
        val body = try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
        AddonResponseParser.parse(addon.name, body, engineUp)
    } catch (e: Exception) { Log.w(TAG, "Addon '${addon.name}' failed: ${e.javaClass.simpleName}"); emptyList() }

    private fun hostOf(url: String) = try { URL(url).host } catch (_: Exception) { "addon" }

    private companion object { const val TIMEOUT_S = 12 }
}

/** Pure parsing of a Stremio addon stream response, kept apart from networking so it can be tested. */
object AddonResponseParser {
    /** Turns a Stremio stream response into candidates. Malformed entries are skipped, not fatal. */
    fun parse(provider: String, json: String, engineUp: Boolean = false): List<StreamCandidate> {
        val streams = try { JSONObject(json).optJSONArray("streams") } catch (_: Exception) { null } ?: return emptyList()
        return (0 until streams.length()).mapNotNull { i ->
            try {
                val s = streams.getJSONObject(i)
                val hints = s.optJSONObject("behaviorHints")
                val filename = hints?.optString("filename")?.ifBlank { null }
                val text = listOf(s.optString("name"), s.optString("title"), s.optString("description"), filename.orEmpty()).joinToString("\n")
                val size = hints?.optLong("videoSize", -1L)?.takeIf { it > 0 }
                val headers = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                    h.keys().asSequence().associateWith { k -> h.optString(k) }
                }.orEmpty()
                val infoHash = s.optString("infoHash").ifBlank { null }
                var url = s.optString("url").ifBlank { null }
                val fileIdx = if (s.has("fileIdx")) s.optInt("fileIdx") else null
                val trackers = s.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") } }.orEmpty()
                // A torrent result has no link of its own: Stremio's local engine serves it, when it is running.
                val viaEngine = url == null && infoHash != null && engineUp
                if (viaEngine) url = StremioEngine.streamUrl(infoHash!!, fileIdx, trackers)
                StreamCandidate(
                    id = "$provider#$i", provider = provider,
                    displayName = text.lineSequence().firstOrNull { it.isNotBlank() } ?: provider,
                    url = url, infoHash = infoHash,
                    meta = StreamParser.parse(text, size, filename),
                    notWebReady = hints?.optBoolean("notWebReady", false) ?: false, headers = headers, viaEngine = viaEngine,
                    trackers = trackers, fileIdx = fileIdx
                )
            } catch (_: Exception) { null }
        }
    }
}

/** Film runtime from Cinemeta (Stremio's public metadata service), cached. Needed to turn file size into bitrate. */
class RuntimeLookup(context: Context) {
    private val prefs = context.getSharedPreferences("runtime_cache", Context.MODE_PRIVATE)

    fun seconds(imdbId: String): Long? {
        prefs.getLong(imdbId, -1).takeIf { it > 0 }?.let { return it }
        return try {
            val c = URL("https://v3-cinemeta.strem.io/meta/movie/$imdbId.json").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 10000
            val body = try { check(c.responseCode == 200); c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            val minutes = Regex("(\\d+)").find(JSONObject(body).getJSONObject("meta").optString("runtime"))?.groupValues?.get(1)?.toLongOrNull()
            minutes?.times(60)?.also { prefs.edit().putLong(imdbId, it).apply() }
        } catch (_: Exception) { null }
    }
}

/** Remembers the film most recently handed to Stremio, so [com.example.tvlauncher.system.MediaWatcherService] can attribute what it sees. */
class HandoffStore(context: Context) {
    private val prefs = context.getSharedPreferences("handoff", Context.MODE_PRIVATE)

    fun set(h: PendingHandoff) {
        prefs.edit().putString("h", JSONObject().put("id", h.contentId).put("provider", h.provider).put("res", h.resolution.name)
            .put("codec", h.codec.name).put("src", h.source.name).put("bitrate", h.estimatedBitrateBps ?: -1).put("at", h.handedAtMs).toString()).apply()
    }

    /** The pending hand-off if it is recent enough to still be the thing being watched. */
    fun current(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 10 * 60_000L): PendingHandoff? = try {
        val o = JSONObject(prefs.getString("h", null) ?: return null)
        val at = o.getLong("at")
        if (now - at > maxAgeMs) null else PendingHandoff(
            o.getString("id"), o.getString("provider"),
            enumValues<Resolution>().firstOrNull { it.name == o.optString("res") } ?: Resolution.UNKNOWN,
            enumValues<VideoCodec>().firstOrNull { it.name == o.optString("codec") } ?: VideoCodec.UNKNOWN,
            enumValues<SourceType>().firstOrNull { it.name == o.optString("src") } ?: SourceType.UNKNOWN,
            o.optLong("bitrate", -1).takeIf { it > 0 }, at
        )
    } catch (_: Exception) { null }

    fun clear() { prefs.edit().remove("h").apply() }
}
