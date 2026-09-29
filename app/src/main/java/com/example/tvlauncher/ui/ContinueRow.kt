package com.example.tvlauncher.ui

import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvlauncher.R
import com.example.tvlauncher.data.AppEntry
import com.example.tvlauncher.data.ContinueWatching
import com.example.tvlauncher.data.ResumeItem
import com.example.tvlauncher.design.LauncherTheme
import java.util.concurrent.Executors

/**
 * "Continue watching": one card per part-watched title, with the app's own still, a progress bar,
 * the time left and the app it belongs to. OK opens the app's deep link, which is how the app is
 * asked to resume. The section hides itself when no app has published anything.
 */
class ContinueRow(
    private val section: View,
    private val container: LinearLayout,
    private val source: ContinueWatching,
    private val launch: (ResumeItem) -> Unit
) {
    /** Long-press removes a card, for things you have finished with. */
    var onRemove: ((ResumeItem) -> Unit)? = null

    private val context = container.context
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var shown: List<ResumeItem> = emptyList()
    private val youtube = com.example.tvlauncher.data.YouTubeSearch(context)
    private val searched = HashSet<String>()

    fun refresh(apps: List<AppEntry>) {
        lastApps = apps
        worker.execute {
            val items = source.load()
            handler.post { render(items, apps) }
            backfillYouTubeThumbnails(items)
        }
    }

    private var lastApps: List<AppEntry> = emptyList()
    private fun refreshNow() { shown = emptyList(); refresh(lastApps) }

    /**
     * YouTube-family cards saved without a picture get one by searching the title (needs the API key on the TV).
     * Each title is tried once per launch, and a picture already saved is never replaced.
     */
    private fun backfillYouTubeThumbnails(items: List<ResumeItem>) {
        if (!youtube.hasKey) return
        var changed = false
        for (item in items) {
            val isYouTube = item.packageName == "org.smarttube.stable" || item.packageName.startsWith("com.google.android.youtube")
            val complete = item.imageUri != null && com.example.tvlauncher.data.ResumeLinks.youtubeId(item.mediaId) != null
            if (!isYouTube || complete || !searched.add(item.packageName + item.title)) continue
            val match = youtube.find(item.title, item.subtitle?.substringBefore(" \u2022 ")) ?: continue
            val bmp = if (item.imageUri == null) com.example.tvlauncher.data.Thumbnails.download(listOf(match.thumbnailUrl)) else null
            source.save(item.copy(mediaId = listOfNotNull("youtube:${match.id}", item.mediaId).joinToString("\n")), bmp)
            changed = true
        }
        if (changed) handler.post { shown = emptyList(); refresh(lastApps) }
    }

    fun close() { worker.shutdownNow() }

    private fun render(items: List<ResumeItem>, apps: List<AppEntry>) {
        val installed = apps.associateBy { it.packageName }
        val usable = items.filter { it.packageName in installed || it.packageName == context.packageName }
        if (usable.map { it.title + it.positionMs + it.imageUri } == shown.map { it.title + it.positionMs + it.imageUri }) return
        shown = usable
        container.removeAllViews()
        section.visibility = if (usable.isEmpty()) View.GONE else View.VISIBLE
        usable.forEachIndexed { i, item -> container.addView(card(item, installed[item.packageName]), LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = px(R.dimen.card_gutter) }) }
        trapEdgesOf(container)
    }

    /**
     * One card, on the same pattern as the painting shelf: a 16:9 still with the progress bar along its
     * foot and the app's badge in the corner, the title beneath. Focus is the image-card mat.
     */
    private fun card(item: ResumeItem, app: AppEntry?): View {
        val radius = px(R.dimen.radius_small).toFloat()
        val width = px(R.dimen.card_column)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true
            clipChildren = false; clipToPadding = false
            contentDescription = "Continue ${item.title}"
        }
        val frame = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) }
            }
            // No artwork from the app: a warm panel with the app's icon, instead of an empty dark box.
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(context.getColor(R.color.walnut), context.getColor(R.color.surface_raised)))
        }
        val still = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; colorFilter = com.example.tvlauncher.design.Motion.printFilter(0f) }
        frame.addView(still, FrameLayout.LayoutParams(-1, -1))
        val placeholder = ImageView(context).apply {
            app?.icon?.let { setImageDrawable(com.example.tvlauncher.design.RoundIcon.own(context, it)) } ?: setImageResource(R.drawable.ic_launcher)
        }
        frame.addView(placeholder, FrameLayout.LayoutParams(px(R.dimen.space_7), px(R.dimen.space_7), Gravity.CENTER))
        if (item.progress > 0f) {
            val bar = px(R.dimen.space_1)
            frame.addView(View(context).apply { setBackgroundColor(0x66000000) }, FrameLayout.LayoutParams(-1, bar, Gravity.BOTTOM))
            frame.addView(View(context).apply { setBackgroundColor(context.getColor(R.color.accent)) },
                FrameLayout.LayoutParams((width * item.progress).toInt().coerceAtLeast(bar), bar, Gravity.BOTTOM or Gravity.START))
        }
        app?.icon?.let { icon ->
            val badge = px(R.dimen.space_4)
            frame.addView(ImageView(context).apply { setImageDrawable(com.example.tvlauncher.design.RoundIcon.own(context, icon)) },
                FrameLayout.LayoutParams(badge, badge, Gravity.TOP or Gravity.START).apply { topMargin = px(R.dimen.space_2); marginStart = px(R.dimen.space_2) })
        }
        val mat = LauncherTheme.imageMat(context, radius).apply { alpha = 0 }
        frame.foreground = mat
        root.addView(frame, LinearLayout.LayoutParams(width, width * 9 / 16))
        val titleText = com.example.tvlauncher.design.Type.onPainting(com.example.tvlauncher.design.Type.text(context, item.title, com.example.tvlauncher.design.Type.Style.BODY)).apply {
            maxLines = 1; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            alpha = com.example.tvlauncher.design.Motion.CAPTION_REST_ALPHA
        }
        root.addView(titleText, LinearLayout.LayoutParams(width, -2).apply { topMargin = px(R.dimen.space_2) })

        root.setOnFocusChangeListener { v, hasFocus ->
            LauncherTheme.fadeDrawable(frame, mat, hasFocus)
            com.example.tvlauncher.design.Motion.focusImageCard(frame, still, titleText, hasFocus)
            com.example.tvlauncher.design.Motion.printFocus(still, hasFocus)
            scrollFocusIntoView(v, hasFocus)
            if (hasFocus) PageHint.show(context.getString(R.string.hint_remove_card))
        }
        root.setOnClickListener { com.example.tvlauncher.design.Motion.press(frame, com.example.tvlauncher.design.Motion.CARD_SCALE); launch(item) }
        root.setOnLongClickListener { source.remove(item); refreshNow(); true }

        item.imageUri?.let { uri -> worker.execute { source.image(uri)?.let { bmp -> handler.post { still.setImageBitmap(bmp); placeholder.visibility = View.GONE } } } }
        return root
    }

    private fun label(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = t; textSize = sp; setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun trapEdgesOf(row: LinearLayout) {
        val n = row.childCount
        for (i in 0 until n) row.getChildAt(i).setOnKeyListener { _, code, ev ->
            ev.action == android.view.KeyEvent.ACTION_DOWN &&
                ((code == android.view.KeyEvent.KEYCODE_DPAD_LEFT && i == 0) || (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && i == n - 1))
        }
    }

    private fun px(res: Int) = context.resources.getDimensionPixelSize(res)
}
