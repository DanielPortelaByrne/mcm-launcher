package com.example.tvlauncher.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.tvlauncher.R
import com.example.tvlauncher.data.AppEntry
import com.example.tvlauncher.data.jellyfin.JellyfinClient
import com.example.tvlauncher.data.live.IptvChannel
import com.example.tvlauncher.data.live.IptvRepository
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.SectionHeader
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.room.JellyfinConnectSheet
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The Live TV page: what can be watched live right now, in three shelves. "On this TV" opens the tuner and any
 * live-TV apps that are installed. "Your channels" are the owner's own channels from Jellyfin (the horror, action
 * and cartoon channels built in Tunarr). "Free channels" is a public UK list that plays in the launcher's own player.
 */
class LiveTvPanel(
    private val activity: Activity,
    private val overlay: View,
    private val content: LinearLayout,
    private val jellyfin: JellyfinClient,
    private val connectSheet: JellyfinConnectSheet,
    private val shelfBuilder: ShelfLayoutBuilder,
    private val openTuner: () -> Unit,
    private val onLaunchApp: (AppEntry) -> Unit
) {
    private val worker = Executors.newFixedThreadPool(3)
    private val images = Executors.newFixedThreadPool(2)
    private val iptv = IptvRepository(activity)
    private val bitmaps = android.util.LruCache<String, Bitmap>(60)
    private var generation = 0   // each build owns its shelves; a late answer for an older one is dropped
    private var apps: List<AppEntry> = emptyList()

    val isVisible: Boolean get() = overlay.visibility == View.VISIBLE

    fun show(installed: List<AppEntry>) {
        apps = installed
        overlay.visibility = View.VISIBLE
        com.example.tvlauncher.design.Motion.enter(overlay, null, 0f)
        build()
    }

    fun hide() { overlay.visibility = View.GONE; generation++ }
    fun close() { worker.shutdownNow(); images.shutdownNow() }

    // ---------------------------------------------------------------------------------------------

    private fun build() {
        val gen = ++generation
        content.removeAllViews()
        content.addView(Type.text(activity, "Live TV", Type.Style.TITLE))
        content.addView(Type.text(activity, "Watch something that is on right now", Type.Style.CAPTION).apply { setPadding(0, px(R.dimen.space_1), 0, 0) })

        // 1. On this TV
        content.addView(SectionHeader.build(activity, "On this TV", "The tuner and your live apps"))
        val tv = tvShelf()
        val tvRow = row()
        content.addView(tvRow.first, rowParams())
        if (tv.isEmpty()) tvRow.second.addView(note("No live TV app found on this TV"))
        else shelfBuilder.buildFeatureRow(tvRow.second, tv)
        tvRow.second.getChildAt(0)?.let { requestFocusRobust(overlay as android.view.ViewGroup, it) }

        // 2. Your channels (Jellyfin)
        content.addView(SectionHeader.build(activity, "Your channels", "From your Jellyfin"))
        val mine = row()
        content.addView(mine.first, rowParams())
        loadMine(gen, mine.second)

        // 3. Free channels
        content.addView(SectionHeader.build(activity, "Free channels", "UK, no sign-in needed"))
        val free = row()
        content.addView(free.first, rowParams())
        free.second.addView(note("Loading channels…"))
        worker.execute {
            val list = priorityOrder(iptv.channels()).take(MAX_FREE)
            activity.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                free.second.removeAllViews()
                if (list.isEmpty()) free.second.addView(note("Couldn't load the channel list right now"))
                list.forEachIndexed { i, ch -> free.second.addView(card(ch.name, ch.group ?: "Live", ch.logo, emptyMap()) { playFree(ch) }, cardParams(i)) }
            }
        }
    }

    private fun tvShelf(): List<ShelfTile> {
        val tiles = ArrayList<ShelfTile>()
        val tuner = apps.find { it.packageName == "com.tcl.tv" } ?: apps.find { it.packageName == "com.android.tv" }
        if (tuner != null) tiles += ShelfTile("TV tuner", tuner.icon, tuner.packageName, onSelect = { openTuner() })
        apps.filter { app -> app !== tuner && LIVE_APP_HINTS.any { app.label.contains(it, ignoreCase = true) } }
            .sortedBy { it.label.lowercase() }
            .forEach { tiles += ShelfTile.forApp(it, onLaunchApp) }
        return tiles
    }

    private fun loadMine(gen: Int, into: LinearLayout) {
        if (!jellyfin.isConnected) {
            into.addView(actionCard("Connect Jellyfin", "Sign in to see your channels") {
                connectSheet.show(jellyfin) { if (isVisible) build() }
            })
            return
        }
        into.addView(note("Loading your channels…"))
        worker.execute {
            val channels = jellyfin.liveChannels()
            activity.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                into.removeAllViews()
                if (channels.isEmpty()) { into.addView(note("No live channels on your server yet")); return@runOnUiThread }
                channels.forEachIndexed { i, ch ->
                    val title = if (ch.number != null) "${ch.number} · ${ch.name}" else ch.name
                    val image = if (ch.hasImage) jellyfin.imageUrl(ch.id) else null
                    into.addView(card(title, ch.nowPlaying?.let { "Now: $it" } ?: "On air", image, jellyfin.playbackHeaders()) { playMine(ch.id, ch.name) }, cardParams(i))
                }
            }
        }
    }

    private fun playMine(id: String, name: String) {
        Toast.makeText(activity, "Tuning $name…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val url = jellyfin.liveStreamUrl(id)
            activity.runOnUiThread {
                if (url == null) Toast.makeText(activity, "Couldn't open $name right now", Toast.LENGTH_LONG).show()
                else PlayerActivity.launchLive(activity, name, url, jellyfin.playbackHeaders())
            }
        }
    }

    private fun playFree(ch: IptvChannel) = PlayerActivity.launchLive(activity, ch.name, ch.url)

    // --- Pieces ----------------------------------------------------------------------------------

    /** A horizontally scrolling shelf: the scroller (to add to the page) and the row inside it (to fill). */
    private fun row(): Pair<View, LinearLayout> {
        val margin = px(R.dimen.page_margin)
        val inner = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, px(R.dimen.space_2), 0, px(R.dimen.space_2)) }
        val scroller = CalmHorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(margin, 0, margin, 0)
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(px(R.dimen.edge_fade))
            addView(inner)
        }
        return scroller to inner
    }

    private fun rowParams() = LinearLayout.LayoutParams(-1, -2).apply { marginStart = -px(R.dimen.page_margin); marginEnd = -px(R.dimen.page_margin) }
    private fun cardParams(index: Int) = LinearLayout.LayoutParams(dp(250), -2).apply { if (index > 0) marginStart = px(R.dimen.card_gutter) }

    private fun note(text: String) = Type.text(activity, text, Type.Style.CAPTION).apply { setPadding(px(R.dimen.space_1), px(R.dimen.space_3), 0, px(R.dimen.space_3)) }

    /** A channel card: logo or artwork on top, name and what is on beneath. Lights ivory on focus. */
    private fun card(title: String, subtitle: String, imageUrl: String?, headers: Map<String, String>, onSelect: () -> Unit): View {
        val radius = dp(20).toFloat()
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
            clipChildren = false; clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(12))
            contentDescription = title
        }
        val frame = FrameLayout(activity).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12).toFloat()) } }
            setBackgroundColor(0xFF3A2C1E.toInt())
        }
        val initials = TextView(activity).apply {
            text = title.filter { it.isLetterOrDigit() || it == ' ' }.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
            gravity = Gravity.CENTER; textSize = 30f; setTextColor(0x99F3E9CF.toInt())
        }
        frame.addView(initials, FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        tile.addView(frame, LinearLayout.LayoutParams(-1, dp(128)))
        tile.addView(Type.text(activity, title, Type.Style.BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(R.dimen.space_2) })
        tile.addView(Type.text(activity, subtitle, Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        LauncherTheme.bindPanelFocus(tile, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius)) { lit -> scrollFocusIntoView(tile, lit) }
        tile.setOnClickListener { onSelect() }
        if (imageUrl != null) fetch(imageUrl, headers) { bmp -> image.setImageBitmap(bmp); initials.visibility = View.GONE }
        return tile
    }

    /** A text-only card for an action, such as signing in. */
    private fun actionCard(title: String, subtitle: String, onSelect: () -> Unit): View {
        val radius = dp(20).toFloat()
        val tile = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(16))
        }
        tile.addView(Type.text(activity, title, Type.Style.BODY))
        tile.addView(Type.text(activity, subtitle, Type.Style.CAPTION))
        LauncherTheme.bindPanelFocus(tile, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius)) { lit -> scrollFocusIntoView(tile, lit) }
        tile.setOnClickListener { onSelect() }
        tile.layoutParams = LinearLayout.LayoutParams(dp(300), dp(96))
        return tile
    }

    private fun fetch(url: String, headers: Map<String, String>, done: (Bitmap) -> Unit) {
        bitmaps.get(url)?.let { done(it); return }
        images.execute {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                try { if (c.responseCode == 200) c.inputStream.use { BitmapFactory.decodeStream(it) } else null } finally { c.disconnect() }
            } catch (_: Exception) { null }
            if (bmp != null) { bitmaps.put(url, bmp); activity.runOnUiThread { if (overlay.isAttachedToWindow) done(bmp) } }
        }
    }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun px(res: Int) = activity.resources.getDimensionPixelSize(res)

    companion object {
        private const val MAX_FREE = 60

        /** Live-TV apps worth a tile if they are installed, matched on the name the TV shows. */
        val LIVE_APP_HINTS = listOf("Pluto", "Freely", "iPlayer", "ITVX", "Channel 4", "My5", "Channel 5", "Samsung TV Plus", "Rakuten", "Tubi", "Sky", "YouTube TV", "Plex")

        private val FAMOUS = listOf("BBC One", "BBC Two", "ITV", "Channel 4", "Channel 5", "BBC Three", "BBC Four", "BBC News", "Sky News", "GB News", "CBBC", "CBeebies", "BBC Parliament", "Al Jazeera", "Euronews", "France 24", "DW")

        /** The channels people look for first, in the order they expect them; the rest follow as listed. */
        fun priorityOrder(all: List<IptvChannel>): List<IptvChannel> {
            fun rank(c: IptvChannel): Int = FAMOUS.indexOfFirst { c.name.startsWith(it, ignoreCase = true) }.let { if (it < 0) Int.MAX_VALUE else it }
            return all.sortedBy { rank(it) }   // stable, so unranked channels keep the list's own order
        }
    }
}
