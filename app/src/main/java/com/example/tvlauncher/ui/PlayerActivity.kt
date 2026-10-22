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
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import androidx.media3.common.C
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import android.view.KeyEvent
import android.view.Gravity
import android.widget.TextView
import com.example.tvlauncher.data.stream.NetworkEstimator
import com.example.tvlauncher.data.stream.PlaybackRecord
import com.example.tvlauncher.data.stream.PlaybackStore
import com.example.tvlauncher.data.stream.RankedStream
import com.example.tvlauncher.data.stream.Resolution
import com.example.tvlauncher.data.stream.SourceType
import com.example.tvlauncher.data.stream.VideoCodec
import org.json.JSONArray
import org.json.JSONObject
import com.example.tvlauncher.data.jellyfin.EpisodeAutoplay
import com.example.tvlauncher.data.jellyfin.JellyfinClient
import com.example.tvlauncher.data.jellyfin.JellyfinItem
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.Type
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar

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
    /** Loading screen for a stream: artwork, title and live status, until the first frame. */
    private var loadingView: View? = null
    private var loadingStatus: TextView? = null
    private var titleLabel: TextView? = null
    private var art: String? = null
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var captionsButton: TextView
    private lateinit var nextCard: NextEpisodeCard
    private var autoplay = EpisodeAutoplay()
    private var nextItem: JellyfinItem? = null
    private var nextPlan: JellyfinPlayback.Plan? = null
    private var episodeGeneration = 0
    private var foreground = false
    private val episodeLookup = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var episodeImages: RemoteImages? = null
    private val jellyfin by lazy { JellyfinClient(this) }
    private val cancelNext = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { cancelAutoplay() }
    }
    private val captionPrefs by lazy { getSharedPreferences("player", MODE_PRIVATE) }
    /** Whether captions are wanted. Remembered, and on by default, as the player has always shown them. */
    private var captionsOn = true
    private lateinit var items: List<PlayItem>
    private var imdbId: String? = null
    /** A live channel: no resume point, no Continue watching card, and no Stremio to fall back on. */
    private var live = false
    /** Set for a Jellyfin title: playback is reported to the server, so "continue watching" and watched marks follow the viewer. */
    private var jf: com.example.tvlauncher.data.jellyfin.JellyfinReport? = null
    private val jfSession = com.example.tvlauncher.data.jellyfin.JellyfinPlaybackSession()
    private val reporter by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    private var title: String = ""

    private lateinit var history: PlaybackStore
    private lateinit var continueStore: ContinueWatching
    private var payload: String? = null
    private lateinit var network: NetworkEstimator
    private lateinit var bandwidth: DefaultBandwidthMeter
    private val resumePrefs by lazy { getSharedPreferences("resume_positions", MODE_PRIVATE) }

    private var index = 0
    private var audioFallbackQueued = false
    private var releasingPlayer = false
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
        override fun run() { saveResume(); snapshotToContinue(); reportToJellyfin("Progress"); handler.postDelayed(this, 15_000) }
    }
    private val autoplayTick = object : Runnable {
        override fun run() {
            if (!foreground || isFinishing || isDestroyed || !ended) return
            val now = SystemClock.elapsedRealtime()
            nextCard.countdown(autoplay.remainingMs(now), autoplay.fraction(now))
            if (autoplay.takeNext(now) != null) {
                startNextEpisode()
            } else if (autoplay.remainingMs(now) != null) {
                handler.postDelayed(this, if (Motion.animationsOn(nextCard)) 16L else 250L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        items = parse(intent.getStringExtra(EXTRA_ITEMS))
        imdbId = intent.getStringExtra(EXTRA_IMDB)
        live = intent.getBooleanExtra(EXTRA_LIVE, false)
        jf = com.example.tvlauncher.data.jellyfin.JellyfinReport.fromJson(intent.getStringExtra(EXTRA_JF))
        title =intent.getStringExtra(EXTRA_TITLE).orEmpty()
        art = intent.getStringExtra(EXTRA_ART)
        history = PlaybackStore(this)
        continueStore = ContinueWatching(this)
        payload = intent.getStringExtra(EXTRA_PAYLOAD)
        network = NetworkEstimator(this)
        if (items.isEmpty()) { fallBackToStremio("nothing to play"); return }

        bandwidth = DefaultBandwidthMeter.getSingletonInstance(this)
        trackSelector = DefaultTrackSelector(this)
        captionsOn = captionPrefs.getBoolean("captions", true)
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setTrackSelector(trackSelector).setBandwidthMeter(bandwidth).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .apply {
                // Library playback starts promptly over the LAN. Keep the normal
                // long-term and rebuffer reserves; only the first-picture threshold changes.
                if (jf != null && !live) setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                    750, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).build())
                // A torrent stream arrives unevenly: start quickly, then hold a deep buffer so one slow stretch doesn't stop playback.
                if (streamOnly()) setLoadControl(streamLoadControl())
            }.build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 4000
            setBackgroundColor(0xFF000000.toInt())
            keepScreenOn = true
        }
        // The CC button lives in the controls' bottom bar, beside the settings gear, so the remote can select it.
        // The remote's captions key works at any time as well.
        captionsButton = TextView(this).apply {
            textSize = 16f; gravity = Gravity.CENTER; setPadding(28, 0, 28, 0)
            isFocusable = true; isClickable = true
            setOnClickListener { toggleCaptions() }
            setOnFocusChangeListener { v, focused ->
                v.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 40f; setColor(if (focused) 0xFFF3E9CF.toInt() else 0xCC302319.toInt()) }
                (v as TextView).setTextColor(if (focused) 0xFF26271D.toInt() else 0xFFF3E9CF.toInt())
            }
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 40f; setColor(0xCC302319.toInt()) }
        }
        val bottomBar = view.findViewById<android.view.ViewGroup?>(androidx.media3.ui.R.id.exo_bottom_bar)
        val settingsGear = view.findViewById<View?>(androidx.media3.ui.R.id.exo_settings)
        if (bottomBar != null) {
            val at = settingsGear?.let { bottomBar.indexOfChild(it) }?.takeIf { it >= 0 } ?: bottomBar.childCount
            bottomBar.addView(captionsButton, at, android.widget.LinearLayout.LayoutParams(-2, dp(40)).apply { setMargins(dp(8), 0, dp(8), 0); gravity = Gravity.CENTER_VERTICAL })
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(view, -1, -1) }
        nextCard = NextEpisodeCard(this, play = { playNextNow() }, cancel = { cancelAutoplay() })
        root.addView(nextCard, FrameLayout.LayoutParams(-1, -1))
        onBackPressedDispatcher.addCallback(this, cancelNext)
        titleLabel = Type.text(this, title, Type.Style.TITLE).apply { textSize = 26f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; visibility = View.GONE }
        root.addView(titleLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(48), dp(32), dp(48), 0) })
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { updateTitleLabel(it == View.VISIBLE) })
        if (streamOnly()) {
            loadingView = buildLoading().also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
            pollStatus()
        }
        setContentView(root)
        applyCaptions()
        updateCaptionsLabel()
        player.addListener(listener)
        handler.postDelayed(saveTick, 15_000)
        val report = jf
        if (report == null) startAttempt(0) else {
            val client = jellyfin
            reporter.execute {
                val fresh = client.item(report.itemId)
                handler.post {
                    if (isFinishing || isDestroyed) return@post
                    jf = fresh?.let { report.copy(sourceId = it.sourceId, startMs = if (it.played) 0L else it.resumeMs) } ?: report
                    startAttempt(0)
                    prefetchNextEpisode(fresh)
                }
            }
        }
    }

    private fun prefetchNextEpisode(current: JellyfinItem?) {
        val report = jf ?: return
        if (live || current?.isEpisode != true || current.seriesId == null ||
            com.example.tvlauncher.data.jellyfin.JellyfinApi.cleanBase(jellyfin.server) != report.base) return
        val generation = ++episodeGeneration
        episodeLookup.execute {
            val next = jellyfin.followingEpisode(current) ?: return@execute
            val name = listOfNotNull(next.seriesName, next.episodeLabel(), next.name).joinToString(" · ")
            val plan = JellyfinPlayback.prepare(jellyfin, next, name, "jf:${next.id}")
            handler.post {
                if (generation != episodeGeneration || isFinishing || isDestroyed) return@post
                nextItem = next; nextPlan = plan
                autoplay.ready(next.id, SystemClock.elapsedRealtime())
                nextCard.bind(next)
                val images = episodeImages ?: RemoteImages(this).also { episodeImages = it }
                images.load(jellyfin.imageUrl(next.id, 300), jellyfin.playbackHeaders(), maxWidth = 480) { bitmap ->
                    if (generation == episodeGeneration) nextCard.artwork.setImageBitmap(bitmap)
                }
                showNextEpisode()
            }
        }
    }

    private fun showNextEpisode() {
        if (!ended || !foreground || nextPlan == null || autoplay.cancelled) return
        view.hideController()
        nextCard.showCard()
        cancelNext.isEnabled = true
        handler.removeCallbacks(autoplayTick)
        autoplayTick.run()
    }

    private fun cancelAutoplay() {
        autoplay.cancel()
        handler.removeCallbacks(autoplayTick)
        if (::nextCard.isInitialized) nextCard.hideCard()
        cancelNext.isEnabled = false
        if (::view.isInitialized) { view.requestFocus(); view.showController() }
    }

    private fun playNextNow() {
        if (foreground && autoplay.takeNext(SystemClock.elapsedRealtime(), playNow = true) != null) startNextEpisode()
    }

    private fun startNextEpisode() {
        val plan = nextPlan ?: return
        val next = nextItem ?: return
        handler.removeCallbacks(autoplayTick)
        nextCard.hideCard(); cancelNext.isEnabled = false
        record(failed = false)
        // Reuse the player and activity: a long viewing session never builds an activity stack.
        val nextIntent = playbackIntent(this, plan.streams, plan.title, plan.resumeKey, plan.report)
        intent = nextIntent
        items = parse(nextIntent.getStringExtra(EXTRA_ITEMS))
        imdbId = plan.resumeKey; title = plan.title; jf = plan.report
        payload = nextIntent.getStringExtra(EXTRA_PAYLOAD)
        nextItem = null; nextPlan = null; autoplay = EpisodeAutoplay(); episodeGeneration++
        startAttempt(0)
        prefetchNextEpisode(next)
    }

    // --- attempts ---------------------------------------------------------------------------------

    private fun startAttempt(i: Int) {
        index = i
        if (i == 0) audioFallbackQueued = false
        val item = items[i]
        started = false; ended = false; recorded = false
        startupMs = null; bufferEvents = 0; bufferMs = 0; bufferStart = 0; seeking = false; playMs = 0; playingSince = 0
        Log.i(TAG, "Attempt ${i + 1}/${items.size}: ${item.label} [${item.provider}]")

        // A torrent has to find peers before the first byte arrives, so the engine gets far longer than the 8 s default.
        // A torrent has to find peers, and a live torrent can pause for a while between pieces: wait for those, not 15 s.
        val slow = item.viaEngine || item.provider == STREAM_PROVIDER
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(item.headers)
            .setConnectTimeoutMs(if (slow) 60_000 else 15_000).setReadTimeoutMs(if (slow) 60_000 else 15_000)
        // A Jellyfin title picks up where the server says the viewer left off; anything else, where this TV last did.
        val serverStart = jf?.startMs
        val resume = com.example.tvlauncher.data.jellyfin.JellyfinApi.resumePosition(serverStart, resumePrefs.getLong(imdbId ?: "", 0L))
        // Retry transient network errors more patiently for streams: a stalled torrent recovers far more often than it dies.
        val retries = if (slow) STREAM_LOAD_RETRIES else DEFAULT_LOAD_RETRIES
        player.setMediaSource(DefaultMediaSourceFactory(this, extractorsFor(item)).setDataSourceFactory(http)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(retries))
            .createMediaSource(MediaItem.fromUri(Uri.parse(item.url))), resume)
        prepareAt = SystemClock.elapsedRealtime()
        player.prepare()
        player.playWhenReady = true
        handler.removeCallbacks(startupTimeout)
        handler.postDelayed(startupTimeout, if (slow) ENGINE_STARTUP_TIMEOUT_MS else STARTUP_TIMEOUT_MS)
    }

    /** The current attempt cannot work: log it against its provider, then move on or hand over to Stremio. */
    private fun fail(reason: String) {
        if (releasingPlayer || isFinishing || isDestroyed) return
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

    /**
     * Most MKV files keep their seek index (Cues) at the very end. A torrent delivers that last, so waiting for it would
     * delay playback until the download was nearly finished. A stream plays without it; seeking is then approximate.
     */
    private fun extractorsFor(item: PlayItem): DefaultExtractorsFactory = DefaultExtractorsFactory().apply {
        if (item.provider == STREAM_PROVIDER) setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
    }

    /** Streams from the home service never hand over to Stremio: the viewer asked for the film in this player. */
    private fun streamOnly(): Boolean = items.isNotEmpty() && items.all { it.provider == STREAM_PROVIDER }

    /** Start quickly, hold up to 90 s ahead, and rebuild after a rebuffer only once 6 s are buffered again. */
    private fun streamLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(STREAM_MIN_BUFFER_MS, STREAM_MAX_BUFFER_MS, STREAM_START_BUFFER_MS, STREAM_REBUFFER_BUFFER_MS)
        .setTargetBufferBytes(STREAM_TARGET_BUFFER_BYTES)
        .build()

    private fun fallBackToStremio(reason: String) {
        if (streamOnly()) {
            Log.w(TAG, "Stream failed: $reason")
            Toast.makeText(this, "Couldn't play this stream. Try again in a minute.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        if (live) {
            Log.w(TAG, "Live channel failed: $reason")
            Toast.makeText(this, "This channel isn't available right now", Toast.LENGTH_LONG).show()
            finish(); return
        }
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
        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            updateCaptionsLabel()
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            audio.forEach { group ->
                val format = group.mediaTrackGroup.getFormat(0)
                Log.i(TAG, "Audio: ${format.sampleMimeType}, channels=${format.channelCount}, selected=${group.isSelected}, supported=${group.isTrackSupported(0)}")
            }
            // A valid picture does not mean its soundtrack can play. For example, Chromecast HD
            // cannot decode AC-3/E-AC-3 when the connected TV only accepts stereo PCM.
            val report = jf ?: return
            if (index != 0 || audioFallbackQueued || audio.isEmpty() || tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) return
            audioFallbackQueued = true
            handler.post {
                if (!foreground || releasingPlayer || isFinishing || isDestroyed || index != 0 || jf?.itemId != report.itemId) return@post
                val stereo = items[0].copy(
                    url = com.example.tvlauncher.data.jellyfin.JellyfinApi.stereoStreamUrl(
                        report.base, report.itemId, report.sourceId, "mcm-stereo-${jfSession.id}"),
                    label = "Stereo audio")
                items = listOf(items[0], stereo) + items.drop(1)
                fail("Soundtrack is unsupported; converting audio to stereo")
            }
        }

        override fun onRenderedFirstFrame() {
            if (started) return
            started = true
            hideLoading()
            startupMs = SystemClock.elapsedRealtime() - prepareAt
            handler.removeCallbacks(startupTimeout)
            Log.i(TAG, "First frame after ${startupMs} ms")
            reportToJellyfin("Playing")
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> if (started && !seeking && player.playWhenReady && bufferStart == 0L) {
                    bufferStart = SystemClock.elapsedRealtime(); bufferEvents++
                }
                Player.STATE_READY -> { closeBuffer(); seeking = false }
                Player.STATE_ENDED -> if (!ended) {
                    closeBuffer(); ended = true; reportToJellyfin("Stopped", atEnd = true); resumePrefs.edit().remove(imdbId ?: "").apply()
                    continueStore.save(ResumeItem(title, null, packageName, null, player.duration, player.duration, null, System.currentTimeMillis()), null)
                    if (!foreground) autoplay.cancel()
                    autoplay.end(SystemClock.elapsedRealtime())
                    showNextEpisode()
                }
            }
        }

        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
            // Seeking is the viewer's choice, not a network fault: never count the wait that follows it.
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                seeking = true; closeBuffer(discard = true)
                if (ended && new.positionMs < player.duration) {
                    ended = false; handler.removeCallbacks(autoplayTick); nextCard.hideCard(); cancelNext.isEnabled = false
                    autoplay = EpisodeAutoplay().also { state -> nextItem?.let { state.ready(it.id, SystemClock.elapsedRealtime()) } }
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (started && !ended) {
                if (isPlaying && jfSession.stopped) reportToJellyfin("Playing")
                reportToJellyfin("Progress")
            }
            val now = SystemClock.elapsedRealtime()
            if (isPlaying) playingSince = now else if (playingSince != 0L) { playMs += now - playingSince; playingSince = 0 }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Player error: ${error.errorCodeName}")
            fail(error.errorCodeName)
        }
    }

    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    /** The film's artwork dimmed under its title, with what the home server is doing right now. */
    private fun buildLoading(): View {
        val box = FrameLayout(this).apply { setBackgroundColor(0xFF0E0A07.toInt()) }
        val artView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.45f }
        box.addView(artView, FrameLayout.LayoutParams(-1, -1))
        art?.let { url -> com.example.tvlauncher.ui.RemoteImages(this).load(url, maxWidth = 1280) { artView.setImageBitmap(it) } }
        box.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xFF0E0A07.toInt(), 0x330E0A07))
        }, FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(64), 0, dp(64), dp(80)) }
        column.addView(Type.text(this, "NOW LOADING", Type.Style.EYEBROW).apply { setTextColor(getColor(com.example.tvlauncher.R.color.butter)) })
        column.addView(Type.text(this, title, Type.Style.TITLE).apply { textSize = 52f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        loadingStatus = Type.text(this, "Finding a copy…", Type.Style.CAPTION).apply { textSize = 18f; setTextColor(0xD9F3E9CF.toInt()) }
        column.addView(loadingStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        column.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(dp(360), dp(4)).apply { topMargin = dp(16) })
        box.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        return box
    }

    /** Asks the home server where the film is, once a second, until the first frame is on screen. */
    private fun pollStatus() {
        if (started || isFinishing || !streamOnly()) return
        val url = items.firstOrNull()?.url?.replace("/stream/", "/status/") ?: return
        Thread {
            val info = try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 4000; c.readTimeout = 4000
                try { if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().use { it.readText() }) else null } finally { c.disconnect() }
            } catch (_: Exception) { null }
            runOnUiThread { applyStatus(info) }
        }.start()
    }

    private fun applyStatus(info: JSONObject?) {
        if (started || isFinishing) return
        when (info?.optString("phase")) {
            "connecting" -> loadingStatus?.text = "Finding peers · ${info.optInt("seeds")} seeds"
            "buffering" -> loadingStatus?.text = "Buffering · ${info.optInt("peers")} peers · ${(info.optDouble("progress") * 100).toInt()}% of the film so far"
            "ready" -> loadingStatus?.text = "Starting…"
            "failed" -> {
                Toast.makeText(this, "Couldn't start this film. Try again in a minute.", Toast.LENGTH_LONG).show()
                finish(); return
            }
            else -> loadingStatus?.text = "Connecting to the sources…"
        }
        handler.postDelayed({ pollStatus() }, 1000)
    }

    private fun hideLoading() {
        loadingView?.animate()?.alpha(0f)?.setDuration(400)?.withEndAction {
            loadingView?.visibility = View.GONE
            updateTitleLabel(view.isControllerFullyVisible)
        }?.start()
    }

    /** The title sits over the picture only while the controls are up, and never over the loading screen. */
    private fun updateTitleLabel(controlsVisible: Boolean) {
        titleLabel?.visibility = if (controlsVisible && loadingView?.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun hasCaptions(): Boolean = ::player.isInitialized && player.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }

    /** Turns captions on or off for everything that plays from here, and remembers the choice. */
    private fun toggleCaptions() {
        captionsOn = !captionsOn
        captionPrefs.edit().putBoolean("captions", captionsOn).apply()
        applyCaptions()
        if (captionsOn && !hasCaptions()) Toast.makeText(this, "This stream has no captions", Toast.LENGTH_SHORT).show()
        updateCaptionsLabel()
    }

    private fun applyCaptions() {
        trackSelector.parameters = trackSelector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsOn).build()
    }

    private fun updateCaptionsLabel() {
        if (!::captionsButton.isInitialized) return
        captionsButton.text = when { !hasCaptions() -> "CC · none"; captionsOn -> "CC · On"; else -> "CC · Off" }
    }

    /** The remote's captions key toggles captions from anywhere in the player. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::nextCard.isInitialized && nextCard.visibility == View.VISIBLE &&
            event.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) cancelAutoplay() else playNextNow()
            }
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) toggleCaptions()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun closeBuffer(discard: Boolean = false) {
        if (bufferStart != 0L) {
            if (discard) bufferEvents = (bufferEvents - 1).coerceAtLeast(0) else bufferMs += SystemClock.elapsedRealtime() - bufferStart
            bufferStart = 0
        }
    }

    private fun record(failed: Boolean) {
        if (recorded || live) return   // a channel's startup says nothing about the film sources the ranker learns from
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
        if (live) return
        if (!started || ended || items.isEmpty() || player.duration <= 0) return
        val position = player.currentPosition
        if (position < 60_000) return
        val item = ResumeItem(
            title, items[index].label.takeIf { it.isNotBlank() }, packageName, null, position, player.duration, null,
            System.currentTimeMillis(), payload
        )
        val generation = episodeGeneration
        captureFrame { frame -> if (generation == episodeGeneration && !ended) continueStore.save(item, frame) }
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
        if (live) return
        val id = imdbId ?: return
        if (started && !ended && player.duration > 0) {
            val p = player.currentPosition
            if (p > 60_000 && p < player.duration * 0.95) resumePrefs.edit().putLong(id, p).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (::nextCard.isInitialized) showNextEpisode()
    }

    override fun onPause() {
        foreground = false
        if (ended) autoplay.cancel()
        handler.removeCallbacks(autoplayTick)
        if (::nextCard.isInitialized) nextCard.countdown(null, 0f)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            saveResume()
            snapshotToContinue()
            player.pause()
            reportToJellyfin("Stopped")
            record(failed = false)
        }
    }

    /**
     * Tells Jellyfin where playback is. "Playing" opens the session, "Progress" updates it (also on pause), and
     * "Stopped" closes it with the final position, at which point Jellyfin counts a title as watched if it is near the end.
     */
    private fun reportToJellyfin(kind: String, pausedOverride: Boolean? = null, atEnd: Boolean = false) {
        val r = jf ?: return
        if (!::player.isInitialized) return
        val paused = pausedOverride ?: !player.playWhenReady   // read only once the player exists
        if (!jfSession.accept(kind)) return
        val position = if (atEnd && player.duration > 0) player.duration else player.currentPosition
        val body = com.example.tvlauncher.data.jellyfin.JellyfinApi.playbackBody(r.itemId, r.sourceId, position, paused, jfSession.id)
        val url = com.example.tvlauncher.data.jellyfin.JellyfinApi.reportUrl(r.base, kind)
        val headers = items.getOrNull(index)?.headers.orEmpty()
        reporter.execute {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 5000; c.readTimeout = 5000; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                try {
                    c.outputStream.use { it.write(body.toByteArray()) }
                    val code = c.responseCode
                    if (code !in 200..299) Log.w("Jellyfin", "Playback $kind report failed (HTTP $code)")
                } finally { c.disconnect() }
            } catch (e: Exception) { Log.w("Jellyfin", "Playback $kind report failed: ${e.javaClass.simpleName}") }
        }
    }

    override fun onDestroy() {
        episodeGeneration++
        episodeLookup.shutdownNow()
        episodeImages?.close()
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) {
            record(failed = false)
            releasingPlayer = true
            player.removeListener(listener)
            player.release()
        }
        reporter.shutdown() // let the final stop report finish, then release its thread
        super.onDestroy()
    }

    companion object {
        /** Provider name for films from the home stream service. */
        const val STREAM_PROVIDER = "Stream"
        private const val STREAM_MIN_BUFFER_MS = 30_000
        private const val STREAM_MAX_BUFFER_MS = 90_000
        private const val STREAM_START_BUFFER_MS = 1_500
        private const val STREAM_REBUFFER_BUFFER_MS = 6_000
        private const val STREAM_TARGET_BUFFER_BYTES = 96 * 1024 * 1024
        private const val STREAM_LOAD_RETRIES = 8
        private const val DEFAULT_LOAD_RETRIES = 3

        private const val EXTRA_ITEMS = "items"
        private const val EXTRA_IMDB = "imdb"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_ART = "art"
        private const val EXTRA_JF = "jf"
        private const val STARTUP_TIMEOUT_MS = 25_000L
        /** A torrent has to find peers and buffer first. */
        private const val ENGINE_STARTUP_TIMEOUT_MS = 75_000L

        /** Starts playback of [ranked] in order, capped at three attempts. */
        fun launch(context: Activity, ranked: List<RankedStream>, title: String, imdbId: String?, report: com.example.tvlauncher.data.jellyfin.JellyfinReport? = null, artUrl: String? = null) {
            context.startActivity(playbackIntent(context, ranked, title, imdbId, report).putExtra(EXTRA_ART, artUrl))
        }

        private fun playbackIntent(context: Activity, ranked: List<RankedStream>, title: String, imdbId: String?, report: com.example.tvlauncher.data.jellyfin.JellyfinReport?): Intent {
            val a = JSONArray()
            ranked.filter { it.playable }.take(3).forEach { r ->
                val c = r.candidate
                a.put(JSONObject().put("provider", c.provider).put("url", c.url).put("headers", JSONObject(c.headers as Map<*, *>))
                    .put("res", c.meta.resolution.name).put("codec", c.meta.codec.name).put("src", c.meta.source.name)
                    .put("bitrate", r.requiredBps).put("label", c.meta.qualityLabel()).put("engine", c.viaEngine))
            }
            val payload = JSONObject().put("items", a.toString()).put("imdb", imdbId ?: "").put("title", title).put("jf", report?.toJson() ?: "").toString()
            return Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ITEMS, a.toString()).putExtra(EXTRA_IMDB, imdbId).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_PAYLOAD, payload).putExtra(EXTRA_JF, report?.toJson())
        }

        /** Plays one live channel from [url]. [headers] carry any sign-in the stream needs. */
        fun launchLive(context: Activity, title: String, url: String, headers: Map<String, String> = emptyMap()) {
            val a = JSONArray().put(JSONObject().put("provider", "Live").put("url", url).put("headers", JSONObject(headers as Map<*, *>))
                .put("res", Resolution.UNKNOWN.name).put("codec", VideoCodec.UNKNOWN.name).put("src", SourceType.UNKNOWN.name)
                .put("bitrate", 0).put("label", title).put("engine", false))
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ITEMS, a.toString()).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_LIVE, true))
        }

        /** Reopens a stream saved by a Continue watching card. Returns false if the payload is unusable. */
        fun resume(context: Activity, payload: String): Boolean = try {
            val o = JSONObject(payload)
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ITEMS, o.getString("items")).putExtra(EXTRA_IMDB, o.optString("imdb").ifBlank { null })
                .putExtra(EXTRA_TITLE, o.optString("title")).putExtra(EXTRA_PAYLOAD, payload).putExtra(EXTRA_JF, o.optString("jf").ifBlank { null }))
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
