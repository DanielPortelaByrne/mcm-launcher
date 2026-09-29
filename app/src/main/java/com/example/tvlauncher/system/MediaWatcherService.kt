package com.example.tvlauncher.system

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import com.example.tvlauncher.data.Thumbnails
import com.example.tvlauncher.data.ResumeLinks
import java.util.concurrent.Executors
import com.example.tvlauncher.data.ContinueWatching
import com.example.tvlauncher.data.stream.HandoffRecorder
import com.example.tvlauncher.data.stream.HandoffStore
import com.example.tvlauncher.data.stream.PlaybackStore
import com.example.tvlauncher.data.ResumeItem

private const val TAG = "TvLauncher"

/**
 * Watches other apps' media sessions (this is why the launcher needs notification access) and keeps
 * a note of what is playing: title, the artwork the app supplies and the position. That note feeds
 * the Continue watching row. It records nothing until you have played something with the launcher installed.
 */
class MediaWatcherService : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: MediaSessionManager
    private lateinit var store: ContinueWatching
    private lateinit var handoffs: HandoffStore
    private lateinit var history: PlaybackStore
    private var recorder: HandoffRecorder? = null
    private var lastHandoffFilm: String? = null
    private val fetcher = Executors.newSingleThreadExecutor()
    private val youtube by lazy { com.example.tvlauncher.data.YouTubeSearch(this) }
    private val component by lazy { ComponentName(this, MediaWatcherService::class.java) }
    private val watched = HashMap<String, Pair<MediaController, MediaController.Callback>>()
    private val loggedIds = HashSet<String>()

    /** The last state seen for each app, so a session that ends between two ticks (you went home) is still saved. */
    private class Snap(val item: ResumeItem, val art: Bitmap?, val playing: Boolean, val at: Long)
    private val snaps = HashMap<String, Snap>()

    /** Saves the last snapshot of [pkg], adding the few seconds it kept playing since the last update. */
    private fun flush(pkg: String) {
        val snap = snaps.remove(pkg) ?: return
        val extra = if (snap.playing) (System.currentTimeMillis() - snap.at).coerceIn(0, TICK_MS) else 0L
        store.save(snap.item.copy(positionMs = snap.item.positionMs + extra, lastEngagedMs = System.currentTimeMillis()), snap.art)
    }

    private fun flushAll() { snaps.keys.toList().forEach { flush(it) } }
    private val loggedKeys = HashSet<String>()
    private val fetchedFor = HashSet<String>()
    private val debug by lazy { (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }

    /** While something is playing, refresh its saved position now and then, since exiting an app often skips a final callback. */
    private val ticker = object : Runnable {
        override fun run() {
            watched.values.forEach { (controller, _) -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) record(controller) }
            recorder?.let { r -> if (r.neverStarted(System.currentTimeMillis())) finishHandoff() }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onListenerConnected() {
        manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        store = ContinueWatching(this)
        handoffs = HandoffStore(this)
        history = PlaybackStore(this)
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
            attach(manager.getActiveSessions(component))
        } catch (e: SecurityException) { Log.w(TAG, "Media sessions not permitted: ${e.message}") }
        handler.postDelayed(ticker, TICK_MS)
        connected = true
        Log.i(TAG, "MediaWatcherService connected")
    }

    override fun onListenerDisconnected() {
        connected = false
        flushAll()
        handler.removeCallbacks(ticker)
        watched.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        try { manager.removeOnActiveSessionsChangedListener(sessionsChanged) } catch (_: Exception) {}
    }

    private fun attach(controllers: List<MediaController>) {
        val current = controllers.associateBy { it.packageName }
        (watched.keys - current.keys).toList().forEach { pkg -> watched.remove(pkg)?.let { (c, cb) -> record(c); flush(pkg); if (pkg == STREMIO) finishHandoff(); c.unregisterCallback(cb) } }
        controllers.forEach { controller ->
            if (controller.packageName == packageName || controller.packageName in IGNORED || controller.packageName in watched) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) { record(controller) }
                override fun onMetadataChanged(metadata: MediaMetadata?) { record(controller) }
                override fun onSessionDestroyed() { record(controller); flush(controller.packageName); if (controller.packageName == STREMIO) finishHandoff(); watched.remove(controller.packageName) }
            }
            controller.registerCallback(callback, handler)
            watched[controller.packageName] = controller to callback
            record(controller)
        }
    }

    /** Feeds Stremio's playback into the source-reliability history (the launcher's own player records itself). */
    private fun trackHandoff(controller: MediaController) {
        if (controller.packageName != STREMIO) return
        val now = System.currentTimeMillis()
        val state = controller.playbackState?.state ?: return
        if (recorder == null) {
            val pending = handoffs.current(now) ?: return
            if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_BUFFERING) return
            recorder = HandoffRecorder(pending)
            lastHandoffFilm = pending.contentId.takeIf { it.startsWith("tt") }
        }
        recorder?.onState(state, now)
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE) finishHandoff()
    }

    private fun finishHandoff() {
        val r = recorder ?: return
        recorder = null
        r.finish(System.currentTimeMillis())?.let {
            history.add(it)
            Log.i(TAG, "Recorded Stremio playback: provider=${it.provider} startup=${it.startupTimeMs} bufferEvents=${it.bufferEventCount} playMs=${it.playDurationMs} failed=${it.failed}")
        }
        handoffs.clear()
    }

    private fun record(controller: MediaController) {
        trackHandoff(controller)
        val meta = controller.metadata ?: return
        val state = controller.playbackState ?: return
        if (debug && controller.packageName !in IGNORED && loggedKeys.add(controller.packageName + meta.keySet().sorted() + (meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) != null))) {
            Log.i(TAG, "Metadata of ${controller.packageName}: " + meta.keySet().sorted().joinToString(", ") { k ->
                val v = meta.getString(k) ?: meta.getLong(k).takeIf { it != 0L }?.toString() ?: if (meta.getBitmap(k) != null) "<bitmap>" else "-"
                "$k=" + v.take(80)
            })
        }
        if (state.state !in TRACKED) return
        val title = (meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_TITLE))?.trim()
        if (title.isNullOrBlank()) return

        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) * state.playbackSpeed).toLong()
        }
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)
        if (position < MIN_POSITION_MS) return   // skip previews and trailers

        val art: Bitmap? = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON, MediaMetadata.METADATA_KEY_ALBUM_ART)
            .firstNotNullOfOrNull { meta.getBitmap(it) }
        val subtitle = (meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_ARTIST))?.trim()?.ifBlank { null }

        val ids = listOf(MediaMetadata.METADATA_KEY_MEDIA_ID, MediaMetadata.METADATA_KEY_MEDIA_URI, MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI).mapNotNull { meta.getString(it)?.takeIf { v -> v.isNotBlank() } }
        if (debug && loggedIds.add(controller.packageName + title)) Log.i(TAG, "Media ids for '$title' (${controller.packageName}): ${ids.joinToString(" | ") { it.take(120) }}")
        val filmId = if (controller.packageName == STREMIO) lastHandoffFilm?.let { "imdb:$it" } else null
        val item = ResumeItem(title, subtitle, controller.packageName, null, position, duration.coerceAtLeast(0), null, System.currentTimeMillis(),
            mediaId = (listOfNotNull(filmId) + ids).joinToString("\n").ifBlank { null })
        snaps[controller.packageName] = Snap(item, art, state.state == PlaybackState.STATE_PLAYING, System.currentTimeMillis())
        if (art != null) { store.save(item, art); return }

        // No bitmap from the app: keep an existing thumbnail, otherwise look one up from the ids it gave us.
        val existing = store.load(50).firstOrNull { it.packageName == item.packageName && it.title == item.title }
        store.save(item, null)
        val youtubeApp = item.packageName == "org.smarttube.stable" || item.packageName.startsWith("com.google.android.youtube")
        val needsPicture = existing?.imageUri == null
        val needsVideoId = youtubeApp && ResumeLinks.youtubeId(existing?.mediaId) == null && ResumeLinks.youtubeId(item.mediaId) == null
        if ((needsPicture || needsVideoId) && fetchedFor.add(item.packageName + title)) {
            val urls = if (needsPicture) Thumbnails.candidates(ids) else emptyList()
            fetcher.execute {
                // 1) an address the app gave us, 2) a search for the title through the YouTube API (needs a key).
                val match = if (youtubeApp) youtube.find(title, subtitle?.substringBefore(" \u2022 ")) else null
                val bmp = (if (urls.isNotEmpty()) Thumbnails.download(urls) else null)
                    ?: if (needsPicture && match != null) Thumbnails.download(listOf(match.thumbnailUrl)) else null
                val withId = match?.let { item.copy(mediaId = listOfNotNull("youtube:${it.id}", item.mediaId).joinToString("\n")) } ?: item
                if (bmp != null || match != null) store.save(withId.copy(lastEngagedMs = System.currentTimeMillis()), bmp) else fetchedFor.remove(item.packageName + title)
            }
        }
    }

    companion object {
        /** True while Android has this service bound and delivering media sessions. */
        @Volatile var connected = false
            private set
        private const val TICK_MS = 10_000L
        val TRACKED = setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_STOPPED)
        private const val MIN_POSITION_MS = 45_000L
        const val STREMIO = "com.stremio.one"
        /** Music apps: this row is for things to watch. */
        val IGNORED = setOf("com.spotify.tv.android", "com.google.android.youtube.tvmusic", "com.google.android.music")
    }
}
