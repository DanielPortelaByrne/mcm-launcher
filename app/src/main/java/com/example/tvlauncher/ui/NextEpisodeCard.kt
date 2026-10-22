package com.example.tvlauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvlauncher.R
import com.example.tvlauncher.data.jellyfin.JellyfinItem
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.Type
import kotlin.math.ceil

/** A TV-safe end card. Only the small ring redraws each frame; artwork and text stay still. */
class NextEpisodeCard(context: Context, play: () -> Unit, cancel: () -> Unit) : FrameLayout(context) {
    val artwork = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = LauncherTheme.surface(context, R.color.surface_raised, R.dimen.radius_small)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val episodeTitle = Type.text(context, "", Type.Style.HEADING).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END
    }
    private val seriesTitle = Type.text(context, "", Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val episodeMeta = Type.text(context, "", Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val ring = CountdownRing(context)
    private val playLabel = Type.text(context, "Play next", Type.Style.BODY)
    private val primary = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(px(12), 0, px(20), 0)
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        addView(ring, LinearLayout.LayoutParams(px(48), px(48)))
        addView(playLabel, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })
        setOnClickListener { play() }
    }

    init {
        visibility = View.GONE
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00000000, 0x991A140E.toInt()))
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
            background = LauncherTheme.surface(context, R.color.surface_solid)
            elevation = px(12).toFloat()
        }
        card.addView(Type.text(context, "UP NEXT", Type.Style.EYEBROW))
        card.addView(artwork, LinearLayout.LayoutParams(-1, px(136)).apply { topMargin = px(12) })
        val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        details.addView(episodeTitle, LinearLayout.LayoutParams(-1, -2))
        details.addView(seriesTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        details.addView(episodeMeta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(4) })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
        }
        actions.addView(primary, LinearLayout.LayoutParams(-1, px(52)))
        val stay = Type.text(context, "Stay here", Type.Style.BODY).apply {
            gravity = Gravity.CENTER; setPadding(px(20), 0, px(20), 0)
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            setOnClickListener { cancel() }
        }
        actions.addView(stay, LinearLayout.LayoutParams(-1, px(44)).apply { topMargin = px(8) })
        details.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        card.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = px(12) })
        addView(card, LayoutParams(px(300), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = resources.getDimensionPixelSize(R.dimen.page_margin)
        })
        LauncherTheme.bindPanelFocus(primary, LauncherTheme.surface(context, R.color.surface_raised, R.dimen.radius_pill),
            LauncherTheme.surface(context, R.color.focus, R.dimen.radius_pill), scale = 1f) { focused ->
            val color = context.getColor(if (focused) R.color.on_focus else R.color.text)
            playLabel.setTextColor(color); ring.foregroundColor = color
        }
        LauncherTheme.bindPanelFocus(stay, LauncherTheme.surface(context, R.color.surface_raised, R.dimen.radius_pill),
            LauncherTheme.surface(context, R.color.focus, R.dimen.radius_pill), scale = 1f)
    }

    fun bind(item: JellyfinItem) {
        artwork.setImageDrawable(null)
        episodeTitle.text = item.name
        seriesTitle.text = item.seriesName
        seriesTitle.visibility = if (item.seriesName.isNullOrBlank()) View.GONE else View.VISIBLE
        episodeMeta.text = listOfNotNull(item.episodeLabel(), item.runtimeMin?.let { "$it min" }).joinToString(" · ")
    }

    fun showCard() {
        if (visibility == View.VISIBLE) return
        visibility = View.VISIBLE
        if (Motion.animationsOn(this)) {
            alpha = 0f; translationX = px(16).toFloat()
            animate().alpha(1f).translationX(0f).setDuration(Motion.SHEET_IN_MS).setInterpolator(Motion.SETTLE).start()
        }
        primary.requestFocus()
        post { if (visibility == View.VISIBLE) primary.requestFocus() }
    }

    fun countdown(remainingMs: Long?, fraction: Float) {
        val seconds = remainingMs?.let { ceil(it / 1000.0).toInt() }
        ring.update(fraction, seconds?.toString() ?: "▶")
        val description = if (seconds != null) "Play next episode, starts in $seconds seconds" else "Play next episode"
        if (primary.contentDescription != description) primary.contentDescription = description
    }

    fun hideCard() { animate().cancel(); visibility = View.GONE; alpha = 1f; translationX = 0f }
    private fun px(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}

private class CountdownRing(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private var fraction = 1f
    private var label = "3"
    var foregroundColor: Int = context.getColor(R.color.text)
        set(value) { field = value; invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun update(progress: Float, value: String) {
        fraction = progress.coerceIn(0f, 1f); label = value; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val inset = 5f * density
        arc.set(inset, inset, width - inset, height - inset)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * density; paint.strokeCap = Paint.Cap.ROUND
        paint.color = foregroundColor; paint.alpha = 45
        canvas.drawOval(arc, paint)
        paint.alpha = 255
        canvas.drawArc(arc, -90f, fraction * 360f, false, paint)
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        paint.textSize = resources.getDimension(R.dimen.text_body)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(label, width / 2f, height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
    }
}
