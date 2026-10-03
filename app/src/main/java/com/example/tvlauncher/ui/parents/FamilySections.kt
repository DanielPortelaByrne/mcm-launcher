package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.tvlauncher.data.parents.FeedImages
import com.example.tvlauncher.data.parents.Inspiration
import com.example.tvlauncher.data.parents.LocalEvent
import com.example.tvlauncher.data.parents.ParentsFormat
import com.example.tvlauncher.data.parents.Photo
import com.example.tvlauncher.data.parents.Pick
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.SectionTheme
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.scrollFocusIntoView

// ---------------------------------------------------------------------------------------------------
// Amélia's crochet basket
// ---------------------------------------------------------------------------------------------------

/** Pattern cards: one large, two small, new ones each day. Inspiration only; OK shows the picture large. */
class CrochetSection(private val activity: Activity, private val images: FeedImages, private val viewer: PhotoViewer) {
    val section = Kit.Section(activity, "Ideias de crochê", "Para inspirar", SectionTheme.Mood.MAKING)
    private val row = LinearLayout(activity).row(activity)
    private var shownKey: String? = null

    init { section.body.addView(row, LinearLayout.LayoutParams(-1, -2)) }

    fun bind(ideas: List<Inspiration>) {
        if (ideas.isEmpty()) { section.shown = false; return }
        section.shown = true
        val key = ideas.joinToString { it.id }
        if (key == shownKey) return
        shownKey = key
        row.removeAllViews()
        val cards = ideas.take(3).mapIndexed { i, idea ->
            card(idea, i, ideas).also { row.addView(it, LinearLayout.LayoutParams(Kit.dp(activity, if (i == 0) 424 else 204), Kit.dp(activity, 260)).apply { if (i > 0) marginStart = Kit.dp(activity, 16) }) }
        }
        Kit.trapEdges(cards)
    }

    private fun card(idea: Inspiration, index: Int, all: List<Inspiration>): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Kit.paper(activity, 6)
            val p = Kit.dp(activity, 10); setPadding(p, p, p, Kit.dp(activity, 8))
            elevation = Kit.dpf(activity, 4f)
            rotation = floatArrayOf(-0.8f, 1.2f, -1.4f)[index % 3]
            isFocusable = true; isClickable = true; contentDescription = idea.title
        }
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x22261E14) }
        Kit.rounded(image, Kit.dpf(activity, 3f))
        card.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val title = Kit.line(activity, idea.title, Type.Style.BODY, Kit.PAPER_INK).apply { typeface = Typeface.create("serif", Typeface.ITALIC) }
        card.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 6) })
        val tilt = card.rotation
        Kit.imageFocus(card, card, null, null, Kit.dpf(activity, 6f)) { focused ->
            card.animate().rotation(if (focused) 0f else tilt).setDuration(if (focused) Motion.FOCUS_IN_MS else Motion.FOCUS_OUT_MS).setInterpolator(Motion.SETTLE).start()
        }
        card.setOnClickListener {
            viewer.show(all.map { PhotoViewer.Slide(it.image, it.image, it.title, it.credit) }, index, card)
        }
        images.load(idea.image, Kit.dp(activity, 404), Kit.dp(activity, 220)) { it?.let { b -> image.setImageBitmap(b) } }
        return card
    }
}

// ---------------------------------------------------------------------------------------------------
// Ária's corner
// ---------------------------------------------------------------------------------------------------

/** A few picture books, each one calm programme from an allow-listed channel. One press plays it. */
class AriaSection(private val activity: Activity, private val images: FeedImages) {
    val section = Kit.Section(activity, "Ária's corner", "For the little one", SectionTheme.Mood.EVENING)
    private val row = LinearLayout(activity).row(activity)
    private var shownKey: String? = null
    private val covers = intArrayOf(0xFF8FA98A.toInt(), 0xFFDDBB57.toInt(), 0xFFD9897A.toInt(), 0xFF7C9CB8.toInt())

