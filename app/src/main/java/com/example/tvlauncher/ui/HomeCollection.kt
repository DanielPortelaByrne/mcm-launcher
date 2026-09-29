package com.example.tvlauncher.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*
import com.example.tvlauncher.R
import com.example.tvlauncher.design.IconStyle
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.data.Film
import com.example.tvlauncher.data.FilmDetails
import com.example.tvlauncher.data.FilmInfo
import com.example.tvlauncher.data.Watchlist

class HomeCollection(private val activity: Activity, private val sheet: com.example.tvlauncher.ui.room.InfoSheet, private val art: ArtModeOverlay, private val onArt: () -> Unit, private val onSettings: () -> Unit, private val onEdit: () -> Unit, private val onIconStyle: () -> Unit) {
    private val watchlist = Watchlist(activity)
    private var film: Film? = null
    private val title = com.example.tvlauncher.design.Type.text(activity, "Finding tonight's film…", com.example.tvlauncher.design.Type.Style.HEADING)
    private val status = com.example.tvlauncher.design.Type.text(activity, "Syncing Eva's watchlist", com.example.tvlauncher.design.Type.Style.CAPTION)
    private val card = LinearLayout(activity)
    private val info = FilmInfo(activity)
    private val availability = com.example.tvlauncher.design.Type.text(activity, "", com.example.tvlauncher.design.Type.Style.CAPTION)
    private val badge = LetterboxdBadge(activity)
    private val poster = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) }
        }
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x33F3E9CF) }
    }
    private var shownPath: String? = null
    /** The next picks, already being preloaded (details + poster), so a new film appears instantly. */
    private val upcoming = ArrayDeque<Film>()

    private fun nextFilm(): Film? {
        val film = upcoming.removeFirstOrNull() ?: watchlist.pick()
        while (upcoming.size < 2) {
            val next = watchlist.pick() ?: break
            if (next.path == film?.path || upcoming.any { it.path == next.path }) break
            upcoming.addLast(next); info.prefetch(next)
        }
        return film
    }
    private var details: FilmDetails? = null
    private var posterBitmap: android.graphics.Bitmap? = null

    // Smart PLAY: the best stream is worked out when the film sheet opens, then cached for a while.
    private val smart = com.example.tvlauncher.data.stream.SmartStreamRepository(activity)
    private val smartWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var smartResult: com.example.tvlauncher.data.stream.SmartResult? = null
    private var smartFor: String? = null
    private var smartAt = 0L
    private var smartLoading = false
    private val filmSheet = com.example.tvlauncher.ui.room.FilmSheet(activity, sheet)
    private val thumbDecoder = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val connectSheet = com.example.tvlauncher.ui.room.StremioConnectSheet(activity, sheet)
    private var disposed = false

    init {
        // Eva's film card: a fixed size so it never jumps between films, poster left, one eyebrow,
        // a two-line serif title and two quiet captions. OK opens the film sheet; no instruction line.
        card.orientation = LinearLayout.HORIZONTAL
        val inset = px(R.dimen.space_3)
        card.setPadding(inset, inset, px(R.dimen.space_4), inset)
        card.addView(poster, LinearLayout.LayoutParams(dp(101), dp(152)).apply { marginEnd = px(R.dimen.space_4); gravity = Gravity.CENTER_VERTICAL })
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        card.addView(column, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        // Letterboxd's dots and the rating sit in the top-right corner of the card.
        column.addView(badge.view, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; bottomMargin = px(R.dimen.space_1) })
        column.addView(com.example.tvlauncher.design.Type.text(activity, "Eva's pick for tonight", com.example.tvlauncher.design.Type.Style.EYEBROW))
        com.example.tvlauncher.design.Type.apply(title, com.example.tvlauncher.design.Type.Style.HEADING)
        title.maxLines = 2; title.ellipsize = android.text.TextUtils.TruncateAt.END
        column.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(R.dimen.space_2) })
        com.example.tvlauncher.design.Type.apply(availability, com.example.tvlauncher.design.Type.Style.CAPTION)
        availability.maxLines = 1; availability.ellipsize = android.text.TextUtils.TruncateAt.END
        column.addView(availability, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(R.dimen.space_2) })
        // Only shown while the watchlist is syncing or failing; hidden once there is a film.
        com.example.tvlauncher.design.Type.apply(status, com.example.tvlauncher.design.Type.Style.CAPTION)
        status.maxLines = 1; status.ellipsize = android.text.TextUtils.TruncateAt.END
        column.addView(status)
        panel(card, 20)
        card.setOnClickListener { showFilm() }
        com.example.tvlauncher.design.SectionTheme.tag(card, com.example.tvlauncher.design.SectionTheme.Mood.FILM)
        activity.findViewById<FrameLayout>(R.id.homeHero).addView(card, FrameLayout.LayoutParams(dp(408), dp(184), Gravity.END or Gravity.CENTER_VERTICAL))

        val sections = activity.findViewById<LinearLayout>(R.id.discoverSections)
        sections.addView(com.example.tvlauncher.design.SectionHeader.build(activity, "The MCM collection", "${art.library.paintings.size} paintings for a quieter screen"))
        // The painting shelf: 16:9 thumbnails on the shared 4-column rhythm, image-card focus, name beneath.
        // Thumbnails decode off the main thread (decoding all of them inline cost seconds at every cold start).
        val margin = px(R.dimen.page_margin)
        val columnWidth = px(R.dimen.card_column)
        val radius = px(R.dimen.radius_small).toFloat()
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, px(R.dimen.space_2), 0, px(R.dimen.space_2)) }
        sections.addView(com.example.tvlauncher.ui.CalmHorizontalScrollView(activity).apply {
            com.example.tvlauncher.design.SectionTheme.tag(this, com.example.tvlauncher.design.SectionTheme.Mood.GALLERY)
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(margin, 0, margin, 0)
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(px(R.dimen.edge_fade))
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = -margin; marginEnd = -margin })
        art.library.paintings.forEachIndexed { index, painting ->
            val frame = FrameLayout(activity).apply {
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) { outline.setRoundRect(0, 0, v.width, v.height, radius) }
                }
                background = LauncherTheme.surface(activity, R.color.surface_raised, R.dimen.radius_small)
            }
            val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            frame.addView(image, FrameLayout.LayoutParams(-1, -1))
            val mat = LauncherTheme.imageMat(activity, radius).apply { alpha = 0 }
            frame.foreground = mat
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false
                contentDescription = "View ${painting.title}"
            }
            tile.addView(frame, LinearLayout.LayoutParams(columnWidth, columnWidth * 9 / 16))
            val titleText = com.example.tvlauncher.design.Type.onPainting(com.example.tvlauncher.design.Type.text(activity, painting.title, com.example.tvlauncher.design.Type.Style.BODY)).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                alpha = com.example.tvlauncher.design.Motion.CAPTION_REST_ALPHA
            }
            tile.addView(titleText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(R.dimen.space_2) })
            tile.setOnFocusChangeListener { v, focused ->
                LauncherTheme.fadeDrawable(frame, mat, focused)
                com.example.tvlauncher.design.Motion.focusImageCard(frame, image, titleText, focused)
                scrollFocusIntoView(v, focused)
            }
            tile.setOnClickListener { com.example.tvlauncher.design.Motion.press(frame, com.example.tvlauncher.design.Motion.CARD_SCALE); art.library.select(index); onArt() }
            row.addView(tile, LinearLayout.LayoutParams(columnWidth, -2).apply { if (index > 0) marginStart = px(R.dimen.card_gutter) })
            thumbDecoder.execute {
                val bitmap = try { BitmapFactory.decodeResource(activity.resources, painting.resource, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (_: Exception) { null }
                if (bitmap != null) activity.runOnUiThread { if (!disposed) image.setImageBitmap(bitmap) }
            }
        }
        sections.addView(com.example.tvlauncher.design.SectionHeader.build(activity, "Make it yours"))
        val actions=LinearLayout(activity)
        listOf<Pair<String, (TextView) -> Unit>>(
            "TV settings" to { _ -> onSettings() },
            "Organise apps" to { _ -> onEdit() },
            "Painting settings" to { _ -> artSettings() },
            IconStyle.label(activity) to { self -> IconStyle.toggle(activity); self.text = IconStyle.label(activity); onIconStyle() }
        ).forEach { (label,action) ->
            // Buttons on the shared four-column rhythm: one column each, one gutter between.
            val button = com.example.tvlauncher.design.Type.text(activity, label, com.example.tvlauncher.design.Type.Style.BODY)
            button.apply { gravity=Gravity.CENTER; panel(this, 26); setOnClickListener { action(button) } }
            actions.addView(button, LinearLayout.LayoutParams(px(R.dimen.card_column), px(R.dimen.control_height)).apply { if (actions.childCount > 0) marginStart = px(R.dimen.card_gutter) })
        }
        com.example.tvlauncher.design.SectionTheme.tag(actions, com.example.tvlauncher.design.SectionTheme.Mood.GALLERY)
        sections.addView(actions, LinearLayout.LayoutParams(-1,-2))
    }
    fun homeReturned() {
        watchlist.whenLoaded { activity.runOnUiThread { if (!disposed) { film = nextFilm(); render() } } }
        watchlist.refresh { message -> activity.runOnUiThread {
            if (!disposed) { status.text=message; if (film == null) { film=nextFilm(); render() } else status.visibility = View.GONE }
        } }
    }
    fun close() { disposed=true; watchlist.close(); info.close(); smartWorker.shutdownNow(); thumbDecoder.shutdownNow() }
    private fun render() {
        com.example.tvlauncher.design.Motion.swapText(title, film?.title ?: "Your next film awaits")
        showDetails()
        status.visibility = if (watchlist.films.isNotEmpty()) View.GONE else View.VISIBLE
    }
    /** Looks up poster + UK availability once per film shown. */
    private fun showDetails() {
        val current = film ?: return
        if (current.path == shownPath) return
        shownPath = current.path
        details = null
        posterBitmap = null
        // The old poster steps back and waits, dimmed, for the new one (or the empty frame if there is none).
        if (com.example.tvlauncher.design.Motion.animationsOn(poster) && poster.isShown && poster.drawable != null)
            poster.animate().alpha(0f).scaleX(0.97f).scaleY(0.97f).setDuration(110).setInterpolator(com.example.tvlauncher.design.Motion.SETTLE)
                .withEndAction { poster.setImageDrawable(null); settlePoster() }.start()
        else poster.setImageDrawable(null)
        badge.set(null)
        com.example.tvlauncher.design.Motion.swapText(availability, "Checking UK availability…")
        info.load(current) { details, bitmap ->
            activity.runOnUiThread {
                if (disposed || film?.path != current.path) return@runOnUiThread
                this.details = details
                com.example.tvlauncher.design.Motion.swapText(availability, details?.cardLine() ?: "Not streaming in the UK right now")
                badge.set(details?.ratingLabel())
                // No poster on Letterboxd: a printed cover in the room's colours instead of an empty frame.
                val art = bitmap ?: com.example.tvlauncher.design.PosterArt.typographic(activity, current.title)
                posterBitmap = art
                poster.animate().cancel()
                poster.setImageBitmap(art)
                settlePoster()
                // Head start: work out the best version now, so PLAY is ready by the time the sheet is opened.
                details?.imdbId?.let { imdb -> if (smartFor != imdb) startSmart(current, imdb) }
            }
        }
    }

    /** Brings the poster frame forward to rest, from wherever the last change left it. */
    private fun settlePoster() {
        if (!com.example.tvlauncher.design.Motion.animationsOn(poster) || !poster.isShown) { poster.alpha = 1f; poster.scaleX = 1f; poster.scaleY = 1f; return }
        poster.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(com.example.tvlauncher.design.Motion.SWOOSH).start()
    }

    private fun showFilm() {
        val current = film ?: return
        val imdb = details?.imdbId
        if (imdb != null && (smartFor != imdb || System.currentTimeMillis() - smartAt > 10 * 60_000L)) startSmart(current, imdb)
        val actions = com.example.tvlauncher.ui.room.FilmActions(
            play = { onPlayPressed(current) },
            stremio = { openInStremio() },
            versions = { smartResult?.let { showVersions(it, current) } },
            next = { film = nextFilm(); render() },
            letterboxd = {
                try { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://letterboxd.com${current.path}"))) }
                catch (_: Exception) { Toast.makeText(activity, "No web browser installed on this TV", Toast.LENGTH_LONG).show() }
            },
            refresh = {
                status.text = "Refreshing\u2026"
                watchlist.refresh(true) { r -> activity.runOnUiThread { if (!disposed) { upcoming.clear(); film = nextFilm(); render(); status.text = r } } }
            }
        )
        filmSheet.show(current.title, details?.ratingLabel(), posterBitmap, details?.page, whereLines(), actions, from = poster)
        applySmart()
    }

    private fun startSmart(current: Film, imdb: String) {
        smartFor = imdb; smartResult = null; smartLoading = true
        smartWorker.execute {
            val result = smart.select(imdb, current.title)
            activity.runOnUiThread {
                if (disposed || smartFor != imdb) return@runOnUiThread
                smartResult = result; smartAt = System.currentTimeMillis(); smartLoading = false
                if (sheet.isVisible && film?.path == current.path) applySmart()
            }
        }
    }

    /** Streaming availability plus, when PLAY is unavailable, the honest reason. */
    private fun whereLines(): List<String> {
        val lines = details?.availabilityLines().orEmpty().toMutableList()
        val imdb = details?.imdbId
        val result = smartResult?.takeIf { smartFor == imdb }
        if (result != null && result.best == null) smartMessage(result.status)?.let { lines += it }
        return lines
    }

    /** Brings the PLAY button and Watch tab in line with the latest smart-selection result. */
    private fun applySmart() {
        val imdb = details?.imdbId
        val result = smartResult?.takeIf { smartFor == imdb }
        val best = result?.best
        val hasVersions = result != null && result.ranked.count { it.playable && it.verdict != com.example.tvlauncher.data.stream.Verdict.INCOMPATIBLE } > 1
        val sub = when {
            imdb == null -> "Film details still loading\u2026"
            smartLoading && result == null -> "Finding best version\u2026"
            best != null -> best.candidate.meta.shortLabel()
            result != null -> when (result.status) {
                com.example.tvlauncher.data.stream.SmartStatus.NO_SOURCES -> "Connect Stremio to enable"
                com.example.tvlauncher.data.stream.SmartStatus.NO_STREAMS -> "No streams found"
                com.example.tvlauncher.data.stream.SmartStatus.NOTHING_PLAYABLE -> "Only opens in Stremio"
                com.example.tvlauncher.data.stream.SmartStatus.OFFLINE -> "No internet"
                else -> "Couldn't check just now"
            }
            else -> "Finding best version\u2026"
        }
        filmSheet.setPlay(sub, hasVersions)
        filmSheet.setWhere(whereLines())
    }

    private fun onPlayPressed(current: Film) {
        val imdb = details?.imdbId
        val result = smartResult?.takeIf { smartFor == imdb }
        val best = result?.best
        when {
            result != null && best != null -> { sheet.hide(); play(best, result, current) }
            result?.status == com.example.tvlauncher.data.stream.SmartStatus.NO_SOURCES ->
                connectSheet.show { smartFor = null; smartResult = null; showFilm() }
            smartLoading && result == null -> Toast.makeText(activity, "Still finding the best version\u2026", Toast.LENGTH_SHORT).show()
            else -> Toast.makeText(activity, smartMessage(result?.status ?: com.example.tvlauncher.data.stream.SmartStatus.ERROR) ?: "Nothing to play", Toast.LENGTH_LONG).show()
        }
    }

    private fun smartMessage(status: com.example.tvlauncher.data.stream.SmartStatus): String? = when (status) {
        com.example.tvlauncher.data.stream.SmartStatus.NO_SOURCES -> "PLAY needs your Stremio account. Press PLAY to connect it."
        com.example.tvlauncher.data.stream.SmartStatus.NO_STREAMS -> "No streams found from your sources for this film."
        com.example.tvlauncher.data.stream.SmartStatus.NOTHING_PLAYABLE -> "Your sources only offer torrents, which need Stremio."
        com.example.tvlauncher.data.stream.SmartStatus.OFFLINE -> "No internet connection."
        com.example.tvlauncher.data.stream.SmartStatus.ERROR -> "Couldn't check streams right now."
        else -> null
    }

    /** Plays [chosen] first, then up to two of the next-best playable versions as automatic fallbacks. */
    private fun play(chosen: com.example.tvlauncher.data.stream.RankedStream, result: com.example.tvlauncher.data.stream.SmartResult, current: Film) {
        // Torrent results play in Stremio's own player (with its subtitles): its magnet handler resolves the file.
        if (chosen.candidate.stremioHandoff) { handToStremio(chosen, current); return }
        val backups = result.ranked.filter { it.candidate.directPlayable && it !== chosen && it.verdict != com.example.tvlauncher.data.stream.Verdict.INCOMPATIBLE }
        PlayerActivity.launch(activity, listOf(chosen) + backups, current.title, details?.imdbId)
    }

    private fun handToStremio(chosen: com.example.tvlauncher.data.stream.RankedStream, current: Film) {
        val candidate = chosen.candidate
        val m = candidate.meta
        com.example.tvlauncher.data.stream.HandoffStore(activity).set(com.example.tvlauncher.data.stream.PendingHandoff(
            details?.imdbId ?: current.title, candidate.provider, m.resolution, m.codec, m.source, chosen.requiredBps, System.currentTimeMillis()
        ))
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(candidate.magnetUri(current.title.replace(Regex("\\s*\\(\\d{4}\\)$"), ""))))
                .setPackage("com.stremio.one").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { Toast.makeText(activity, "Stremio isn't installed on this TV", Toast.LENGTH_LONG).show() }
    }

    /** A restrained list of the playable versions, best first. */
    private fun showVersions(result: com.example.tvlauncher.data.stream.SmartResult, current: Film) {
        val playable = result.ranked.filter { it.playable && it.verdict != com.example.tvlauncher.data.stream.Verdict.INCOMPATIBLE }.take(6)
        val actions = playable.map { r ->
            val tag = when (r.verdict) {
                com.example.tvlauncher.data.stream.Verdict.RECOMMENDED -> "Recommended"
                com.example.tvlauncher.data.stream.Verdict.EXCELLENT -> "Excellent"
                com.example.tvlauncher.data.stream.Verdict.GOOD -> "Good"
                else -> "May buffer"
            }
            com.example.tvlauncher.ui.room.SheetAction((if (r === result.best) "\u2713 " else "") + r.candidate.meta.qualityLabel(), tag) { play(r, result, current) }
        }
        sheet.show("Change version", current.title, null, listOf("Best first. \"May buffer\" needs more than this connection can comfortably sustain."), null, actions)
    }

    /**
     * Opens the film in Stremio: straight to its page when Letterboxd gave an IMDb id, otherwise
     * Stremio's search for the title. Stremio does not accept a "play now" link, so this lands on the
     * film's page and you press play there.
     */
    private fun openInStremio() {
        val current = film ?: return
        val imdb = details?.imdbId
        val title = current.title.replace(Regex("\\s*\\(\\d{4}\\)$"), "")
        val uri = if (imdb != null) "stremio:///detail/movie/$imdb/$imdb"
                  else "stremio:///search?search=" + java.net.URLEncoder.encode(title, "UTF-8")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.stremio.one").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { Toast.makeText(activity, "Stremio isn't installed on this TV", Toast.LENGTH_LONG).show() }
    }

    private fun artSettings() {
        val choices = listOf("30 seconds" to 30000L, "1 minute" to 60000L, "5 minutes" to 300000L, "15 minutes" to 900000L)
        val current = choices.firstOrNull { it.second == art.library.interval }?.first
        sheet.show(
            "Painting settings", "Painting interval", current?.let { "Now: $it" },
            listOf("How long each painting stays up in Art mode."),
            null, choices.map { (label, ms) -> com.example.tvlauncher.ui.room.SheetAction(label) { art.library.interval = ms } }
        )
    }

    private fun text(value: String,size: Float,dim: Boolean = false)=TextView(activity).apply {
        text=value; textSize=size
        setTextColor(activity.getColor(if (dim) R.color.ivory_text_dim else R.color.ivory))
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    /** The shared surface: walnut glass at rest, lit ivory when focused. */
    private fun panel(view: View,radiusDp: Int) {
        view.isFocusable=true; view.isClickable=true
        val radius=dp(radiusDp).toFloat()
        LauncherTheme.bindPanelFocus(view, LauncherTheme.tileBackground(activity,radius), LauncherTheme.tileBackgroundFocused(activity,radius)) { active -> scrollFocusIntoView(view,active) }
    }
    private fun dp(value:Int)=(value*activity.resources.displayMetrics.density).toInt()
    private fun px(res: Int) = activity.resources.getDimensionPixelSize(res)
}
