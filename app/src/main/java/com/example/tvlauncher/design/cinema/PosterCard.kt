package com.example.tvlauncher.design.cinema

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvlauncher.R
import com.example.tvlauncher.design.Cinema
import com.example.tvlauncher.design.Motion

/**
 * A Cinema poster card (`.card`): a rounded poster with its name and a quiet subline beneath.
 *
 * Focused, the whole card grows to 1.1 about a point a third of the way down the poster, a soft saffron glow blooms
 * behind the poster with a deeper shadow (no outline), the name turns cream and semibold and the subline
 * ("2026 · Animation") fades in. Sizes are in mockup pixels; [titled] false leaves the text out (the Top 10 row).
 * Parents must not clip children, as the glow and the grown card reach past the card's bounds.
 */
class PosterCard(context: Context, posterW: Int, posterH: Int, titled: Boolean = true, radiusPx: Float = 16f) : LinearLayout(context) {
    val poster: FrameLayout
    val image: ImageView
    val title: TextView?
    val subline: TextView?
    var onFocus: ((Boolean) -> Unit)? = null

    private val radius = Cinema.pxf(context, radiusPx)
    // No outlines in the Cinema language: focus is a soft saffron glow behind the poster, never a ring.
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cinema.alpha(Cinema.SAFFRON, 0.42f)
        if (android.os.Build.VERSION.SDK_INT >= 28) maskFilter = android.graphics.BlurMaskFilter(Cinema.pxf(context, 34), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val box = RectF()
    private val posterHeight = Cinema.px(context, posterH)

    init {
        orientation = VERTICAL
        isFocusable = true; isClickable = true
        clipChildren = false; clipToPadding = false
        setWillNotDraw(false)
        poster = FrameLayout(context).apply {
            background = GradientDrawable().apply { cornerRadius = radius; setColor(Cinema.PLACEHOLDER) }
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) } }
            clipToOutline = true
            elevation = Cinema.pxf(context, 14)
            // The poster's lower edge darkens a touch (`.card .p::after`), so a badge or the next row reads against it.
            foreground = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0, 0x40000000))
        }
        image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        poster.addView(image, FrameLayout.LayoutParams(-1, -1))
        addView(poster, LayoutParams(Cinema.px(context, posterW), posterHeight))
        if (titled) {
            title = Cinema.text(context, "", 21, Cinema.CARD_TITLE, weight = 500).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            subline = Cinema.text(context, "", 17, Cinema.DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; alpha = 0f }
            addView(title, LayoutParams(Cinema.px(context, posterW), -2).apply { topMargin = Cinema.px(context, 16) })
            addView(subline, LayoutParams(Cinema.px(context, posterW), -2).apply { topMargin = Cinema.px(context, 2) })
        } else { title = null; subline = null }
    }

    /** Big initials on the placeholder, for a title with no poster at all. */
    fun placeholder(name: String) {
        val initials = name.filter { it.isLetterOrDigit() || it == ' ' }.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
        poster.addView(Cinema.text(context, initials, 52, Cinema.alpha(Cinema.CREAM, 0.5f), serif = true).apply { gravity = Gravity.CENTER }, 0, FrameLayout.LayoutParams(-1, -1))
    }

    /** The "IN LIBRARY" badge (`.badge`): dark glass pill, saffron tracked capitals and a check, bottom left. */
    fun badge() {
        val pill = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Cinema.px(context, 9), Cinema.px(context, 6), Cinema.px(context, 12), Cinema.px(context, 6))
            background = GradientDrawable().apply { cornerRadius = Cinema.pxf(context, 20); setColor(0xD10C0806.toInt()) }
        }
        pill.addView(Cinema.icon(context, R.drawable.ic_cin_check, Cinema.SAFFRON), LayoutParams(Cinema.px(context, 15), Cinema.px(context, 15)).apply { marginEnd = Cinema.px(context, 7) })
        pill.addView(Cinema.text(context, "IN LIBRARY", 14, Cinema.SAFFRON, weight = 700, tracking = 0.14f))
        poster.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(Cinema.px(context, 12), 0, 0, Cinema.px(context, 12)) })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w / 2f
        pivotY = posterHeight * 0.35f
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val scale = if (gainFocus) 1.1f else 1f
        val duration = if (gainFocus) Motion.FOCUS_IN_MS else Motion.FOCUS_OUT_MS
        if (Motion.animationsOn(this)) animate().scaleX(scale).scaleY(scale).translationZ(if (gainFocus) Cinema.pxf(context, 20) else 0f).setDuration(duration).setInterpolator(Motion.SETTLE).start()
        else { scaleX = scale; scaleY = scale }
        poster.elevation = Cinema.pxf(context, if (gainFocus) 34 else 14)
        title?.let { t -> t.setTextColor(if (gainFocus) Cinema.CREAM else Cinema.CARD_TITLE); Cinema.weight(t, 21, serif = false, weight = if (gainFocus) 600 else 500) }
        subline?.animate()?.alpha(if (gainFocus && !subline.text.isNullOrEmpty()) 1f else 0f)?.setDuration(duration)?.start()
        invalidate()
        onFocus?.invoke(gainFocus)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (isFocused && android.os.Build.VERSION.SDK_INT >= 28) {
            // A warm bloom behind the poster, a little larger than it, so the focused card seems lit from behind.
            val s = Cinema.pxf(context, 6)
            box.set(poster.left - s, poster.top - s, poster.right + s, poster.bottom + s)
            canvas.drawRoundRect(box, radius + s, radius + s, glow)
        }
        super.dispatchDraw(canvas)
    }
}
