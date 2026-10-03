package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.tvlauncher.R
import com.example.tvlauncher.data.parents.FeedImages
import com.example.tvlauncher.data.parents.Listening
import com.example.tvlauncher.data.parents.ParentsFormat
import com.example.tvlauncher.data.parents.Pick
import com.example.tvlauncher.data.parents.Sport
import com.example.tvlauncher.data.parents.Team
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.SectionTheme
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.room.InfoSheet
import com.example.tvlauncher.ui.room.SheetAction
import com.example.tvlauncher.ui.scrollFocusIntoView

// ---------------------------------------------------------------------------------------------------
// Tonight at home
// ---------------------------------------------------------------------------------------------------

/** One film and one documentary for the two of them, changing daily. OK shows the film; the sheet opens it. */
class TonightAtHomeSection(private val activity: Activity, private val images: FeedImages, private val sheet: InfoSheet) {
    val section = Kit.Section(activity, "Tonight at home", "Something you'd both enjoy", SectionTheme.Mood.FILM)
    private val row = LinearLayout(activity).row(activity)
    private var shownKey: String? = null

    init { section.body.addView(row, LinearLayout.LayoutParams(-1, -2)) }

    fun bind(picks: List<Pick>) {
        if (picks.isEmpty()) { section.shown = false; return }
        section.shown = true
        val key = picks.joinToString { it.link.uri }
        if (key == shownKey) return
        shownKey = key
        row.removeAllViews()
        val cards = picks.take(2).map { card(it) }
        cards.forEachIndexed { i, c -> row.addView(c, LinearLayout.LayoutParams(Kit.dp(activity, 424), Kit.dp(activity, 184)).apply { if (i > 0) marginStart = Kit.dp(activity, 16) }) }
        Kit.trapEdges(cards)
    }

    private fun card(pick: Pick): View {
        val poster = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = Kit.dpf(activity, 10f); setColor(0x33F3E9CF) }
        }
        Kit.rounded(poster, Kit.dpf(activity, 10f))
        var posterBitmap: Bitmap? = null
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val inset = Kit.dp(activity, 16); setPadding(inset, inset, Kit.dp(activity, 20), inset)
            contentDescription = pick.title
        }
        card.addView(poster, LinearLayout.LayoutParams(Kit.dp(activity, 101), Kit.dp(activity, 152)).apply { gravity = Gravity.CENTER_VERTICAL; marginEnd = Kit.dp(activity, 20) })
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        column.addView(Kit.line(activity, if (pick.kind == "documentary") "Documentary" else "Film", Type.Style.EYEBROW))
        column.addView(Kit.line(activity, pick.title, Type.Style.HEADING, lines = 2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 6) })
        column.addView(Kit.line(activity, listOfNotNull(pick.year?.toString(), "Stremio").joinToString(" · "), Type.Style.CAPTION), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 6) })
        card.addView(column, LinearLayout.LayoutParams(0, -1, 1f))
        val radius = Kit.dpf(activity, 20f)
        card.isFocusable = true; card.isClickable = true
        LauncherTheme.bindPanelFocus(card, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius)) { scrollFocusIntoView(card, it) }
        card.setOnClickListener {
            sheet.show(
                "Tonight at home", pick.title, pick.year?.toString(), listOfNotNull(pick.note),
                image = posterBitmap, from = poster,
                actions = listOf(SheetAction("Watch in Stremio", primary = true) { Opener.open(activity, pick.link, pick.title) })
            )
        }
        pick.image?.let { url -> images.load(url, Kit.dp(activity, 150), Kit.dp(activity, 225)) { it?.let { b -> posterBitmap = b; Motion.swapImage(poster) { poster.setImageBitmap(b) } } } }
        return card
    }
}

// ---------------------------------------------------------------------------------------------------
// Padraig's listening room
// ---------------------------------------------------------------------------------------------------

/**
 * LP sleeves on a shelf (OK plays the artist in Spotify), tonight's gig as a print with its ticket
 * pinned on, and the latest episodes of his podcasts on a printed listing (they open on YouTube, where
 * the shows post full episodes).
 */
class ListeningRoomSection(private val activity: Activity, private val images: FeedImages) {
    val section = Kit.Section(activity, "Padraig's listening room", null, SectionTheme.Mood.SPINNING)
    private val sleeves = LinearLayout(activity).row(activity)
    private val sleeveCaption = Type.onPainting(Kit.line(activity, "", Type.Style.BODY)).apply { alpha = Motion.CAPTION_REST_ALPHA }
    private val second = LinearLayout(activity).row(activity)
    private var shownKey: String? = null