    init { section.body.addView(row, LinearLayout.LayoutParams(-1, -2)) }

    fun bind(picks: List<Pick>) {
        if (picks.isEmpty()) { section.shown = false; return }
        section.shown = true
        val key = picks.joinToString { it.link.uri }
        if (key == shownKey) return
        shownKey = key
        row.removeAllViews()
        val books = picks.take(4).mapIndexed { i, p -> book(p, covers[i % covers.size]).also { row.addView(it, LinearLayout.LayoutParams(Kit.dp(activity, 204), Kit.dp(activity, 196)).apply { if (i > 0) marginStart = Kit.dp(activity, 16) }) } }
        Kit.trapEdges(books)
    }

    private fun book(pick: Pick, colour: Int): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = Kit.dpf(activity, 16f); setColor(colour) }
            val p = Kit.dp(activity, 10); setPadding(p, p, p, Kit.dp(activity, 10))
            elevation = Kit.dpf(activity, 4f)
            isFocusable = true; isClickable = true; contentDescription = pick.title
        }
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0x33000000) }
        Kit.rounded(image, Kit.dpf(activity, 10f))
        card.addView(image, LinearLayout.LayoutParams(-1, Kit.dp(activity, 104)))
        card.addView(Kit.line(activity, pick.title, Type.Style.HEADING, Kit.PAPER_INK).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 10) })
        card.addView(Kit.line(activity, pick.minutes?.let { "$it minutes" } ?: "", Type.Style.CAPTION, Kit.PAPER_INK_DIM).apply { gravity = Gravity.CENTER })
        Kit.imageFocus(card, card, null, null, Kit.dpf(activity, 16f))
        card.setOnClickListener { Motion.press(card, Motion.CARD_SCALE); Opener.open(activity, pick.link, pick.title) }
        pick.image?.let { url -> images.load(url, Kit.dp(activity, 184), Kit.dp(activity, 104)) { it?.let { b -> image.setImageBitmap(b) } } }
        return card
    }
}

// ---------------------------------------------------------------------------------------------------
// From the family album
// ---------------------------------------------------------------------------------------------------

/**
 * One old photograph a day, framed and mounted, from a deliberately curated album (never the whole
 * library). Its title and year beside it. OK opens the album full screen at that photograph.
 */
class FamilyArchiveSection(private val activity: Activity, private val images: FeedImages, private val viewer: PhotoViewer) {
    val section = Kit.Section(activity, "From the family album", null, SectionTheme.Mood.GALLERY)
    private val frame = FrameLayout(activity).apply { isFocusable = true; isClickable = true; clipChildren = false }
    private val photoView = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF3B2C1F.toInt()) }
    private val title = Type.onPainting(Kit.line(activity, "", Type.Style.TITLE, lines = 2))
    private val year = Type.onPainting(Kit.line(activity, "", Type.Style.BODY))
    private var photos: List<Photo> = emptyList()
    private var index = 0
    private var shownId: String? = null

    init {
        val mount = FrameLayout(activity).apply {
            background = Kit.framed(activity, 12)
            val p = Kit.dp(activity, 30); setPadding(p, p, p, p)
            elevation = Kit.dpf(activity, 6f)
            addView(photoView, FrameLayout.LayoutParams(-1, -1))
        }
        frame.addView(mount, FrameLayout.LayoutParams(-1, -1))
        Kit.imageFocus(frame, mount, null, null, Kit.dpf(activity, 10f))
        frame.setOnClickListener {
            viewer.show(photos.map { PhotoViewer.Slide(it.imageUrl, it.thumbUrl, it.title ?: it.caption, it.year?.toString()) }, index, frame)
        }
        val row = LinearLayout(activity).row(activity)
        row.addView(frame, LinearLayout.LayoutParams(Kit.dp(activity, 420), Kit.dp(activity, 316)))
        val words = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        words.addView(title); words.addView(year, LinearLayout.LayoutParams(-2, -2).apply { topMargin = Kit.dp(activity, 6) })
        row.addView(words, LinearLayout.LayoutParams(0, Kit.dp(activity, 316), 1f).apply { marginStart = Kit.dp(activity, 32) })
        section.body.addView(row, LinearLayout.LayoutParams(-1, -2))
        Kit.trapEdges(listOf(frame))
    }

    fun bind(list: List<Photo>, now: Long) {
        if (list.isEmpty()) { section.shown = false; return }
        section.shown = true
        photos = list
        index = Math.floorMod(ParentsFormat.dayIndex(now), list.size)
        val p = list[index]
        if (p.id == shownId) return
        shownId = p.id
        title.text = p.title ?: p.caption ?: "A family photograph"
        year.text = p.year?.toString().orEmpty()
        frame.contentDescription = "${title.text} ${year.text}"
        images.load(p.thumbUrl, Kit.dp(activity, 360), Kit.dp(activity, 256)) { it?.let { b -> Motion.swapImage(photoView) { photoView.setImageBitmap(b) } } }
    }
}

