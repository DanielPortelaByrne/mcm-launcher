package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.tvlauncher.data.parents.FeedImages
import com.example.tvlauncher.data.parents.ForAmelia
import com.example.tvlauncher.data.parents.Pick
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.SectionTheme
import com.example.tvlauncher.design.Type

/**
 * "Para Amélia": a printed Brazilian TV guide with a little screen beside it. Each line is one press
 * from the thing itself: live Brazilian TV (official channels' own live streams), the newest novela
 * clips, an action film, and Ária's programme for the daytime. Portuguese, because it is hers.
 */
class AmeliaSection(private val activity: Activity, private val images: FeedImages, private val aria: () -> Pick?) {
    val section = Kit.Section(activity, "Para Amélia", "Ao vivo, programas e um filme", SectionTheme.Mood.EVENING)

    private val guide = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val rowsBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val streak = Kit.line(activity, "", Type.Style.CAPTION, Kit.PAPER_INK_DIM)
    private val screen = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF1A140E.toInt()) }
    /** The line the little screen is showing (the guide itself already says what it is). */
    private var tuned: String? = null
    private val guideMat = LauncherTheme.imageMat(activity, Kit.dpf(activity, 10f)).apply { alpha = 0 }
    private val rows = mutableListOf<View>()
    private var shownKey: String? = null

    private class Line(val label: String, val pick: Pick, val title: String, val sub: String?)

    init {
        guide.background = Kit.framed(activity)
        guide.setPadding(Kit.dp(activity, 26), Kit.dp(activity, 20), Kit.dp(activity, 26), Kit.dp(activity, 16))
        guide.elevation = Kit.dpf(activity, 6f)
        guide.foreground = guideMat
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Type.text(activity, "Guia de TV", Type.Style.TITLE, Kit.PAPER_INK).apply { typeface = Typeface.create("serif", Typeface.BOLD_ITALIC) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Type.text(activity, "Hoje", Type.Style.EYEBROW, Kit.TERRACOTTA))
        guide.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Kit.dp(activity, 6) })
        guide.addView(rowsBox, LinearLayout.LayoutParams(-1, -2))
        guide.addView(streak, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 8); marginStart = Kit.dp(activity, 16) })

        // The little screen: a walnut bezel round whatever the focused line is.
        val tv = FrameLayout(activity).apply {
            background = LayerDrawable(arrayOf(GradientDrawable().apply { cornerRadius = Kit.dpf(activity, 18f); setColor(Kit.WALNUT_FRAME) }))
            val b = Kit.dp(activity, 12); setPadding(b, b, b, b)
            elevation = Kit.dpf(activity, 6f)
        }
        Kit.rounded(screen, Kit.dpf(activity, 8f))
        tv.addView(screen, FrameLayout.LayoutParams(-1, -1))

        val row = LinearLayout(activity).row(activity)
        row.addView(guide, LinearLayout.LayoutParams(Kit.dp(activity, 520), -2))
        row.addView(tv, LinearLayout.LayoutParams(Kit.dp(activity, 320), Kit.dp(activity, 196)).apply { marginStart = Kit.dp(activity, 24); topMargin = Kit.dp(activity, 18) })
        section.body.addView(row, LinearLayout.LayoutParams(-1, -2))
    }

    fun bind(a: ForAmelia?) {
        val lines = mutableListOf<Line>()
        a?.live?.firstOrNull()?.let { lines += Line("Ao vivo", it, it.title, "Agora, do Brasil") }
        a?.shows?.firstOrNull()?.let { lines += Line("Programa", it, it.title, it.subtitle) }
        a?.novelas?.firstOrNull()?.let { lines += Line("Novelas", it, it.title, it.subtitle) }
        a?.film?.let { lines += Line("Filme", it, it.title + (it.year?.let { y -> " ($y)" } ?: ""), "Ação e drama para hoje à noite") }
        aria()?.let { lines += Line("Para a Ária", it, it.title, it.subtitle) }
        if (lines.isEmpty()) { section.shown = false; return }
        section.shown = true
        streak.text = a?.duolingoStreak?.let { "Duolingo · ofensiva de ${java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("pt-BR")).format(it)} dias 🔥" }.orEmpty()
        streak.visibility = if (streak.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        val key = lines.joinToString { it.pick.link.uri }
        if (key == shownKey) return
        val focusedIndex = rows.indexOfFirst { it.isFocused }
        shownKey = key
        rowsBox.removeAllViews(); rows.clear()
        lines.forEach { line -> rowsBox.addView(buildRow(line), LinearLayout.LayoutParams(-1, Kit.dp(activity, 60))) }
        // The guide is a column: Left and Right have nowhere to go, so they stay on the line.
        rows.forEach { r -> r.setOnKeyListener { _, keyCode, event ->
            event.action == android.view.KeyEvent.ACTION_DOWN && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        } }
        if (focusedIndex >= 0) rows.getOrNull(focusedIndex)?.requestFocus()
        tune(lines.first())
    }

    private fun buildRow(line: Line): View {
        val label = Kit.line(activity, line.label.uppercase(java.util.Locale.forLanguageTag("pt-BR")), Type.Style.EYEBROW, Kit.TERRACOTTA)
        val title = Kit.line(activity, line.title, Type.Style.HEADING, Kit.PAPER_INK)
        val sub = Kit.line(activity, line.sub.orEmpty(), Type.Style.CAPTION, Kit.PAPER_INK_DIM).apply { visibility = if (line.sub.isNullOrBlank()) View.GONE else View.VISIBLE }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; addView(title); addView(sub) }
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(Kit.dp(activity, 16), 0, Kit.dp(activity, 16), 0)
            addView(label, LinearLayout.LayoutParams(Kit.dp(activity, 132), -2))
            addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            contentDescription = "${line.label}: ${line.title}"
        }
        Kit.paperRowFocus(row, guide, guideMat, { rows }) { focused -> if (focused) tune(line) }
        row.setOnClickListener { Opener.open(activity, line.pick.link, line.title) }
        rows += row
        return row
    }

    /** Puts the focused line on the little screen. */
    private fun tune(line: Line) {
        tuned = line.label
        val url = line.pick.image ?: return
        images.load(url, Kit.dp(activity, 296), Kit.dp(activity, 172)) { bmp -> if (bmp != null && tuned == line.label) Motion.swapImage(screen) { screen.setImageBitmap(bmp) } }
    }
}