    init {
        section.body.addView(sleeves, LinearLayout.LayoutParams(-1, Kit.dp(activity, 116)))
        section.body.addView(sleeveCaption, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 6) })
        section.body.addView(second, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 24) })
    }

    fun bind(l: Listening?) {
        if (l == null || (l.records.isEmpty() && l.concert == null && l.podcasts.isEmpty())) { section.shown = false; return }
        section.shown = true
        val key = (l.records + listOfNotNull(l.concert) + l.podcasts).joinToString { it.link.uri + it.title }
        if (key == shownKey) return
        shownKey = key
        sleeves.removeAllViews(); second.removeAllViews()
        val shelf = l.records.take(7).mapIndexed { i, r -> sleeve(r).also { sleeves.addView(it, LinearLayout.LayoutParams(Kit.dp(activity, 104), Kit.dp(activity, 104)).apply { if (i > 0) marginStart = Kit.dp(activity, 22); gravity = Gravity.BOTTOM }) } }
        Kit.trapEdges(shelf)
        sleeveCaption.text = l.records.firstOrNull()?.title.orEmpty()
        val lower = mutableListOf<View>()
        l.concert?.let { lower += gig(it).also { v -> second.addView(v, LinearLayout.LayoutParams(Kit.dp(activity, 424), Kit.dp(activity, 238))) } }
        if (l.podcasts.isNotEmpty()) {
            val (card, rows) = podcasts(l.podcasts)
            second.addView(card, LinearLayout.LayoutParams(Kit.dp(activity, 424), Kit.dp(activity, 238)).apply { if (second.childCount > 0) marginStart = Kit.dp(activity, 16) })
            // Left from any episode goes to the gig (or nowhere); Right stays on the listing.
            val gigView = if (l.concert != null) second.getChildAt(0) else null
            rows.forEach { r -> r.setOnKeyListener { _, k, e ->
                when {
                    e.action != android.view.KeyEvent.ACTION_DOWN -> false
                    k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    k == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { gigView?.requestFocus(); true }
                    else -> false
                }
            } }
        }
        if (l.concert != null) second.getChildAt(0).setOnKeyListener { _, k, e ->
            e.action == android.view.KeyEvent.ACTION_DOWN && (k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && l.podcasts.isEmpty()))
        }
    }

    private fun sleeve(record: Pick): View {
        val box = FrameLayout(activity).apply { isFocusable = true; isClickable = true; clipChildren = false; contentDescription = record.title }
        val disc = VinylView(activity)
        box.addView(disc, FrameLayout.LayoutParams(-1, -1).apply { setMargins(Kit.dp(activity, 6), Kit.dp(activity, 6), Kit.dp(activity, 6), Kit.dp(activity, 6)) })
        val cover = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF3B2C1F.toInt()); elevation = Kit.dpf(activity, 4f) }
        Kit.rounded(cover, Kit.dpf(activity, 3f))
        box.addView(cover, FrameLayout.LayoutParams(-1, -1))
        val mat = LauncherTheme.imageMat(activity, Kit.dpf(activity, 3f)).apply { alpha = 0 }
        cover.foreground = mat
        box.setOnFocusChangeListener { v, focused ->
            LauncherTheme.fadeDrawable(cover, mat, focused)
            Motion.focusImageCard(cover, null, null, focused)
            // The record slides a little way out of its sleeve, and back.
            disc.animate().translationX(if (focused) Kit.dpf(activity, 30f) else 0f).setDuration(if (focused) 260 else Motion.FOCUS_OUT_MS).setInterpolator(Motion.SETTLE).start()
            if (focused) { Motion.swapText(sleeveCaption, record.title); sleeveCaption.animate().alpha(1f).setDuration(Motion.FOCUS_IN_MS).start(); scrollFocusIntoView(v, true) }
            else sleeveCaption.animate().alpha(Motion.CAPTION_REST_ALPHA).setDuration(Motion.FOCUS_OUT_MS).start()
        }
        box.setOnClickListener { Motion.press(cover, Motion.CARD_SCALE); Opener.open(activity, record.link, record.title) }
        record.image?.let { url -> images.load(url, Kit.dp(activity, 104), Kit.dp(activity, 104)) { it?.let { b -> cover.setImageBitmap(b) } } }
        return box
    }

    private fun gig(concert: Pick): View {
        val frame = FrameLayout(activity).apply { background = LauncherTheme.surface(activity, R.color.surface_raised, R.dimen.radius_small) }
        Kit.rounded(frame, Kit.dpf(activity, 10f))
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        val tile = FrameLayout(activity).apply { isFocusable = true; isClickable = true; clipChildren = false; contentDescription = "Tonight's gig: ${concert.title}" }
        tile.addView(frame, FrameLayout.LayoutParams(-1, -1))
        // The ticket pinned to the corner of the print.
        val ticket = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Kit.paper(activity, 4)
            setPadding(Kit.dp(activity, 14), Kit.dp(activity, 8), Kit.dp(activity, 16), Kit.dp(activity, 10))
            elevation = Kit.dpf(activity, 8f); rotation = -2f
            addView(Kit.line(activity, "Tonight's gig", Type.Style.EYEBROW, Kit.TERRACOTTA))
            addView(Kit.line(activity, concert.subtitle ?: concert.title, Type.Style.HEADING, Kit.PAPER_INK))
            addView(Kit.line(activity, concert.minutes?.let { if (it >= 60) "${it / 60} hr ${it % 60} min" else "$it min" } ?: "Full concert", Type.Style.CAPTION, Kit.PAPER_INK_DIM))
        }
        tile.addView(ticket, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(Kit.dp(activity, 14), 0, 0, Kit.dp(activity, 14)) })
        Kit.imageFocus(tile, frame, image, ticket, Kit.dpf(activity, 10f))
        tile.setOnClickListener { Motion.press(frame, Motion.CARD_SCALE); Opener.open(activity, concert.link, concert.title) }
        concert.image?.let { url -> images.load(url, Kit.dp(activity, 424), Kit.dp(activity, 238)) { it?.let { b -> image.setImageBitmap(b) } } }
        return tile
    }

    private fun podcasts(episodes: List<Pick>): Pair<View, List<View>> {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Kit.paper(activity, 10)
            setPadding(Kit.dp(activity, 12), Kit.dp(activity, 12), Kit.dp(activity, 12), Kit.dp(activity, 8))
            elevation = Kit.dpf(activity, 5f)
        }
        val mat = LauncherTheme.imageMat(activity, Kit.dpf(activity, 10f)).apply { alpha = 0 }
        card.foreground = mat
        card.addView(Type.text(activity, "On the wireless", Type.Style.HEADING, Kit.PAPER_INK).apply { typeface = Typeface.create("serif", Typeface.BOLD_ITALIC); setPadding(Kit.dp(activity, 12), 0, 0, Kit.dp(activity, 4)) })
        val rows = mutableListOf<View>()
        episodes.take(4).forEach { ep ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                setPadding(Kit.dp(activity, 12), 0, Kit.dp(activity, 12), 0)
                contentDescription = "${ep.subtitle}: ${ep.title}"
                addView(Kit.line(activity, ep.subtitle.orEmpty(), Type.Style.EYEBROW, Kit.TERRACOTTA).apply { textSize = 12f })
                addView(Kit.line(activity, ep.title, Type.Style.BODY, Kit.PAPER_INK).apply { textSize = 16f })
            }
            Kit.paperRowFocus(row, card, mat, { rows })
            row.setOnClickListener { Opener.open(activity, ep.link, ep.subtitle ?: ep.title) }
            rows += row
            card.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return card to rows
    }
}

