package com.example.tvlauncher.ui.room

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.example.tvlauncher.data.withoutEmDashes
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.tvlauncher.R
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.data.FilmPage
import java.util.Locale

private fun Context.px(v: Int) = (v * resources.displayMetrics.density).toInt()

/** What the sheet's buttons do. */
class FilmActions(
    val play: () -> Unit,
    val stremio: () -> Unit,
    val versions: () -> Unit,
    val next: () -> Unit,
    val letterboxd: () -> Unit,
    val refresh: () -> Unit
)

/**
 * The popup for Eva's film: poster, a row of tabs (Overview, Cast, Crew, Details, Watch) drawn from the
 * film's Letterboxd page, and a tidy set of pill actions. Buttons change colour on focus rather than
 * scaling, so a highlight can never be clipped by its container.
 */
class FilmSheet(private val context: Context, private val sheet: InfoSheet) {

    private val ivory = ContextCompat.getColor(context, R.color.ivory)
    private val ink = ContextCompat.getColor(context, R.color.ink)
    private val dim = ContextCompat.getColor(context, R.color.ivory_text_dim)
    private val butter = ContextCompat.getColor(context, R.color.butter)
    private val terracotta = ContextCompat.getColor(context, R.color.terracotta)
    private val pillRest = ContextCompat.getColor(context, R.color.btn_brown_on)
    private val pillQuiet = ContextCompat.getColor(context, R.color.panel_bg)

    private enum class Tab(val label: String) { OVERVIEW("Overview"), CAST("Cast"), CREW("Crew"), DETAILS("Details"), WATCH("Watch") }

    private var page: FilmPage? = null
    private var whereLines: List<String> = emptyList()
    private var rating: String? = null
    private var selected = Tab.OVERVIEW
    private val tabViews = HashMap<Tab, TextView>()
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var playButton: TextView? = null
    private var versionsButton: View? = null

    val isVisible: Boolean get() = sheet.isVisible

    /** [from] is the poster on the page: when given, it lifts off the page and becomes the sheet's poster. */
    fun show(title: String, ratingLabel: String?, poster: Bitmap?, page: FilmPage?, whereLines: List<String>, actions: FilmActions, from: View? = null) {
        this.page = page; this.whereLines = whereLines; this.rating = ratingLabel; selected = Tab.OVERVIEW
        tabViews.clear()

        val pad = dimen(R.dimen.space_6)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false; clipToPadding = false
            setPadding(pad, dimen(R.dimen.space_5), pad, dimen(R.dimen.space_5))
            background = LauncherTheme.surface(context, R.color.surface_solid)
            elevation = context.px(24).toFloat()
        }