// ---------------------------------------------------------------------------------------------------
// At the Seantí
// ---------------------------------------------------------------------------------------------------

/** A gig poster pinned up, only when there is a relevant night coming at the Seantí. */
class SeantiSection(private val activity: Activity, private val images: FeedImages) {
    val section = Kit.Section(activity, "At the Seantí", null, SectionTheme.Mood.SPINNING)
    private val row = LinearLayout(activity).row(activity)

    init { section.body.addView(row, LinearLayout.LayoutParams(-1, -2)) }

    fun bind(events: List<LocalEvent>, now: Long) {
        val next = events.filter { e -> ParentsFormat.parse(e.date)?.let { ParentsFormat.daysBetween(now, it) in 0..45 } == true }.take(2)
        if (next.isEmpty()) { section.shown = false; return }
        section.shown = true
        row.removeAllViews()
        val posters = next.mapIndexed { i, e -> poster(e, now).also { row.addView(it, LinearLayout.LayoutParams(Kit.dp(activity, 424), Kit.dp(activity, 180)).apply { if (i > 0) marginStart = Kit.dp(activity, 16) }) } }
        Kit.trapEdges(posters)
    }

    private fun poster(e: LocalEvent, now: Long): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Kit.paper(activity, 4)
            setPadding(Kit.dp(activity, 14), Kit.dp(activity, 14), Kit.dp(activity, 18), Kit.dp(activity, 14))
            elevation = Kit.dpf(activity, 5f); rotation = -1f
            isFocusable = true; isClickable = true; contentDescription = e.title
        }
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Kit.TERRACOTTA) }
        card.addView(image, LinearLayout.LayoutParams(Kit.dp(activity, 110), -1).apply { marginEnd = Kit.dp(activity, 16) })
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val start = ParentsFormat.parse(e.date)
        col.addView(Kit.line(activity, start?.let { ParentsFormat.eventWhen(com.example.tvlauncher.data.parents.FamilyEvent(e.title, e.date, null, true), now) } ?: "", Type.Style.EYEBROW, Kit.TERRACOTTA))
        col.addView(Kit.line(activity, e.title, Type.Style.HEADING, Kit.PAPER_INK, lines = 2))
        e.note?.let { col.addView(Kit.line(activity, it, Type.Style.CAPTION, Kit.PAPER_INK_DIM, lines = 2)) }
        card.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
        val ring = LauncherTheme.outsetRing(card, Kit.dpf(activity, 4f), Kit.dp(activity, 5))
        card.setOnFocusChangeListener { v, focused -> LauncherTheme.fadeDrawable(v, ring, focused); LauncherTheme.animateFocus(v, focused, 1.03f); scrollFocusIntoView(v, focused) }
        card.setOnClickListener { e.link?.let { Opener.open(activity, it, e.title) } }
        e.image?.let { url -> images.load(url, Kit.dp(activity, 110), Kit.dp(activity, 152)) { it?.let { b -> image.setImageBitmap(b) } } }
        return card
    }
}
