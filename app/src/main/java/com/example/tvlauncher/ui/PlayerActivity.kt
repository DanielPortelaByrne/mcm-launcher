package com.example.tvlauncher.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.graphics.Bitmap
import com.example.tvlauncher.data.ContinueWatching
import com.example.tvlauncher.data.ResumeItem
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import com.example.tvlauncher.data.stream.NetworkEstimator
import com.example.tvlauncher.data.stream.PlaybackRecord
import com.example.tvlauncher.data.stream.PlaybackStore
import com.example.tvlauncher.data.stream.RankedStream
import com.example.tvlauncher.data.stream.Resolution
import com.example.tvlauncher.data.stream.SourceType
import com.example.tvlauncher.data.stream.VideoCodec
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "SmartStream"

/** One stream the player may try, in order. Serialised through the launch intent. */
data class PlayItem(
    val provider: String, val url: String, val headers: Map<String, String>,
    val resolution: Resolution, val codec: VideoCodec, val source: SourceType,
    val bitrateBps: Long?, val label: String, val viaEngine: Boolean = false
)

/**
 * Full-screen player for PLAY. It tries the ranked streams in order (at most three), records how each
 * behaves (startup time, buffering, failures, throughput) so future ranking improves, and if none can
 * be played it hands over to Stremio instead of leaving you on a spinner.
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var items: List<PlayItem>
    private var imdbId: String? = null
    private var title: String = ""

    private lateinit var history: PlaybackStore
    private lateinit var continueStore: ContinueWatching
    private var payload: String? = null
    private lateinit var network: NetworkEstimator
    private lateinit var bandwidth: DefaultBandwidthMeter
    private val resumePrefs by lazy { getSharedPreferences("resume_positions", MODE_PRIVATE) }

    private var index = 0
    private var prepareAt = 0L
    private var started = false
    private var ended = false
    private var recorded = false
    private var startupMs: Long? = null
    private var bufferEvents = 0
    private var bufferMs = 0L
    private var bufferStart = 0L
    private var seeking = false
    private var playMs = 0L
    private var playingSince = 0L

    private val startupTimeout = Runnable { fail("no picture in time") }
    private val saveTick = object : Runnable {
        override fun run() { saveResume(); snapshotToContinue(); handler.postDelayed(this, 15_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        items = parse(intent.getStringExtra(EXTRA_ITEMS))
        imdbId = intent.getStringExtra(EXTRA_IMDB)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        history = PlaybackStore(this)
        continueStore = ContinueWatching(this)
        payload = intent.getStringExtra(EXTRA_PAYLOAD)
        network = NetworkEstimator(this)
        if (items.isEmpty()) { fallBackToStremio("nothing to play"); return }

        bandwidth = DefaultBandwidthMeter.getSingletonInstance(this)
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setBandwidthMeter(bandwidth).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4000
            setBackgroundColor(0xFF000000.toInt())
            keepScreenOn = true
        }
        setContentView(FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(view, -1, -1) })
        player.addListener(listener)
        handler.postDelayed(saveTick, 15_000)
        startAttempt(0)
    }

    // --- attempts ---------------------------------------------------------------------------------

    private fun startAttempt(i: Int) {
        index = i
        val item = items[i]
        started = false; ended = false; recorded = false
        startupMs = null; bufferEvents = 0; bufferMs = 0; bufferStart = 0; seeking = false; playMs = 0; playingSince = 0
        Log.i(TAG, "Attempt ${i + 1}/${items.size}: ${item.label} [${item.provider}]")

        // A torrent has to find peers before the first byte arrives, so the engine gets far longer than the 8 s default.
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(item.headers)
            .setConnectTimeoutMs(if (item.viaEngine) 60_000 else 15_000).setReadTimeoutMs(if (item.viaEngine) 60_000 else 15_000)
        val resume = resumePrefs.getLong(imdbId ?: "", 0L).takeIf { it > 60_000 } ?: 0L
        player.setMediaSource(DefaultMediaSourceFactory(this).setDataSourceFactory(http).createMediaSource(MediaItem.fromUri(Uri.parse(item.url))), resume)
        prepareAt = SystemClock.elapsedRealtime()
        player.prepare()
        player.playWhenReady = true
        handler.removeCallbacks(startupTimeout)
        handler.postDelayed(startupTimeout, if (item.viaEngine) ENGINE_STARTUP_TIMEOUT_MS else STARTUP_TIMEOUT_MS)
    }

    /** The current attempt cannot work: log it against its provider, then move on or hand over to Stremio. */
    private fun fail(reason: String) {
        handler.removeCallbacks(startupTimeout)
        Log.w(TAG, "Attempt ${index + 1} failed: $reason")
        val position = if (started) player.currentPosition else 0L
        record(failed = true)
        player.stop()
        if (index + 1 < items.size) {
            Toast.makeText(this, "Trying another version…", Toast.LENGTH_SHORT).show()
            startAttempt(index + 1)
            if (position > 60_000) player.seekTo(position)
        } else fallBackToStremio("no version could be played")
    }

    private fun fallBackToStremio(reason: String) {
        Log.w(TAG, "Falling back to Stremio: $reason")
        Toast.makeText(this, "Couldn't play this automatically, opening Stremio", Toast.LENGTH_LONG).show()
        val id = imdbId
        val uri = if (id != null) "stremio:///detail/movie/$id/$id" else "stremio:///search?search=" + Uri.encode(title)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.stremio.one").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { Toast.makeText(this, "Stremio isn't installed on this TV", Toast.LENGTH_LONG).show() }
        finish()
    }

    // --- telemetry --------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (started) return
            started = true
            startupMs = SystemClock.elapsedRealtime() - prepareAt
            handler.removeCallbacks(startupTimeout)
            Log.i(TAG, "First frame after ${startupMs} ms")
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> if (started && !seeking && player.playWhenReady && bufferStart == 0L) {
                    bufferStart = SystemClock.elapsedRealtime(); bufferEvents++
                }
                Player.STATE_READY -> { closeBuffer(); seeking = false }
                Player.STATE_ENDED -> { closeBuffer(); ended = true; resumePrefs.edit().remove(imdbId ?: "").apply()
                    continueStore.save(ResumeItem(title, null, packageName, null, player.duration, player.duration, null, System.currentTimeMillis()), null) }
            }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // Seeking is the viewer's choice, not a network fault: never count the wait that follows it.
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) { seeking = true; closeBuffer(discard = true) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (isPlaying) playingSince = now else if (playingSince != 0L) { playMs += now - playingSince; playingSince = 0 }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Player error: ${error.errorCodeName}")
            fail(error.errorCodeName)
        }
    }

    private fun closeBuffer(discard: Boolean = false) {
        if (bufferStart != 0L) {
            if (discard) bufferEvents = (bufferEvents - 1).coerceAtLeast(0) else bufferMs += SystemClock.elapsedRealtime() - bufferStart
            bufferStart = 0
        }
    }

    private fun record(failed: Boolean) {
        if (recorded) return
        recorded = true
        closeBuffer()
        if (playingSince != 0L) { playMs += SystemClock.elapsedRealtime() - playingSince; playingSince = 0 }
        val item = items.getOrNull(index) ?: return
        val observed = bandwidth.bitrateEstimate.takeIf { it > 0 }
        // A session the viewer just walked out of quickly says nothing about the source, so only real sessions count.
        if (!failed && !started) return
        if (!failed && playMs < 15_000) return
        history.add(PlaybackRecord(
            imdbId ?: title, item.provider, item.resolution, item.codec, item.source, item.bitrateBps,
            startupMs, observed, bufferEvents, bufferMs, playMs, completedNormally = ended, failed = failed, timestampMs = System.currentTimeMillis()
        ))
        // Throughput seen while playing is only a floor: a low-bitrate film downloads no faster than it plays.
        // So it may raise the estimate, or lower it if playback actually stalled, but never lower it otherwise.
        if (started && observed != null && playMs > 15_000 && (bufferEvents > 0 || observed > network.current().bps)) network.record(observed)
        Log.i(TAG, "Recorded: provider=${item.provider} startup=$startupMs bufferEvents=$bufferEvents bufferMs=$bufferMs playMs=$playMs failed=$failed observed=${observed?.div(1_000_000)} Mbps")
    }

    /** Grabs the actual video frame and files it under Continue watching, with what is needed to resume this stream. */
    private fun snapshotToContinue() {
        if (!started || ended || items.isEmpty() || player.duration <= 0) return
        val position = player.currentPosition
        if (position < 60_000) return
        val item = ResumeItem(
            title, items[index].label.takeIf { it.isNotBlank() }, packageName, null, position, player.duration, null,
            System.currentTimeMillis(), payload
        )
        captureFrame { frame -> continueStore.save(item, frame) }
    }

    private fun captureFrame(onDone: (Bitmap?) -> Unit) {
        val surface = view.videoSurfaceView as? SurfaceView
        if (surface == null || android.os.Build.VERSION.SDK_INT < 26 || surface.width == 0 || surface.height == 0 || !surface.holder.surface.isValid) { onDone(null); return }
        try {
            val bitmap = Bitmap.createBitmap(640, (640f * surface.height / surface.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            PixelCopy.request(surface, bitmap, { result -> onDone(if (result == PixelCopy.SUCCESS) bitmap else null) }, handler)
        } catch (e: Exception) { Log.w(TAG, "Frame capture failed: ${e.javaClass.simpleName}"); onDone(null) }
    }

    private fun saveResume() {
        val id = imdbId ?: return
        if (started && !ended && player.duration > 0) {
            val p = player.currentPosition
            if (p > 60_000 && p < player.duration * 0.95) resumePrefs.edit().putLong(id, p).apply()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            saveResume()
            snapshotToContinue()
            player.pause()
            record(failed = false)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) { record(failed = false); player.release() }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ITEMS = "items"
        private const val EXTRA_IMDB = "imdb"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PAYLOAD = "payload"
        private const val STARTUP_TIMEOUT_MS = 25_000L
        /** A torrent has to find peers and buffer first. */
        private const val ENGINE_STARTUP_TIMEOUT_MS = 75_000L

        /** Starts playback of [ranked] in order, capped at three attempts. */
        fun launch(context: Activity, ranked: List<RankedStream>, title: String, imdbId: String?) {
            val a = JSONArray()
            ranked.filter { it.playable }.take(3).forEach { r ->
                val c = r.candidate
                a.put(JSONObject().put("provider", c.provider).put("url", c.url).put("headers", JSONObject(c.headers as Map<*, *>))
                    .put("res", c.meta.resolution.name).put("codec", c.meta.codec.name).put("src", c.meta.source.name)
                    .put("bitrate", r.requiredBps).put("label", c.meta.qualityLabel()).put("engine", c.viaEngine))
            }
            val payload = JSONObject().put("items", a.toString()).put("imdb", imdbId ?: "").put("title", title).toString()
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ITEMS, a.toString()).putExtra(EXTRA_IMDB, imdbId).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PAYLOAD, payload))
        }

        /** Reopens a stream saved by a Continue watching card. Returns false if the payload is unusable. */
        fun resume(context: Activity, payload: String): Boolean = try {
            val o = JSONObject(payload)
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ITEMS, o.getString("items")).putExtra(EXTRA_IMDB, o.optString("imdb").ifBlank { null })
                .putExtra(EXTRA_TITLE, o.optString("title")).putExtra(EXTRA_PAYLOAD, payload))
            true
        } catch (_: Exception) { false }

        private fun parse(json: String?): List<PlayItem> = try {
            val a = JSONArray(json ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val h = o.optJSONObject("headers")
                PlayItem(
                    o.getString("provider"), o.getString("url"),
                    h?.keys()?.asSequence()?.associateWith { k -> h.optString(k) }.orEmpty(),
                    enumOr(o.optString("res"), Resolution.UNKNOWN), enumOr(o.optString("codec"), VideoCodec.UNKNOWN), enumOr(o.optString("src"), SourceType.UNKNOWN),
                    o.optLong("bitrate", -1).takeIf { it > 0 }, o.optString("label"), o.optBoolean("engine", false)
                )
            }
        } catch (_: Exception) { emptyList() }

        private inline fun <reified T : Enum<T>> enumOr(name: String, default: T): T = enumValues<T>().firstOrNull { it.name == name } ?: default
    }
}