        // ---- left: poster and rating ----
        val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        val posterView = ImageView(context)
        left.addView(posterView.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF3A2C1E.toInt())
            poster?.let { setImageBitmap(it) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dimen(R.dimen.radius_small).toFloat()) } }
        }, LinearLayout.LayoutParams(context.px(160), context.px(240)))
        val reveal = mutableListOf<View>()
        ratingLabel?.let { label ->
            val badge = com.example.tvlauncher.ui.LetterboxdBadge(context).apply { set(label) }
            left.addView(badge.view, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dimen(R.dimen.space_3) })
            reveal += badge.view
        }
        card.addView(left, LinearLayout.LayoutParams(context.px(160), -2).apply { marginEnd = dimen(R.dimen.space_5) })

        // ---- right: header, tabs, content, actions ----
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        card.addView(right, LinearLayout.LayoutParams(context.px(610), -2))
        reveal += right

        right.addView(Type.text(context, "Eva's pick for tonight", Type.Style.EYEBROW))
        right.addView(Type.text(context, title, Type.Style.TITLE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dimen(R.dimen.space_1), 0, 0) })
        // One meta line: runtime, genres, director (the year is already in the title).
        page?.let { pg ->
            val genres = pg.genres.take(2).joinToString(", ").takeIf { it.isNotBlank() }
            val parts = pg.headline(withYear = !Regex("""\(\d{4}\)\s*$""").containsMatchIn(title)).split(" · ").filter { it.isNotBlank() }.toMutableList()
            genres?.let { parts.add(minOf(1, parts.size), it) }
            parts.joinToString(" · ")
        }?.takeIf { it.isNotBlank() }?.let { right.addView(Type.text(context, it, Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dimen(R.dimen.space_1), 0, 0) }) }
        page?.tagline?.withoutEmDashes()?.let { right.addView(Type.text(context, "“$it”", Type.Style.CAPTION).apply { typeface = Typeface.create("serif", Typeface.ITALIC); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dimen(R.dimen.space_1), 0, 0) }) }

        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        Tab.values().forEachIndexed { i, tab ->
            val v = Type.text(context, tab.label, Type.Style.BODY, dim).apply {
                gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                setPadding(dimen(R.dimen.space_3) + dimen(R.dimen.space_1), 0, dimen(R.dimen.space_3) + dimen(R.dimen.space_1), 0)
            }
            v.setOnFocusChangeListener { _, focused -> if (focused) select(tab); styleTab(tab, focused) }
            v.setOnClickListener { select(tab) }
            tabViews[tab] = v
            // Left and Right stay on the tab row: the ends do not wander off to other buttons.
            v.setOnKeyListener { _, code, ev ->
                ev.action == android.view.KeyEvent.ACTION_DOWN &&
                    ((code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && i == Tab.values().lastIndex) || (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && i == 0))
            }
            tabs.addView(v, LinearLayout.LayoutParams(-2, dimen(R.dimen.space_6)).apply { if (i > 0) marginStart = dimen(R.dimen.space_1) })
        }
        // Tabs sit flush with the title: the first pill's text lines up with the text above it.
        right.addView(tabs, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dimen(R.dimen.space_2) + dimen(R.dimen.space_1); marginStart = -(dimen(R.dimen.space_3) + dimen(R.dimen.space_1)) })

        content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; setPadding(0, dimen(R.dimen.space_2), dimen(R.dimen.space_3), dimen(R.dimen.space_5)) }
        // The body scrolls with the D-pad (Down from the tabs), fading at the edge when there is more to read.
        scroll = ScrollView(context).apply {
            isFocusable = true; isFocusableInTouchMode = false
            isVerticalScrollBarEnabled = false; isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(context.px(30))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, android.view.ViewGroup.LayoutParams(-1, -2))
            setOnFocusChangeListener { v, focused -> v.background = if (focused) LauncherTheme.surface(context, R.color.hairline, R.dimen.radius_small) else null }
        }
        // Its own clipping frame: the surrounding views must not clip (so focus highlights are never cut off),
        // which would otherwise let scrolled text spill up over the tabs.
        val scrollHolder = FrameLayout(context).apply { clipChildren = true; addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        right.addView(scrollHolder, LinearLayout.LayoutParams(-1, context.px(132)).apply { topMargin = dimen(R.dimen.space_2); bottomMargin = dimen(R.dimen.space_4) })

        // primary row
        val primary = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        val gap = dimen(R.dimen.space_2) + dimen(R.dimen.space_1)
        val tall = context.px(56)
        val play = pill("Play", "Finding best version…", accent = true).also { playButton = it }
        play.setOnClickListener { actions.play() }
        primary.addView(play, LinearLayout.LayoutParams(context.px(230), tall))
        val stremio = pill("Open in Stremio").apply { setOnClickListener { hideThen(actions.stremio) } }
        primary.addView(stremio, LinearLayout.LayoutParams(-2, tall).apply { marginStart = gap })
        val versions = pill("Versions").apply { setOnClickListener { actions.versions() }; visibility = View.GONE }
        versionsButton = versions
        primary.addView(versions, LinearLayout.LayoutParams(-2, tall).apply { marginStart = gap })
        right.addView(primary)

        // Secondary actions: the same button, one step quieter.
        val quiet = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        listOf("Another film" to actions.next, "Letterboxd" to actions.letterboxd, "Refresh list" to actions.refresh).forEachIndexed { i, (label, run) ->
            quiet.addView(pill(label, quiet = true).apply { setOnClickListener { hideThen(run) } }, LinearLayout.LayoutParams(-2, context.px(44)).apply { if (i > 0) marginStart = gap })
        }
        right.addView(quiet, LinearLayout.LayoutParams(-2, -2).apply { topMargin = gap })

        select(Tab.OVERVIEW)
        styleAllTabs()
        val flight = if (from != null && poster != null) InfoSheet.Flight(from, posterView, reveal) else null
        sheet.showCustom(card, tabViews[Tab.OVERVIEW] ?: play, flight)
    }

    /** PLAY's second line and whether Versions is offered. Safe to call while the sheet is open. */
    fun setPlay(subLabel: String?, hasVersions: Boolean) {
        playButton?.let { it.text = playText("PLAY", subLabel) }
        versionsButton?.visibility = if (hasVersions) View.VISIBLE else View.GONE
    }

    /** Replaces the Watch tab's lines. */
    fun setWhere(lines: List<String>) { whereLines = lines; if (selected == Tab.WATCH) render() }

    // ---------------------------------------------------------------------------------------------

    private fun hideThen(run: () -> Unit) { sheet.hide(); run() }

    private fun select(tab: Tab) { if (selected != tab) { selected = tab; styleAllTabs(); render() } else if (content.childCount == 0) render() }

    private fun styleAllTabs() = tabViews.forEach { (t, v) -> styleTab(t, v.isFocused) }

    private fun styleTab(tab: Tab, focused: Boolean) {
        val v = tabViews[tab] ?: return
        val isSel = tab == selected
        v.background = GradientDrawable().apply { cornerRadius = context.px(19).toFloat(); setColor(if (focused) ivory else if (isSel) pillRest else 0) }
        v.setTextColor(if (focused) ink else if (isSel) ivory else dim)
        v.typeface = Typeface.create("sans-serif-medium", if (isSel) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun render() {
        content.removeAllViews()
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(200).setInterpolator(com.example.tvlauncher.design.Motion.SETTLE).start()
        scroll.scrollTo(0, 0)
        scroll.isFocusable = false
        val p = page
        when (selected) {
            Tab.OVERVIEW -> {
                val story = p?.synopsis?.withoutEmDashes() ?: "No synopsis on Letterboxd yet."
                content.addView(Type.reading(Type.text(context, story, Type.Style.BODY)))
            }
            Tab.CAST -> grid(p?.cast?.take(24)?.map { it.name to it.role?.takeUnless { r -> r.equals("Self", true) } }.orEmpty(), "No cast listed.")
            Tab.CREW -> {
                val groups = p?.crew.orEmpty()
                if (groups.isEmpty()) content.addView(Type.text(context, "No crew listed.", Type.Style.CAPTION))
                groups.forEach { g -> content.addView(row(g.role, g.names.joinToString(", "))) }
            }
            Tab.DETAILS -> {
                val rows = listOfNotNull(
                    p?.runtimeMin?.let { "Runtime" to "$it minutes" },
                    p?.studios?.takeIf { it.isNotEmpty() }?.let { "Studio" to it.joinToString(", ") },
                    p?.countries?.takeIf { it.isNotEmpty() }?.let { "Country" to it.joinToString(", ") },
                    p?.languages?.takeIf { it.isNotEmpty() }?.let { "Language" to it.joinToString(", ") },
                    p?.themes?.takeIf { it.isNotEmpty() }?.let { "Themes" to it.joinToString("; ") }
                )
                if (rows.isEmpty()) content.addView(Type.text(context, "No details listed.", Type.Style.CAPTION)) else rows.forEach { (k, v) -> content.addView(row(k, v)) }
            }
            Tab.WATCH -> {
                if (whereLines.isEmpty()) content.addView(Type.text(context, "Checking where to watch…", Type.Style.CAPTION))
                whereLines.forEach { content.addView(Type.text(context, it, Type.Style.BODY).apply { setPadding(0, 0, 0, dimen(R.dimen.space_2)) }) }
            }
        }
        updateScrollFocus()
    }

    /** Called once the body is laid out: it only becomes a focus stop if it actually overflows. */
    private fun updateScrollFocus() { scroll.post { scroll.isFocusable = content.height > scroll.height } }

    private fun grid(items: List<Pair<String, String?>>, empty: String) {
        if (items.isEmpty()) { content.addView(Type.text(context, empty, Type.Style.CAPTION)); return }
        items.chunked(2).forEach { pair ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (name, role) ->
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                cell.addView(Type.text(context, name, Type.Style.BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                cell.addView(Type.text(context, role?.let { "as $it" } ?: " ", Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                line.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dimen(R.dimen.space_3) })
            }
            if (pair.size == 1) line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            content.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dimen(R.dimen.space_2) })
        }
    }

    private fun row(label: String, value: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Type.text(context, label, Type.Style.EYEBROW), LinearLayout.LayoutParams(context.px(150), -2).apply { topMargin = dimen(R.dimen.space_1); marginEnd = dimen(R.dimen.space_3) })
        addView(Type.reading(Type.text(context, value, Type.Style.BODY)), LinearLayout.LayoutParams(0, -2, 1f))
        setPadding(0, 0, 0, dimen(R.dimen.space_2))
    }

    private fun playText(label: String, sub: String?): CharSequence =
        if (sub == null) label else android.text.SpannableString("$label\n$sub").apply {
            setSpan(android.text.style.RelativeSizeSpan(0.74f), label.length + 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.StyleSpan(Typeface.NORMAL), label.length + 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** A pill button: [accent] is the terracotta PLAY, [quiet] the small secondary row. Focus = solid ivory, no scaling. */
    private fun pill(label: String, sub: String? = null, accent: Boolean = false, quiet: Boolean = false): TextView {
        val rest = if (quiet) GradientDrawable().apply { cornerRadius = 999f; setColor(0) }
            else LauncherTheme.surface(context, if (accent) R.color.action else R.color.surface_selected, R.dimen.radius_pill)
        val lit = LauncherTheme.surface(context, R.color.focus, R.dimen.radius_pill)
        val restText = if (quiet) dim else ivory
        val fade = android.graphics.drawable.TransitionDrawable(arrayOf(rest, lit)).apply { isCrossFadeEnabled = true }
        return Type.text(context, "", Type.Style.BODY, restText).apply {
            text = playText(label, sub)
            gravity = if (quiet) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
            isFocusable = true; isClickable = true
            setPadding(dimen(R.dimen.space_4), 0, dimen(R.dimen.space_4), 0)
            background = fade
            setOnFocusChangeListener { v, focused ->
                if (focused) fade.startTransition(com.example.tvlauncher.design.Motion.FOCUS_IN_MS.toInt()) else fade.reverseTransition(com.example.tvlauncher.design.Motion.FOCUS_OUT_MS.toInt())
                com.example.tvlauncher.design.Motion.tweenTextColor(v as TextView, if (focused) ink else restText)
            }
        }
    }

    private fun dimen(res: Int) = context.resources.getDimensionPixelSize(res)

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false, family: String = "sans-serif-medium", italic: Boolean = false) = TextView(context).apply {
        text = value; textSize = sp; setTextColor(color)
        typeface = Typeface.create(family, if (bold && italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else if (italic) Typeface.ITALIC else Typeface.NORMAL)
    }
}
