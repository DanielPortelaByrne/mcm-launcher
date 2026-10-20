package com.example.tvlauncher.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.tvlauncher.R
import com.example.tvlauncher.data.AppEntry
import com.example.tvlauncher.data.jellyfin.JellyfinClient
import com.example.tvlauncher.data.jellyfin.Programme
import com.example.tvlauncher.data.live.IptvChannel
import com.example.tvlauncher.data.live.IptvRepository
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.guide.GuideMath
import com.example.tvlauncher.ui.room.JellyfinConnectSheet
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Live TV as a programme guide: channels down the left, a half-hourly time ruler along the top and each
 * programme a block as wide as it is long. The owner's Jellyfin channels carry a real schedule; the free UK
 * channels have none, so each is one "live now" block. What is focused is described in the panel above the
 * guide, and OK on any block or channel tunes in. The TV tuner and live apps sit in a strip beside the title.
 */
class LiveTvPanel(
    private val activity: Activity,
    private val overlay: View,
    private val top: LinearLayout,
    private val content: LinearLayout,
    private val jellyfin: JellyfinClient,
    private val connectSheet: JellyfinConnectSheet,
    private val openTuner: () -> Unit,
    private val onLaunchApp: (AppEntry) -> Unit
) {
    /** A channel as the guide draws it: its cell on the left and the blocks along its row. */
    private class Row(
        val number: String?, val name: String, val logoUrl: String?, val headers: Map<String, String>,
        val programmes: List<Programme>, val liveNote: String?, val play: () -> Unit
    )

    private val worker = Executors.newFixedThreadPool(3)
    private val images = Executors.newFixedThreadPool(2)
    private val iptv = IptvRepository(activity)
    private val bitmaps = android.util.LruCache<String, Bitmap>(80)
    private val clock = SimpleDateFormat("HH:mm", Locale.UK)
    private var generation = 0   // each build owns what it draws; a late answer for an older one is dropped
    private var apps: List<AppEntry> = emptyList()

    // The panel above the guide, filled as focus moves.
    private lateinit var detailEyebrow: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailMeta: TextView
    private lateinit var detailBody: TextView

    val isVisible: Boolean get() = overlay.visibility == View.VISIBLE

    fun show(installed: List<AppEntry>) {
        apps = installed
        overlay.visibility = View.VISIBLE
        com.example.tvlauncher.design.Motion.enter(overlay, null, 0f)
        build()
    }

    fun hide() { overlay.visibility = View.GONE; generation++ }
    fun close() { worker.shutdownNow(); images.shutdownNow() }

    // --- Page ------------------------------------------------------------------------------------

    private fun build() {
        val gen = ++generation
        top.removeAllViews(); content.removeAllViews()
        buildTop()
        content.addView(note("Loading the guide…"))
        worker.execute {
            val now = System.currentTimeMillis()
            val from = GuideMath.windowStart(now)
            val to = from + WINDOW_MIN * 60_000L
            val mine = ArrayList<Row>()
            var offline = false
            if (jellyfin.isConnected) {
                val channels = jellyfin.liveChannels()
                offline = channels == null
                val guide = if (channels.isNullOrEmpty()) emptyMap() else jellyfin.guide(from, to).groupBy { it.channelId }
                channels.orEmpty().forEach { ch ->
                    mine += Row(ch.number, ch.name, if (ch.hasImage) jellyfin.imageUrl(ch.id) else null, jellyfin.playbackHeaders(),
                        guide[ch.id].orEmpty(), null) { playMine(ch.id, ch.name) }
                }
            }
            val free = priorityOrder(iptv.channels()).take(MAX_FREE).map { c ->
                Row(null, c.name, c.logo, emptyMap(), emptyList(), c.group ?: "Live") { PlayerActivity.launchLive(activity, c.name, c.url) }
            }
            activity.runOnUiThread { if (gen == generation) draw(mine, offline, free, from, to, now) }
        }
    }

    /** Title, the tuner and live-app strip, and the panel that describes whatever is focused. */
    private fun buildTop() {
        val bar = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        bar.addView(Type.text(activity, "Live TV", Type.Style.TITLE))
        val chips = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        val scroller = CalmHorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(px(R.dimen.edge_fade))
            addView(chips)
        }
        bar.addView(scroller, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(R.dimen.space_4) })
        top.addView(bar, LinearLayout.LayoutParams(-1, -2))

        val tuner = apps.find { it.packageName == "com.tcl.tv" } ?: apps.find { it.packageName == "com.android.tv" }
        if (tuner != null) chips.addView(chip("TV tuner", tuner.icon) { openTuner() }, chipParams(chips))
        apps.filter { it !== tuner && LIVE_APP_HINTS.any { h -> it.label.contains(h, ignoreCase = true) } }
            .sortedBy { it.label.lowercase() }
            .forEach { app -> chips.addView(chip(app.label, app.icon) { onLaunchApp(app) }, chipParams(chips)) }

        val detail = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; minimumHeight = dp(98); setPadding(0, dp(4), 0, dp(6)) }
        detailEyebrow = Type.text(activity, "", Type.Style.EYEBROW)
        detailTitle = Type.text(activity, "", Type.Style.HEADING).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        detailMeta = Type.text(activity, "", Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        detailBody = Type.text(activity, "", Type.Style.CAPTION).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        detail.addView(detailEyebrow)
        detail.addView(detailTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        detail.addView(detailMeta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        detail.addView(detailBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        top.addView(detail, LinearLayout.LayoutParams(-1, -2))
        describe("", "Pick a channel", "Move around the guide; press OK to watch live.", "")
    }

    private fun describe(eyebrow: String, title: String, meta: String, body: String) {
        detailEyebrow.text = eyebrow; detailTitle.text = title; detailMeta.text = meta; detailBody.text = body
    }

    // --- The guide -------------------------------------------------------------------------------

    private fun draw(mine: List<Row>, offline: Boolean, free: List<Row>, from: Long, to: Long, now: Long) {
        content.removeAllViews()
        if (!jellyfin.isConnected) {
            content.addView(actionCard("Connect Jellyfin", "Sign in to put your own channels in the guide") {
                connectSheet.show(jellyfin) { if (isVisible) build() }
            }, LinearLayout.LayoutParams(dp(380), dp(64)).apply { bottomMargin = dp(8) })
        }

        val totalPx = dpf(WINDOW_MIN * DP_PER_MIN)
        val left = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val rowsColumn = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }

        // The ruler, and an empty corner of the same height above the channel names.
        left.addView(View(activity), LinearLayout.LayoutParams(dp(CHANNEL_W), dp(RULER_H)))
        rowsColumn.addView(ruler(from, to), LinearLayout.LayoutParams(totalPx, dp(RULER_H)))

        var first: View? = null
        fun section(label: String, empty: String? = null) {
            left.addView(Type.text(activity, label, Type.Style.EYEBROW).apply { gravity = Gravity.BOTTOM; setPadding(dp(4), 0, 0, dp(4)) }, LinearLayout.LayoutParams(dp(CHANNEL_W), dp(SECTION_H)))
            rowsColumn.addView(View(activity), LinearLayout.LayoutParams(totalPx, dp(SECTION_H)))
            if (empty != null) {
                left.addView(note(empty), LinearLayout.LayoutParams(dp(CHANNEL_W + 320), dp(ROW_H)))
                rowsColumn.addView(View(activity), LinearLayout.LayoutParams(totalPx, dp(ROW_H)))
            }
        }
        fun addRows(rows: List<Row>) = rows.forEach { row ->
            left.addView(channelCell(row, now), LinearLayout.LayoutParams(dp(CHANNEL_W), dp(ROW_H)))
            val line = FrameLayout(activity).apply { clipChildren = false; clipToPadding = false }
            val blocks = blocksFor(row, from, to, now)
            blocks.forEach { (view, span) ->
                line.addView(view, FrameLayout.LayoutParams((span.widthDp - dp(GAP)).coerceAtLeast(dp(4)), dp(ROW_H) - dp(GAP)).apply { leftMargin = span.offsetDp + dp(GAP) / 2; topMargin = dp(GAP) / 2 })
            }
            if (first == null) first = blocks.firstOrNull { it.first.tag == ON_NOW }?.first ?: blocks.firstOrNull()?.first
            rowsColumn.addView(line, LinearLayout.LayoutParams(totalPx, dp(ROW_H)))
        }

        if (mine.isNotEmpty()) { section("Your channels"); addRows(mine) }
        else if (offline) section("Your channels", "Couldn't reach your Jellyfin just now. Close and reopen Live TV to try again.")
        else if (jellyfin.isConnected) section("Your channels", "No live channels on your server yet")
        if (free.isNotEmpty()) { section("Free channels"); addRows(free) }
        else section("Free channels", "Couldn't load the channel list right now")

        val frame = FrameLayout(activity).apply { clipChildren = false; clipToPadding = false }
        frame.addView(rowsColumn, FrameLayout.LayoutParams(totalPx, -2))

        val scroller = CalmHorizontalScrollView(activity).apply {
            // The timeline must not paint over the channel column when it is scrolled, so this one clips.
            isHorizontalScrollBarEnabled = false; clipChildren = true; clipToPadding = true
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(px(R.dimen.edge_fade))
            addView(frame)
        }
        val body = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        body.addView(left, LinearLayout.LayoutParams(dp(CHANNEL_W), -2))
        body.addView(scroller, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(body, LinearLayout.LayoutParams(-1, -2))

        first?.let { requestFocusRobust(overlay as ViewGroup, it) }
    }

    /** The half-hour marks along the top. */
    private fun ruler(from: Long, to: Long): View {
        val bar = FrameLayout(activity).apply { clipChildren = false }
        val slot = dpf(GuideMath.SLOT_MIN * DP_PER_MIN)
        GuideMath.ticks(from, to).forEachIndexed { i, t ->
            val label = Type.text(activity, clock.format(Date(t)), Type.Style.CAPTION).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, 0, 0) }
            bar.addView(label, FrameLayout.LayoutParams(slot, dp(RULER_H)).apply { leftMargin = i * slot })
        }
        return bar
    }

    /** The channel's cell on the left: number, logo, name. OK tunes in. */
    private fun channelCell(row: Row, now: Long): View {
        val radius = dp(10).toFloat()
        val cell = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(dp(8), 0, dp(8), 0); contentDescription = row.name
        }
        if (row.number != null) cell.addView(Type.text(activity, row.number, Type.Style.CAPTION).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(22), -2))
        val logo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(3), dp(3), dp(3), dp(3)) }
        val initials = TextView(activity).apply {
            text = row.name.filter { it.isLetterOrDigit() || it == ' ' }.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
            gravity = Gravity.CENTER; textSize = 13f; setTextColor(0x99F3E9CF.toInt())
        }
        val logoBox = FrameLayout(activity).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
            setBackgroundColor(0xFF3A2C1E.toInt())
            addView(initials, FrameLayout.LayoutParams(-1, -1)); addView(logo, FrameLayout.LayoutParams(-1, -1))
        }
        cell.addView(logoBox, LinearLayout.LayoutParams(dp(46), dp(34)).apply { marginStart = dp(4) })
        cell.addView(Type.text(activity, row.name, Type.Style.BODY).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; textSize = 13f }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        LauncherTheme.bindPanelFocus(cell, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit ->
            scrollFocusIntoView(cell, lit)
            if (lit) describeRow(row, row.programmes.firstOrNull { GuideMath.isOnNow(it.startMs, it.endMs, now) }, now)
        }
        cell.setOnClickListener { row.play() }
        row.logoUrl?.let { url -> fetch(url, row.headers) { b -> logo.setImageBitmap(b); initials.visibility = View.GONE } }
        return cell
    }

    /** A programme block's place on its row, in pixels. */
    private class Place(val offsetDp: Int, val widthDp: Int, val cutOnLeft: Boolean)

    /** The blocks along a channel's row, each with where it sits. */
    private fun blocksFor(row: Row, from: Long, to: Long, now: Long): List<Pair<View, Place>> {
        if (row.programmes.isEmpty()) {
            val place = Place(0, dpf(LIVE_BLOCK_MIN * DP_PER_MIN), false)   // narrower than the screen, so focusing it never has to scroll sideways
            val live = row.liveNote != null
            return listOf(block(row, null, if (live) "Live now" else "No guide for this channel", row.liveNote ?: "Press OK to watch live", place, now, 0f, live) to place)
        }
        return row.programmes.mapNotNull { p ->
            val s = GuideMath.span(p.startMs, p.endMs, from, to, DP_PER_MIN) ?: return@mapNotNull null
            val place = Place(dpf(s.offsetDp.toFloat()), dpf(s.widthDp.toFloat()), s.cutOnLeft)
            val onNow = GuideMath.isOnNow(p.startMs, p.endMs, now)
            block(row, p, p.title, "${clock.format(Date(p.startMs))} – ${clock.format(Date(p.endMs))}", place, now, if (onNow) GuideMath.progress(p.startMs, p.endMs, now) else 0f, onNow) to place
        }
    }

    private fun block(row: Row, p: Programme?, title: String, sub: String, place: Place, now: Long, progress: Float, onNow: Boolean): View {
        val radius = dp(10).toFloat()
        val wide = place.widthDp > dp(110)
        val tile = FrameLayout(activity).apply {
            isFocusable = true; isClickable = true; contentDescription = title
            if (onNow) tag = ON_NOW
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) } }
        }
        val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(10), dp(4), dp(8), dp(6)) }
        text.addView(Type.text(activity, if (place.cutOnLeft) "‹ $title" else title, Type.Style.BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 14f })
        if (wide) text.addView(Type.text(activity, sub, Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 12f })
        tile.addView(text, FrameLayout.LayoutParams(-1, -1))
        if (onNow && progress > 0f) tile.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.terracotta)) },
            FrameLayout.LayoutParams(Math.max(dp(2), (place.widthDp * progress).toInt()), dp(3), Gravity.BOTTOM or Gravity.START))
        // What is on now is a shade lighter than the rest, so the present reads at a glance down the column.
        val rest = if (onNow) LauncherTheme.surface(activity, R.color.surface_selected, R.dimen.radius_small) else LauncherTheme.tileBackground(activity, radius)
        LauncherTheme.bindPanelFocus(tile, rest, LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit ->
            // Reveal only the start of the block: a long film must not drag the view to its far end.
            if (lit) tile.post { tile.requestRectangleOnScreen(android.graphics.Rect(0, 0, minOf(tile.width, dp(150)), tile.height), false) }
            if (lit) describeRow(row, p, now)
        }
        tile.setOnClickListener { row.play() }
        return tile
    }

    /** Fills the panel above the guide for the focused channel or programme. */
    private fun describeRow(row: Row, p: Programme?, now: Long) {
        if (p == null) {
            describe(if (row.liveNote != null) "LIVE NOW  ·  ${row.name.uppercase(Locale.UK)}" else row.name.uppercase(Locale.UK),
                row.name, row.liveNote ?: "No programme information", "Press OK to watch live.")
            return
        }
        val mins = ((p.endMs - p.startMs) / 60_000L).toInt()
        val whenText = when { GuideMath.isOnNow(p.startMs, p.endMs, now) -> "ON NOW"; p.startMs > now -> "LATER"; else -> "EARLIER" }
        val meta = listOfNotNull("${clock.format(Date(p.startMs))} – ${clock.format(Date(p.endMs))}", "$mins min", p.year?.toString()).joinToString("  ·  ")
        describe("$whenText  ·  ${row.name.uppercase(Locale.UK)}", p.title, meta, p.overview ?: "Press OK to watch ${row.name} live.")
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

    // --- Pieces ----------------------------------------------------------------------------------

    private fun chip(label: String, icon: Drawable, onSelect: () -> Unit): View {
        val radius = dp(18).toFloat()
        val chip = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; setPadding(dp(6), 0, dp(14), 0); contentDescription = label }
        chip.addView(ImageView(activity).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        chip.addView(Type.text(activity, label, Type.Style.BODY).apply { maxLines = 1; textSize = 13f }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        LauncherTheme.bindPanelFocus(chip, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit -> scrollFocusIntoView(chip, lit) }
        chip.setOnClickListener { onSelect() }
        return chip
    }

    private fun chipParams(parent: LinearLayout) = LinearLayout.LayoutParams(-2, dp(36)).apply { if (parent.childCount > 0) marginStart = dp(8) }

    private fun actionCard(title: String, subtitle: String, onSelect: () -> Unit): View {
        val radius = dp(14).toFloat()
        val tile = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        tile.addView(Type.text(activity, title, Type.Style.BODY))
        tile.addView(Type.text(activity, subtitle, Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        LauncherTheme.bindPanelFocus(tile, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit -> scrollFocusIntoView(tile, lit) }
        tile.setOnClickListener { onSelect() }
        return tile
    }

    private fun note(text: String) = Type.text(activity, text, Type.Style.CAPTION).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(R.dimen.space_1), px(R.dimen.space_2), 0, px(R.dimen.space_2)) }

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

    private fun density() = activity.resources.displayMetrics.density
    private fun dp(v: Int) = Math.round(v * density())
    private fun dpf(v: Float) = Math.round(v * density())
    private fun px(res: Int) = activity.resources.getDimensionPixelSize(res)

    companion object {
        private const val WINDOW_MIN = 360            // the guide shows six hours
        private const val DP_PER_MIN = 10f            // so half an hour is 300dp
        private const val CHANNEL_W = 190
        private const val ROW_H = 54
        private const val GAP = 6
        private const val RULER_H = 28
        private const val SECTION_H = 30
        private const val MAX_FREE = 40
        private const val LIVE_BLOCK_MIN = 75
        private const val ON_NOW = "on_now"

        /** Live-TV apps worth a chip if they are installed, matched on the name the TV shows. */
        val LIVE_APP_HINTS = listOf("Pluto", "Freely", "iPlayer", "ITVX", "Channel 4", "My5", "Channel 5", "Samsung TV Plus", "Rakuten", "Tubi", "Sky", "YouTube TV", "Plex")

        private val FAMOUS = listOf("BBC One", "BBC Two", "ITV", "Channel 4", "Channel 5", "BBC Three", "BBC Four", "BBC News", "Sky News", "GB News", "CBBC", "CBeebies", "BBC Parliament", "Al Jazeera", "Euronews", "France 24", "DW")

        /** The channels people look for first, in the order they expect them; the rest follow as listed. */
        fun priorityOrder(all: List<IptvChannel>): List<IptvChannel> {
            fun rank(c: IptvChannel): Int = FAMOUS.indexOfFirst { c.name.startsWith(it, ignoreCase = true) }.let { if (it < 0) Int.MAX_VALUE else it }
            return all.sortedBy { rank(it) }   // stable, so unranked channels keep the list's own order
        }
    }
}