/** A black record with a little label, drawn rather than shipped as an image. */
private class VinylView(context: android.content.Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val r = minOf(width, height) / 2f
        val cx = width / 2f; val cy = height / 2f
        p.style = Paint.Style.FILL; p.color = 0xFF151210.toInt(); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x33F3E9CF
        for (i in 1..4) c.drawCircle(cx, cy, r * (0.5f + i * 0.11f), p)
        p.style = Paint.Style.FILL; p.color = Kit.TERRACOTTA; c.drawCircle(cx, cy, r * 0.32f, p)
        p.color = 0xFF151210.toInt(); c.drawCircle(cx, cy, r * 0.04f, p)
    }
}

// ---------------------------------------------------------------------------------------------------
// Match programme
// ---------------------------------------------------------------------------------------------------

/**
 * Wolves (always, when there is a fixture or a result) and Ireland (only around a match) as printed match
 * programmes. Only what the feed actually knows: no stale scores, no odds, nothing when there is nothing.
 */
class MatchProgrammeSection(private val activity: Activity, private val sheet: InfoSheet) {
    val section = Kit.Section(activity, "Match programme", null, SectionTheme.Mood.GALLERY)
    private val row = LinearLayout(activity).row(activity)

    init { section.body.addView(row, LinearLayout.LayoutParams(-1, -2)) }

