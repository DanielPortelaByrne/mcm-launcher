package com.example.tvlauncher.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
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
import com.example.tvlauncher.data.jellyfin.JellyfinApi
import com.example.tvlauncher.data.jellyfin.JellyfinClient
import com.example.tvlauncher.data.jellyfin.JellyfinItem
import com.example.tvlauncher.data.movies.Availability
import com.example.tvlauncher.data.movies.Catalog
import com.example.tvlauncher.data.movies.CatalogRepository
import com.example.tvlauncher.data.movies.Kind
import com.example.tvlauncher.data.movies.RequestOutcome
import com.example.tvlauncher.data.movies.SeerrClient
import com.example.tvlauncher.data.movies.Title
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.SectionHeader
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.room.JellyfinConnectSheet
import com.example.tvlauncher.ui.room.SeerrSignInSheet
import java.util.concurrent.Executors

/**
 * The Movies page, laid out like a streaming service: a featured title, then rows of posters. The first row is the
 * owner's own Jellyfin library; the rest are what is trending and popular across the big UK services. Choosing a
 * poster opens its page, where a title that is in the library can be watched at once and one that is not can be
 * queued for download through Seerr.
 */
class MoviesPanel(
    private val activity: Activity,
    private val overlay: View,
    private val page: ViewGroup,
    private val content: LinearLayout,
    private val detail: FrameLayout,
    private val jellyfin: JellyfinClient,
    private val jellyfinSheet: JellyfinConnectSheet,
    private val seerr: SeerrClient,
    private val seerrSheet: SeerrSignInSheet
) {
    /** A poster on the page: the title, and the library copy of it when there is one. */
    private class Entry(val title: Title, val library: JellyfinItem?, val posterHeaders: Map<String, String>)

    private val worker = Executors.newFixedThreadPool(3)
    private val images = RemoteImages(activity)
    private val catalog = CatalogRepository(activity)
    private var generation = 0
    private var returnFocus: View? = null
    private lateinit var heroImage: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroBody: TextView
    private lateinit var heroButton: TextView

    val isVisible: Boolean get() = overlay.visibility == View.VISIBLE
    private val detailOpen: Boolean get() = detail.visibility == View.VISIBLE

    fun show() {
        overlay.visibility = View.VISIBLE
        com.example.tvlauncher.design.Motion.enter(overlay, null, 0f)
        build()
    }

    fun hide() { closeDetail(restoreFocus = false); overlay.visibility = View.GONE; generation++ }
    fun close() { worker.shutdownNow(); images.close() }

    /** Back closes a title's page first. True when it did. */
    fun back(): Boolean { if (!detailOpen) return false; closeDetail(restoreFocus = true); return true }

    // --- Page ------------------------------------------------------------------------------------

    private fun build() {
        val gen = ++generation
        content.removeAllViews()
        buildHero()

        val library = row("Your library", "Everything on your Jellyfin")
        library.second.addView(note("Loading…"))
        val shelves = Catalog.SHELVES.map { spec -> spec to row(spec.heading, null).also { it.second.addView(note("Loading…")) } }

        worker.execute {
            val items = if (jellyfin.isConnected) jellyfin.library() else emptyList()
            activity.runOnUiThread { if (gen == generation) fillLibrary(library.second, items) }
            shelves.forEachIndexed { index, (spec, rowViews) ->
                worker.execute {
                    val shelf = catalog.shelf(spec)
                    activity.runOnUiThread {
                        if (gen != generation) return@runOnUiThread
                        val entries = shelf.titles.map { entry(it) }
                        fillShelf(rowViews.second, entries)
                        if (index == 0) entries.firstOrNull { it.title.backdropUrl != null }?.let { setHero(it, entries) }
                    }
                }
            }
        }
    }

    private fun entry(t: Title): Entry =
        Entry(t, if (jellyfin.isConnected) jellyfin.find(t.imdbId, t.tmdbId, t.kind == Kind.SHOW) else null, emptyMap())

    private fun libraryTitle(item: JellyfinItem): Title {
        val base = JellyfinApi.cleanBase(jellyfin.server)
        return Title(
            key = "jf:" + item.id, kind = if (item.isSeries) Kind.SHOW else Kind.MOVIE, name = item.name, year = item.year,
            overview = item.overview, runtimeMin = item.runtimeMin, genres = item.genres, age = item.officialRating, imdbScore = null,
            posterUrl = JellyfinApi.posterUrl(base, item.id), backdropUrl = if (item.hasBackdrop) jellyfin.backdropUrl(item.id) else null,
            imdbId = item.imdbId, tmdbId = item.tmdbId
        )
    }

    private fun fillLibrary(into: LinearLayout, items: List<JellyfinItem>) {
        into.removeAllViews()
        if (!jellyfin.isConnected) {
            into.addView(actionCard("Connect Jellyfin", "Sign in to see your own films and series") { jellyfinSheet.show(jellyfin) { if (isVisible) build() } })
            return
        }
        if (items.isEmpty()) { into.addView(note("Nothing in your library yet, or Jellyfin couldn't be reached")); return }
        val entries = items.map { Entry(libraryTitle(it), it, jellyfin.playbackHeaders()) }
        entries.forEachIndexed { i, e -> into.addView(card(e, entries.map { it.title }), cardParams(i)) }
    }

    private fun fillShelf(into: LinearLayout, entries: List<Entry>) {
        into.removeAllViews()
        if (entries.isEmpty()) { into.addView(note("Couldn't load this row right now")); return }
        val related = entries.map { it.title }
        entries.forEachIndexed { i, e -> into.addView(card(e, related), cardParams(i)) }
    }

    // --- Featured title --------------------------------------------------------------------------

    private fun buildHero() {
        val hero = FrameLayout(activity).apply { setBackgroundColor(0xFF1D140D.toInt()) }
        heroImage = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero.addView(heroImage, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2140E09.toInt(), 0xB3140E09.toInt(), 0x00140E09)) }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00140E09, 0xFF140E09.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(px(R.dimen.page_margin), 0, 0, dp(18)) }
        column.addView(Type.text(activity, "FEATURED", Type.Style.EYEBROW))
        heroTitle = Type.text(activity, "Movies", Type.Style.TITLE).apply { textSize = 34f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heroMeta = Type.text(activity, "", Type.Style.CAPTION).apply { maxLines = 1 }
        heroBody = Type.text(activity, "", Type.Style.CAPTION).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f) }
        column.addView(heroTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        column.addView(heroMeta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        column.addView(heroBody, LinearLayout.LayoutParams(dp(520), -2).apply { topMargin = dp(6) })
        heroButton = pill("More info", accent = false).apply { visibility = View.INVISIBLE }
        column.addView(heroButton, LinearLayout.LayoutParams(dp(150), dp(44)).apply { topMargin = dp(12) })
        hero.addView(column, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(300)))
    }

    private fun setHero(e: Entry, related: List<Entry>) {
        heroTitle.text = e.title.name; heroMeta.text = e.title.metaLine(); heroBody.text = e.title.overview.orEmpty()
        heroButton.visibility = View.VISIBLE
        heroButton.setOnClickListener { openDetail(e, related.map { it.title }) }
        e.title.backdropUrl?.let { u -> images.load(u, maxWidth = 1280) { b -> heroImage.setImageBitmap(b); heroImage.animate().alpha(1f).setDuration(350).start() } }
        if (overlay.findFocus() == null) requestFocusRobust(overlay as ViewGroup, heroButton)
    }

    // --- Title page ------------------------------------------------------------------------------

    private fun openDetail(e: Entry, related: List<Title>) {
        returnFocus = overlay.findFocus()
        detail.removeAllViews(); detail.visibility = View.VISIBLE
        page.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val t = e.title

        val backdrop = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        detail.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        detail.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFA140E09.toInt(), 0xD9140E09.toInt(), 0x66140E09)) }, FrameLayout.LayoutParams(-1, -1))
        detail.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00140E09, 0xF2140E09.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        (t.backdropUrl ?: e.library?.takeIf { it.hasBackdrop }?.let { jellyfin.backdropUrl(it.id) })?.let { u ->
            images.load(u, maxWidth = 1280) { b -> backdrop.setImageBitmap(b); backdrop.animate().alpha(1f).setDuration(300).start() }
        }

        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(px(R.dimen.page_margin), dp(34), px(R.dimen.page_margin), dp(8)) }
        val top = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        text.addView(Type.text(activity, (if (t.kind == Kind.SHOW) "SERIES" else "FILM") + if (e.library != null) "  ·  IN YOUR LIBRARY" else "", Type.Style.EYEBROW))
        text.addView(Type.text(activity, t.name, Type.Style.TITLE).apply { textSize = 34f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        text.addView(Type.text(activity, listOfNotNull(t.metaLine().ifBlank { null }, t.imdbScore?.let { "IMDb ${"%.1f".format(java.util.Locale.UK, it)}" }).joinToString("  ·  "), Type.Style.CAPTION).apply { maxLines = 1 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        text.addView(Type.text(activity, t.overview ?: e.library?.overview ?: "No description yet.", Type.Style.BODY).apply { maxLines = 5; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(dp(520), -2).apply { topMargin = dp(10) })

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val primary = pill("Checking…", accent = true)
        val backButton = pill("Back", accent = false).apply { setOnClickListener { closeDetail(restoreFocus = true) } }
        buttons.addView(primary, LinearLayout.LayoutParams(dp(230), dp(52)))
        buttons.addView(backButton, LinearLayout.LayoutParams(dp(120), dp(52)).apply { marginStart = dp(10) })
        text.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        top.addView(text, LinearLayout.LayoutParams(0, -2, 1f))

        val posterBox = FrameLayout(activity).apply {
            clipToOutline = true; setBackgroundColor(0xFF3A2C1E.toInt())
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) } }
        }
        val posterView = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        posterBox.addView(posterView, FrameLayout.LayoutParams(-1, -1))
        t.posterUrl?.let { u -> images.load(u, e.posterHeaders) { b -> posterView.setImageBitmap(b) } }
        top.addView(posterBox, LinearLayout.LayoutParams(dp(150), dp(225)).apply { marginStart = dp(24) })
        column.addView(top, LinearLayout.LayoutParams(-1, -2))

        val more = related.filter { it.key != t.key }.take(14)
        if (more.isNotEmpty()) {
            column.addView(SectionHeader.build(activity, "More like this", null).apply { setPadding(0, dp(14), 0, dp(4)) })
            val rowViews = row(null, null, into = column, inset = false)
            val moreEntries = more.map { entry(it) }
            moreEntries.forEachIndexed { i, m -> rowViews.second.addView(card(m, more, small = true, nested = false), cardParams(i, small = true)) }
        }
        detail.addView(column, FrameLayout.LayoutParams(-1, -1))
        setupPrimary(primary, e)
        requestFocusRobust(detail, primary)
    }

    private fun closeDetail(restoreFocus: Boolean) {
        if (!detailOpen) return
        detail.visibility = View.GONE; detail.removeAllViews()
        page.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (restoreFocus) returnFocus?.takeIf { it.isAttachedToWindow }?.let { f -> f.post { f.requestFocus() } }
    }

    /** Watch now for what is in the library; for the rest, queue it, or say where its request has got to. */
    private fun setupPrimary(button: TextView, e: Entry) {
        fun set(label: String, enabled: Boolean, action: (() -> Unit)?) {
            button.text = label; button.isEnabled = enabled; button.alpha = if (enabled) 1f else 0.6f
            button.setOnClickListener { action?.invoke() }
        }
        val lib = e.library
        if (lib != null) { set("Watch now", true) { watch(lib, e.title) }; return }
        val tmdb = e.title.tmdbId?.toLongOrNull()
        if (tmdb == null) { set("Can't be requested", false, null); return }
        fun queue() {
            if (!seerr.isSignedIn) { seerrSheet.show(seerr, jellyfin) { queue() }; return }
            set("Queuing…", false, null)
            worker.execute {
                val outcome = seerr.request(e.title.kind, tmdb)
                activity.runOnUiThread {
                    when (outcome) {
                        RequestOutcome.QUEUED, RequestOutcome.ALREADY -> { set("Requested ✓", false, null); Toast.makeText(activity, "${e.title.name} is queued for download", Toast.LENGTH_LONG).show() }
                        RequestOutcome.NEEDS_SIGN_IN -> { set("Queue download", true) { queue() }; seerrSheet.show(seerr, jellyfin) { queue() } }
                        RequestOutcome.FAILED -> { set("Queue download", true) { queue() }; Toast.makeText(activity, "Couldn't queue it right now", Toast.LENGTH_LONG).show() }
                    }
                }
            }
        }
        if (!seerr.isSignedIn) { set("Queue download", true) { queue() }; return }
        set("Checking…", false, null)
        worker.execute {
            val availability = seerr.availability(e.title.kind, tmdb)
            activity.runOnUiThread {
                if (!detailOpen) return@runOnUiThread
                when (availability) {
                    Availability.REQUESTED -> set("Requested ✓", false, null)
                    Availability.DOWNLOADING -> set("Downloading…", false, null)
                    Availability.PARTIAL -> set("Partly downloaded", false, null)
                    Availability.AVAILABLE -> set("Downloaded, syncing", false, null)
                    Availability.NONE -> set("Queue download", true) { queue() }
                }
            }
        }
    }

    private fun watch(item: JellyfinItem, title: Title) {
        if (!item.isSeries) { JellyfinPlayback.play(activity, jellyfin, item, title.name, title.imdbId ?: "jf:${item.id}"); return }
        Toast.makeText(activity, "Finding the next episode…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val ep = jellyfin.episodeToPlay(item.id)
            activity.runOnUiThread {
                if (ep == null) Toast.makeText(activity, "No episodes found for ${title.name}", Toast.LENGTH_LONG).show()
                else JellyfinPlayback.play(activity, jellyfin, ep, listOfNotNull(title.name, ep.episodeLabel()).joinToString(" · "), "jf:${ep.id}")
            }
        }
    }

    // --- Pieces ----------------------------------------------------------------------------------

    /** A heading and an empty scrolling row beneath it: (heading view or null, the row to fill). */
    private fun row(heading: String?, caption: String?, into: LinearLayout = content, inset: Boolean = true): Pair<View?, LinearLayout> {
        val margin = if (inset) px(R.dimen.page_margin) else 0
        val head = heading?.let { SectionHeader.build(activity, it, caption).apply { setPadding(margin, dp(14), margin, dp(2)) } }
        head?.let { into.addView(it) }
        val inner = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(6), 0, dp(6)) }
        val scroller = CalmHorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(margin, 0, margin, 0)
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(px(R.dimen.edge_fade))
            addView(inner)
        }
        into.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        return head to inner
    }

    private fun cardParams(index: Int, small: Boolean = false) = LinearLayout.LayoutParams(dp(if (small) 108 else 138), -2).apply { if (index > 0) marginStart = dp(10) }

    /** A poster with its name beneath. Lights ivory on focus; a small badge says when it is already in the library. */
    private fun card(e: Entry, related: List<Title>, small: Boolean = false, nested: Boolean = true): View {
        val radius = dp(12).toFloat()
        val w = if (small) 100 else 130
        val h = if (small) 150 else 195
        val tile = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(4), dp(6)); contentDescription = e.title.name }
        val frame = FrameLayout(activity).apply {
            clipToOutline = true; setBackgroundColor(0xFF3A2C1E.toInt())
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } }
        }
        frame.addView(TextView(activity).apply {
            text = e.title.name.filter { it.isLetterOrDigit() || it == ' ' }.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
            gravity = Gravity.CENTER; textSize = 26f; setTextColor(0x99F3E9CF.toInt())
        }, FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        if (e.library != null) frame.addView(TextView(activity).apply {
            text = "IN LIBRARY"; textSize = 9f; letterSpacing = 0.08f; setTextColor(0xFFF3E9CF.toInt()); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setPadding(dp(6), dp(2), dp(6), dp(2)); background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(activity.getColor(R.color.terracotta)) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
        tile.addView(frame, LinearLayout.LayoutParams(dp(w), dp(h)))
        tile.addView(Type.text(activity, e.title.name, Type.Style.BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 12f }, LinearLayout.LayoutParams(dp(w), -2).apply { topMargin = dp(5) })
        val rest = GradientDrawable().apply { cornerRadius = radius; setColor(0) }
        LauncherTheme.bindPanelFocus(tile, rest, LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit -> scrollFocusIntoView(tile, lit) }
        tile.setOnClickListener {
            if (nested) openDetail(e, related) else { closeDetail(restoreFocus = false); openDetail(e, related) }
        }
        e.title.posterUrl?.let { u -> images.load(u, e.posterHeaders, maxWidth = 332) { b: Bitmap -> image.setImageBitmap(b) } }
        return tile
    }

    private fun actionCard(title: String, subtitle: String, onSelect: () -> Unit): View {
        val radius = dp(14).toFloat()
        val tile = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10)) }
        tile.addView(Type.text(activity, title, Type.Style.BODY))
        tile.addView(Type.text(activity, subtitle, Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        LauncherTheme.bindPanelFocus(tile, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit -> scrollFocusIntoView(tile, lit) }
        tile.setOnClickListener { onSelect() }
        tile.layoutParams = LinearLayout.LayoutParams(dp(360), dp(64))
        return tile
    }

    private fun pill(label: String, accent: Boolean): TextView {
        val rest = LauncherTheme.surface(activity, if (accent) R.color.action else R.color.surface_selected, R.dimen.radius_pill)
        val lit = LauncherTheme.surface(activity, R.color.focus, R.dimen.radius_pill)
        return Type.text(activity, label, Type.Style.BODY).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true; maxLines = 1
            background = android.graphics.drawable.TransitionDrawable(arrayOf(rest, lit)).apply { isCrossFadeEnabled = true }
            setOnFocusChangeListener { v, focused ->
                val d = v.background as android.graphics.drawable.TransitionDrawable
                if (focused) d.startTransition(120) else d.reverseTransition(120)
                (v as TextView).setTextColor(activity.getColor(if (focused) R.color.ink else R.color.ivory))
            }
        }
    }

    private fun note(text: String) = Type.text(activity, text, Type.Style.CAPTION).apply { setPadding(dp(4), dp(10), 0, dp(10)) }

    private fun dp(v: Int) = Math.round(v * activity.resources.displayMetrics.density)
    private fun px(res: Int) = activity.resources.getDimensionPixelSize(res)
}
