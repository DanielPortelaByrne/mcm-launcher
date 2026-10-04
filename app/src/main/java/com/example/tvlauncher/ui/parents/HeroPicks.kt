package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.tvlauncher.data.parents.FeedImages
import com.example.tvlauncher.data.parents.Pick
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.SectionTheme
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.scrollFocusIntoView

/**
 * Two things ready to go, beside the greeting: one for Amélia (her programme, or Brazilian TV live) and
 * one for Padraig (a record for the day). Shown whenever the calendar has nothing to say, so the first
 * screen always offers each of them something one press away.
 */
class HeroPicks(private val activity: Activity, private val images: FeedImages) {
    data class Card(val eyebrow: String, val pick: Pick, val title: String, val sub: String?, val square: Boolean)

    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        visibility = View.GONE
        SectionTheme.tag(this, SectionTheme.Mood.EVENING)
    }
    private var shownKey: String? = null

    fun bind(cards: List<Card>) {
        if (cards.isEmpty()) { view.visibility = View.GONE; return }
        view.visibility = View.VISIBLE
        val key = cards.joinToString { it.pick.link.uri + it.title }
        if (key == shownKey) return
        val hadFocus = view.findFocus() != null
        shownKey = key
        view.removeAllViews()
        cards.take(2).forEachIndexed { i, c ->
            view.addView(card(c), LinearLayout.LayoutParams(-1, Kit.dp(activity, 88)).apply { if (i > 0) topMargin = Kit.dp(activity, 12) })
        }
        if (hadFocus) view.getChildAt(0)?.requestFocus()
    }

    private fun card(c: Card): View {
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = Kit.dpf(activity, 8f); setColor(0x33F3E9CF) }
        }
        Kit.rounded(image, Kit.dpf(activity, 8f))
        val w = if (c.square) 68 else 120
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            val p = Kit.dp(activity, 10); setPadding(p, p, Kit.dp(activity, 18), p)
            isFocusable = true; isClickable = true
            contentDescription = "${c.eyebrow}: ${c.title}"
        }
        row.addView(image, LinearLayout.LayoutParams(Kit.dp(activity, w), Kit.dp(activity, 68)).apply { marginEnd = Kit.dp(activity, 16) })
        val words = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        words.addView(Kit.line(activity, c.eyebrow, Type.Style.EYEBROW))
        words.addView(Kit.line(activity, c.title, Type.Style.HEADING).apply { textSize = 19f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 2) })
        c.sub?.let { words.addView(Kit.line(activity, it, Type.Style.CAPTION)) }
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        val radius = Kit.dpf(activity, 20f)
        LauncherTheme.bindPanelFocus(row, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius), 1.03f) { scrollFocusIntoView(row, it) }
        row.setOnClickListener { Motion.press(row, 1.03f); Opener.open(activity, c.pick.link, c.title) }
        c.pick.image?.let { url -> images.load(url, Kit.dp(activity, w), Kit.dp(activity, 68)) { it?.let { b -> image.setImageBitmap(b) } } }
        return row
    }
}
