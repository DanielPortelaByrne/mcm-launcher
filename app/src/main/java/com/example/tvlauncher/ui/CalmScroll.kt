package com.example.tvlauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import com.example.tvlauncher.R

/**
 * Scroll views that never jump. Android's own "scroll the focused child into view" is switched off, and
 * [scrollFocusIntoView] pans them with the launcher's slow, soft curve instead.
 *
 * They also dissolve content at an edge it can scroll past, instead of slicing it. This TV disables
 * Android's own fading edges system-wide, so the fade is drawn here: the content is rendered into a
 * layer and an alpha gradient is cut out of it along the edge.
 */
class CalmScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
    private val fade = EdgeFade(resources.getDimensionPixelSize(R.dimen.edge_fade).toFloat())

    /** Called with the scroll position on every scroll frame and after every layout. */
    var onScrolled: ((Int) -> Unit)? = null

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        onScrolled?.invoke(t)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        onScrolled?.invoke(scrollY)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val top = scrollY > 0
        val bottom = canScrollVertically(1)
        if (!top && !bottom) { super.dispatchDraw(canvas); return }
        val w = width.toFloat(); val h = height.toFloat(); val y = scrollY.toFloat()
        val save = canvas.saveLayer(0f, y, w, y + h, null)
        super.dispatchDraw(canvas)
        if (top) fade.draw(canvas, 0f, y, w, y + fade.length, fromTop = true)
        if (bottom) fade.draw(canvas, 0f, y + h - fade.length, w, y + h, fromTop = false)
        canvas.restoreToCount(save)
    }
}

class CalmHorizontalScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HorizontalScrollView(context, attrs) {
    private val fade = EdgeFade(resources.getDimensionPixelSize(R.dimen.edge_fade).toFloat())

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

    override fun dispatchDraw(canvas: Canvas) {
        val left = scrollX > 0
        val right = canScrollHorizontally(1)
        if (!left && !right) { super.dispatchDraw(canvas); return }
        // Focus highlights may reach above and below the rail, so the layer is taller than the view.
        val w = width.toFloat(); val x = scrollX.toFloat(); val extra = height.toFloat()
        val save = canvas.saveLayer(x, -extra, x + w, height + extra, null)
        super.dispatchDraw(canvas)
        if (left) fade.drawHorizontal(canvas, x, -extra, x + fade.length, height + extra, fromLeft = true)
        if (right) fade.drawHorizontal(canvas, x + w - fade.length, -extra, x + w, height + extra, fromLeft = false)
        canvas.restoreToCount(save)
    }
}

/** An alpha gradient that erases content towards an edge (opaque content in, transparent at the edge). */
private class EdgeFade(val length: Float) {
    private val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

    fun draw(c: Canvas, l: Float, t: Float, r: Float, b: Float, fromTop: Boolean) {
        paint.shader = LinearGradient(0f, t, 0f, b,
            if (fromTop) EDGE else CLEAR, if (fromTop) CLEAR else EDGE, Shader.TileMode.CLAMP)
        c.drawRect(l, t, r, b, paint)
    }

    fun drawHorizontal(c: Canvas, l: Float, t: Float, r: Float, b: Float, fromLeft: Boolean) {
        paint.shader = LinearGradient(l, 0f, r, 0f,
            if (fromLeft) EDGE else CLEAR, if (fromLeft) CLEAR else EDGE, Shader.TileMode.CLAMP)
        c.drawRect(l, t, r, b, paint)
    }

    private companion object {
        const val EDGE = 0xFF000000.toInt()
        const val CLEAR = 0x00000000
    }
}
