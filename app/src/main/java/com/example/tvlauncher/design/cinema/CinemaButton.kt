package com.example.tvlauncher.design.cinema

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvlauncher.design.Cinema

/**
 * The Cinema pill button (`.btn`): 76 mockup pixels tall, fully rounded, icon then label.
 *
 * Focus is cream: the focused button fills cream with dark text and blooms a soft glow behind it, and every other
 * button rests as glass. The one exception is [Kind.TERRA] ("Queue download"), which keeps its terracotta gradient so
 * the page's call to action reads even when focus is elsewhere. The glow is drawn outside the view's bounds, so its
 * parents must not clip children.
 */
class CinemaButton(context: Context, kind: Kind, label: CharSequence, iconRes: Int? = null) : LinearLayout(context) {
    enum class Kind { PRIMARY, TERRA, GLASS, DANGER }

    val label: TextView = Cinema.text(context, label, 25, Cinema.CREAM, weight = 600).apply { maxLines = 1 }
    private val icon: ImageView? = iconRes?.let { Cinema.icon(context, it, Cinema.CREAM) }
    // Focus glow: a soft blurred bloom behind the button (no ring or outline, per the Cinema language).
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (android.os.Build.VERSION.SDK_INT >= 28) maskFilter = android.graphics.BlurMaskFilter(Cinema.pxf(context, 22), android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val haloBox = RectF()
    private val haloWidth = Cinema.pxf(context, 4)

    var kind: Kind = kind
        set(value) { field = value; restyle() }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER
        isFocusable = true; isClickable = true
        setWillNotDraw(false)
        setPadding(Cinema.px(context, if (iconRes != null) 30 else 38), 0, Cinema.px(context, 38), 0)
        icon?.let { addView(it, LayoutParams(Cinema.px(context, 30), Cinema.px(context, 30)).apply { marginEnd = Cinema.px(context, 14) }) }
        addView(this.label)
        contentDescription = label
        restyle()
    }

    fun setText(value: CharSequence) { label.text = value; contentDescription = value }

    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.6f }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        restyle()
    }

    private fun restyle() {
        val r = Cinema.pxf(context, 38)
        val lit = isFocused
        val (fill, text) = when {
            kind == Kind.TERRA -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFEB8A56.toInt(), 0xFFD4623A.toInt())).apply { cornerRadius = r } to 0xFF1D0D05.toInt()
            lit -> GradientDrawable().apply { cornerRadius = r; setColor(Cinema.CREAM) } to if (kind == Kind.DANGER) 0xFF8A2A12.toInt() else Cinema.INK
            else -> Cinema.glass(context, 38f, if (kind == Kind.PRIMARY) Cinema.GLASS_STRONG else Cinema.GLASS) to if (kind == Kind.DANGER) Cinema.DANGER_TEXT else Cinema.CREAM
        }
        background = fill
        label.setTextColor(text)
        icon?.setColorFilter(text)
        halo.color = if (kind == Kind.TERRA) Cinema.alpha(Cinema.TERRA, 0.55f) else Cinema.alpha(Cinema.CREAM, 0.32f)
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        if (isFocused) {
            haloBox.set(-haloWidth, -haloWidth, width + haloWidth, height + haloWidth)
            val r = height / 2f + haloWidth
            canvas.drawRoundRect(haloBox, r, r, halo)
        }
        super.draw(canvas)
    }
}