    fun bind(sport: Sport?, now: Long) {
        val teams = listOfNotNull(
            sport?.wolves?.takeIf { it.next != null || it.last != null || it.live != null }?.let { it to Colours(0xFFFDB913.toInt(), 0xFF231F20.toInt()) },
            sport?.ireland?.takeIf { it.next != null || it.last != null || it.live != null }?.let { it to Colours(0xFF169B62.toInt(), 0xFFF6F1E6.toInt()) }
        )
        if (teams.isEmpty()) { section.shown = false; return }
        section.shown = true
        val hadFocus = row.findFocus() != null
        row.removeAllViews()
        val covers = teams.map { (team, colours) -> cover(team, colours, now) }
        covers.forEachIndexed { i, c -> row.addView(c, LinearLayout.LayoutParams(Kit.dp(activity, 424), Kit.dp(activity, 214)).apply { if (i > 0) marginStart = Kit.dp(activity, 16) }) }
        Kit.trapEdges(covers)
        if (hadFocus) covers.first().requestFocus()
    }

    private data class Colours(val band: Int, val onBand: Int)

    private fun cover(team: Team, colours: Colours, now: Long): View {
        val featured = team.live ?: team.next ?: team.last!!
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Kit.paper(activity, 8)
            elevation = Kit.dpf(activity, 6f)
            isFocusable = true; isClickable = true
        }
        val band = FrameLayout(activity).apply {
            background = GradientDrawable().apply { val r = Kit.dpf(activity, 8f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setColor(colours.band) }
            setPadding(Kit.dp(activity, 20), 0, Kit.dp(activity, 20), 0)
            addView(Kit.line(activity, team.name, Type.Style.HEADING, colours.onBand).apply { textSize = 20f }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START))
            addView(Kit.line(activity, "Match programme", Type.Style.EYEBROW, colours.onBand), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))
        }
        card.addView(band, LinearLayout.LayoutParams(-1, Kit.dp(activity, 44)))
        val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(Kit.dp(activity, 20), Kit.dp(activity, 12), Kit.dp(activity, 20), Kit.dp(activity, 12)) }
        val headline = when {
            team.live != null -> "Playing now"
            team.next != null -> ParentsFormat.kickoff(featured.date, now)
            else -> "Full time"
        }
        body.addView(Kit.line(activity, headline, Type.Style.EYEBROW, Kit.TERRACOTTA))
        val fixture = if (featured.state == "pre") "${featured.home} v ${featured.away}" else ParentsFormat.score(featured)
        body.addView(Kit.line(activity, fixture, Type.Style.TITLE, Kit.PAPER_INK).apply { typeface = Typeface.create("serif", Typeface.BOLD) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 4) })
        body.addView(Kit.line(activity, listOfNotNull(featured.competition, featured.venue).joinToString(" · "), Type.Style.CAPTION, Kit.PAPER_INK_DIM), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 2) })
        val footer = listOfNotNull(
            team.last?.takeIf { it !== featured }?.let { m -> ParentsFormat.outcome(m, team.name)?.let { "Last time: $it ${m.homeScore}–${m.awayScore} v ${if (m.home == team.name) m.away else m.home}" } ?: "Last time: ${ParentsFormat.score(m)}" },
            team.position?.let { p -> "${ParentsFormat.ordinal(p)} in the ${ParentsFormat.leagueName(team.league) ?: "league"}" }
        )
        footer.forEach { body.addView(Kit.line(activity, it, Type.Style.BODY, Kit.PAPER_INK).apply { textSize = 16f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 6) }) }
        card.addView(body, LinearLayout.LayoutParams(-1, -2))
        val ring = LauncherTheme.outsetRing(card, Kit.dpf(activity, 8f), Kit.dp(activity, 5))
        card.setOnFocusChangeListener { v, focused -> LauncherTheme.fadeDrawable(v, ring, focused); LauncherTheme.animateFocus(v, focused, 1.03f); scrollFocusIntoView(v, focused) }
        card.contentDescription = "${team.name}: $headline, $fixture"
        card.setOnClickListener {
            sheet.show("Match programme", team.name, team.position?.let { "${ParentsFormat.ordinal(it)} · ${ParentsFormat.leagueName(team.league) ?: ""}".trim(' ', '·') }, listOfNotNull(
                team.live?.let { "Now: ${ParentsFormat.score(it)}" },
                team.next?.let { "Next: ${it.home} v ${it.away}, ${ParentsFormat.kickoff(it.date, now)}${it.venue?.let { v -> " at $v" } ?: ""}" },
                team.last?.let { "Last: ${ParentsFormat.score(it)}${it.competition?.let { c -> " ($c)" } ?: ""}" }
            ))
        }
        return card
    }
}
