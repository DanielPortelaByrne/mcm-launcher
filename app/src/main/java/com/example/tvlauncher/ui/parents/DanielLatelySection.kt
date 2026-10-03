package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.example.tvlauncher.data.parents.DanielLatelyArrangement
import com.example.tvlauncher.data.parents.FeedImages
import com.example.tvlauncher.data.parents.ParentsFormat
import com.example.tvlauncher.data.parents.Photo
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.SectionTheme
import com.example.tvlauncher.design.Type

/**
 * "Daniel, lately": loose photo prints on the page. One large print (the newest, or the day's pick when
 * nothing is new) and up to three smaller ones beside it; under them, one caption that follows the focused
 * print. Nothing moves on its own. OK opens the photographs full screen.
 */
class DanielLatelySection(private val activity: Activity, private val images: FeedImages, private val viewer: PhotoViewer) {
    val section = Kit.Section(activity, "Daniel, lately", null, SectionTheme.Mood.EVENING)

    private val leadBox = FrameLayout(activity).apply { clipChildren = false; clipToPadding = false }
    private val smallRow = LinearLayout(activity).row(activity)
    private val title = Type.onPainting(Kit.line(activity, "", Type.Style.HEADING, lines = 2))
    private val detail = Type.onPainting(Kit.line(activity, "", Type.Style.CAPTION))
    private val empty = Type.onPainting(Kit.line(activity, "New photos from Daniel will appear here.", Type.Style.BODY, lines = 2))
    private var shownKey: String? = null
    private var arranged: DanielLatelyArrangement.Arranged? = null

    private val row = LinearLayout(activity).row(activity)

    init {
        row.addView(leadBox, LinearLayout.LayoutParams(-2, Kit.dp(activity, LEAD_H + 24)))
        val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        right.addView(smallRow, LinearLayout.LayoutParams(-2, Kit.dp(activity, SMALL_H + 24)))
        right.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 12) })
        right.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Kit.dp(activity, 4) })
        row.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Kit.dp(activity, 28) })
        section.body.addView(row, LinearLayout.LayoutParams(-1, -2))
        section.body.addView(empty)
        empty.visibility = View.GONE
    }

    /** [configured]: the TV has a feed address, so an empty album means "no photos yet", not "offline". */
    fun bind(photos: List<Photo>?, configured: Boolean, now: Long) {
        if (photos == null || (photos.isEmpty() && !configured)) { section.shown = false; return }
        section.shown = true
        val a = DanielLatelyArrangement.arrange(photos, now)
        arranged = a
        row.visibility = if (a == null) View.GONE else View.VISIBLE
        if (a == null) {
            section.subtitle(null); empty.visibility = View.VISIBLE
            leadBox.removeAllViews(); smallRow.removeAllViews(); title.text = ""; detail.text = ""
            shownKey = null
            return
        }
        empty.visibility = View.GONE
        section.subtitle(listOfNotNull(
            "${a.all.size} photo${if (a.all.size == 1) "" else "s"}",
            ParentsFormat.addedWhen(photos.first().firstSeenAt, now)?.let { "last added $it" }
        ).joinToString(" · "))
        val key = (listOf(a.lead) + a.others).joinToString { it.id }
        if (key == shownKey) return
        val hadFocus = section.view.findFocus() != null
        shownKey = key
        build(a, now)
        if (hadFocus) leadBox.getChildAt(0)?.requestFocus()
    }

    private fun build(a: DanielLatelyArrangement.Arranged, now: Long) {
        leadBox.removeAllViews(); smallRow.removeAllViews()
        val prints = mutableListOf<View>()
        fun add(photo: Photo, parent: android.view.ViewGroup, heightDp: Int, tilt: Float, position: Int) {
            val aspect = photo.aspect.coerceIn(0.66f, 1.6f)
            val border = if (heightDp > 200) 9 else 6
            val h = Kit.dp(activity, heightDp)
            val w = (Kit.dp(activity, heightDp - border * 2) * aspect).toInt() + Kit.dp(activity, border * 2)
            val print = Kit.Print(activity, tilt, border)
            print.frame.contentDescription = photo.caption ?: "Photo from Daniel"
            print.bindFocus(null) { focused -> if (focused) describe(photo, position, now) }
            print.frame.setOnClickListener { Motion.press(print.frame, Motion.CARD_SCALE); open(position, print.frame) }
            val lp = if (parent is FrameLayout) FrameLayout.LayoutParams(w, h, Gravity.CENTER_VERTICAL)
                else LinearLayout.LayoutParams(w, h).apply { gravity = Gravity.CENTER_VERTICAL; if (parent.childCount > 0) marginStart = Kit.dp(activity, 18) }
            parent.addView(print.frame, lp)
            prints += print.frame
            images.load(photo.thumbUrl, w, h) { print.set(it) }
        }
        add(a.lead, leadBox, LEAD_H, -1.6f, 0)
        a.others.forEachIndexed { i, p -> add(p, smallRow, SMALL_H, TILTS[i % TILTS.size], i + 1) }
        Kit.trapEdges(prints)
        describe(a.lead, 0, now)
    }

    private fun describe(photo: Photo, position: Int, now: Long) {
        val total = arranged?.all?.size ?: 0
        Motion.swapText(title, photo.caption ?: ParentsFormat.longDate(photo.takenAt) ?: "From Daniel")
        val added = ParentsFormat.addedWhen(photo.firstSeenAt, now)?.let { "Added $it" }
        val taken = if (photo.caption != null) ParentsFormat.longDate(photo.takenAt) else null
        Motion.swapText(detail, listOfNotNull(taken, added, if (total > 1) "${position + 1} of $total" else null).joinToString("   ·   "))
    }

    private fun open(position: Int, from: View) {
        val all = arranged?.all ?: return
        viewer.show(all.map { PhotoViewer.Slide(it.imageUrl, it.thumbUrl, it.caption, ParentsFormat.longDate(it.takenAt)) }, position, from)
    }

    private companion object {
        const val LEAD_H = 262
        const val SMALL_H = 128
        val TILTS = floatArrayOf(2.2f, -1.4f, 1.6f)
    }
}
