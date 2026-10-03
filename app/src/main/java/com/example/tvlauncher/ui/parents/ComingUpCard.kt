package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.tvlauncher.data.parents.FamilyEvent
import com.example.tvlauncher.data.parents.ParentsFormat
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.SectionTheme
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.room.InfoSheet
import com.example.tvlauncher.ui.scrollFocusIntoView

/**
 * "Coming up": a small paper calendar leaning in the hero, where the greeting is. Only family things
 * (visits, birthdays, weekends away), at most three lines, said the way you'd say them: "in 12 days",
 * "Saturday". With nothing coming up it is not there at all. OK lists everything.
 */
class ComingUpCard(private val activity: Activity, private val sheet: InfoSheet) {
    private val lines = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val month = Kit.line(activity, "", Type.Style.EYEBROW, 0xFFF3E7C6.toInt())
    private var events: List<FamilyEvent> = emptyList()
    private var now = 0L

    val view: View = FrameLayout(activity).apply {
        isFocusable = true; isClickable = true; clipChildren = false
        contentDescription = "Coming up"
        visibility = View.GONE
    }

    init {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Kit.paper(activity, 8)
            elevation = Kit.dpf(activity, 6f)
            rotation = 1.2f
        }
        // The calendar's binding strip: terracotta, with the month printed on it.
        val strip = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                val r = Kit.dpf(activity, 8f); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setColor(Kit.TERRACOTTA)
            }
            setPadding(Kit.dp(activity, 20), 0, Kit.dp(activity, 20), 0)
            addView(Kit.line(activity, "Coming up", Type.Style.EYEBROW, 0xFFF3E7C6.toInt()), FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START))
            addView(month, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END))
        }
        card.addView(strip, LinearLayout.LayoutParams(-1, Kit.dp(activity, 34)))
        lines.setPadding(Kit.dp(activity, 20), Kit.dp(activity, 10), Kit.dp(activity, 20), Kit.dp(activity, 12))
        card.addView(lines, LinearLayout.LayoutParams(-1, -2))
        (view as FrameLayout).addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val ring = LauncherTheme.outsetRing(card, Kit.dpf(activity, 8f), Kit.dp(activity, 5))
        view.setOnFocusChangeListener { v, focused ->
            LauncherTheme.fadeDrawable(card, ring, focused)
            LauncherTheme.animateFocus(v, focused, 1.04f)
            card.animate().rotation(if (focused) 0f else 1.2f).setDuration(if (focused) Motion.FOCUS_IN_MS else Motion.FOCUS_OUT_MS).setInterpolator(Motion.SETTLE).start()
            scrollFocusIntoView(v, focused)
        }
        view.setOnClickListener { showAll() }
        SectionTheme.tag(view, SectionTheme.Mood.EVENING)
    }

    fun bind(all: List<FamilyEvent>, now: Long) {
        this.now = now
        events = ParentsFormat.upcoming(all, now)
        if (events.isEmpty()) {
            if (view.isFocused) view.focusSearch(View.FOCUS_DOWN)?.requestFocus()
            view.visibility = View.GONE; return
        }
        view.visibility = View.VISIBLE
        month.text = java.text.SimpleDateFormat("MMMM", java.util.Locale.UK).apply { timeZone = ParentsFormat.IRELAND }.format(java.util.Date(now))
        lines.removeAllViews()
        events.take(3).forEachIndexed { i, e ->
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(Kit.line(activity, ParentsFormat.eventWhen(e, now).orEmpty(), Type.Style.CAPTION, Kit.TERRACOTTA).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(Kit.dp(activity, 104), -2))
            row.addView(Kit.line(activity, e.title, Type.Style.HEADING, Kit.PAPER_INK).apply { textSize = 19f }, LinearLayout.LayoutParams(0, -2, 1f))
            lines.addView(row, LinearLayout.LayoutParams(-1, Kit.dp(activity, 40)).apply { if (i > 0) topMargin = Kit.dp(activity, 2) })
            if (i < minOf(events.size, 3) - 1) lines.addView(View(activity).apply { setBackgroundColor(0x33261E14) }, LinearLayout.LayoutParams(-1, Kit.dp(activity, 1)))
        }
    }

    private fun showAll() = sheet.show(
        "Coming up", "What's next for the family", null,
        events.map { e -> "${ParentsFormat.eventWhen(e, now)?.replaceFirstChar { it.uppercase() }}  ·  ${e.title}" }
    )
}
