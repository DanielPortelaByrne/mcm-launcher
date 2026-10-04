package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.tvlauncher.data.parents.FeedImages
import com.example.tvlauncher.data.parents.ForAmelia
import com.example.tvlauncher.data.parents.Pick
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.SectionTheme
import com.example.tvlauncher.design.Type

/**
 * "Para Amélia": a printed Brazilian TV guide laid out across the page, one column per programme -- live
 * Brazilian TV, her programme (Domingo Legal), the newest novela scene, an action film. Left and Right
 * move along it, one press opens the thing itself, and Down leaves straight away. Portuguese, because it
 * is hers.
 */
class AmeliaSection(private val activity: Activity, private val images: FeedImages) {
    val section = Kit.Section(activity, "Para Amélia", "Ao vivo, programas e um filme", SectionTheme.Mood.EVENING)

    private val guide = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val columns = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val streak = Kit.line(activity, "", Type.Style.CAPTION, Kit.PAPER_INK_DIM)
    private val guideMat = LauncherTheme.imageMat(activity, Kit.dpf(activity, 10f)).apply { alpha = 0 }
    private val items = mutableListOf<View>()
    private var shownKey: String? = null

    private class Programme(val label: String, val pick: Pick, val title: String, val sub: String?)

    init {
        guide.background = Kit.framed(activity)
        guide.setPadding(Kit.dp(activity, 22), Kit.dp(activity, 16), Kit.dp(activity, 22), Kit.dp(activity, 16))
        guide.elevation = Kit.dpf(activity, 6f)
        guide.foreground = guideMat
        val head = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Type.text(activity, "Guia de TV", Type.Style.TITLE, Kit.PAPER_INK).apply { typeface = Typeface.create("serif", Typeface.BOLD_ITALIC) })
        head.addView(Type.text(activity, "Hoje", Type.Style.EYEBROW, Kit.TERRACOTTA), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Kit.dp(activity, 16) })
        head.addView(streak)
        guide.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Kit.dp(activity, 10) })
        guide.addView(columns, LinearLayout.LayoutParams(-1, -2))
        section.body.addView(guide, LinearLayout.LayoutParams(-1, -2))
    }

    fun bind(a: ForAmelia?) {
        val programmes = listOfNotNull(
            a?.live?.firstOrNull()?.let { Programme("Ao vivo", it, "${it.title} ao vivo", "Agora, do Brasil") },
            a?.shows?.firstOrNull()?.let { Programme("Programa", it, it.title, it.subtitle) },
            a?.novelas?.firstOrNull()?.let { Programme("Novela", it, it.title, it.subtitle) },
            a?.film?.let { Programme("Filme", it, it.title + (it.year?.let { y -> " ($y)" } ?: ""), "Ação e drama") }
        )
        if (programmes.isEmpty()) { section.shown = false; return }
        section.shown = true
        streak.text = a?.duolingoStreak?.let {
            "Duolingo · ${java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("pt-BR")).format(it)} dias 🔥"
        }.orEmpty()
        val key = programmes.joinToString { it.pick.link.uri }
        if (key == shownKey) return
        val focused = items.indexOfFirst { it.isFocused }
        shownKey = key
        columns.removeAllViews(); items.clear()
        programmes.forEachIndexed { i, p ->
            columns.addView(column(p), LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = Kit.dp(activity, 12) })
        }
        Kit.trapEdges(items)
        if (focused >= 0) items.getOrNull(focused)?.requestFocus()
    }

    private fun column(p: Programme): View {
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = Kit.dpf(activity, 6f); setColor(0x22261E14) }
        }
        Kit.rounded(image, Kit.dpf(activity, 6f))
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = Kit.dp(activity, 8); setPadding(pad, pad, pad, Kit.dp(activity, 10))
            isFocusable = true; isClickable = true
            contentDescription = "${p.label}: ${p.title}"
        }
        col.addView(image, LinearLayout.LayoutParams(-1, Kit.dp(activity, 100)))
        col.addView(Kit.line(activity, p.label.uppercase(java.util.Locale.forLanguageTag("pt-BR")), Type.Style.EYEBROW, Kit.TERRACOTTA), LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 8) })
        col.addView(Kit.line(activity, p.title, Type.Style.HEADING, Kit.PAPER_INK).apply { textSize = 18f })
        col.addView(Kit.line(activity, p.sub.orEmpty(), Type.Style.CAPTION, Kit.PAPER_INK_DIM).apply { textSize = 14f })
        Kit.paperRowFocus(col, guide, guideMat, { items })
        col.setOnClickListener { Opener.open(activity, p.pick.link, p.title) }
        p.pick.image?.let { url -> images.load(url, Kit.dp(activity, 190), Kit.dp(activity, 100)) { it?.let { b -> image.setImageBitmap(b) } } }
        items += col
        return col
    }
}
