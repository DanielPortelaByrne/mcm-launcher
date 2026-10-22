package com.example.tvlauncher.design.cinema

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import com.example.tvlauncher.design.Cinema

/**
 * A backdrop picture for a featured title or a title page: centre-cropped into the view (biased by [focusX]: 0.5
 * centre, 1 right), with its left part faded to nothing ([fadeTo], the share of the width the fade spans, `.mk`).
 *
 * The crop and the fade are painted once into a cached bitmap when the picture or the size changes; drawing is a
 * single blit, so the page scrolls over it for free.
 */
class HeroBackdrop(context: Context, private val focusX: Float = 0.5f, private val fadeTo: Float = 0.38f) : View(context) {
    private var source: Bitmap? = null
    private var cache: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init { isFocusable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; alpha = 0f }

    /** Shows [bitmap], fading it in. */
    fun show(bitmap: Bitmap?) {
        source = bitmap; rebuild()
        if (bitmap == null) { alpha = 0f; return }
        alpha = 0f; animate().alpha(1f).setDuration(350).start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        cache?.recycle(); cache = null
        val src = source ?: return invalidate()
        if (width <= 0 || height <= 0) return
        // Half resolution is plenty for a picture that sits under scrims; it also halves the memory.
        val w = width / 2; val h = height / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val dx = (w - src.width * scale) * focusX
        val dy = (h - src.height * scale) * 0.5f
        c.drawBitmap(src, Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }, Paint(Paint.FILTER_BITMAP_FLAG))
        if (fadeTo > 0f) c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, w * fadeTo, 0f, 0, -0x1000000, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        cache = out
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val c = cache ?: return
        canvas.drawBitmap(c, null, android.graphics.Rect(0, 0, width, height), paint)
    }
}

/**
 * Two fades of the Cinema ground (one across, one down) in one pass (`bdgrad` in the mockups). Each fade is a list of
 * (position, alpha) stops over [Cinema.BG].
 */
class Scrim(private val across: List<Pair<Float, Float>>, private val down: List<Pair<Float, Float>>) : Drawable() {
    private val paint = Paint().apply { isDither = true }
    override fun onBoundsChange(b: android.graphics.Rect) {
        fun grad(stops: List<Pair<Float, Float>>, x1: Float, y1: Float) = LinearGradient(b.left.toFloat(), b.top.toFloat(), x1, y1,
            stops.map { Cinema.alpha(Cinema.BG, it.second) }.toIntArray(), stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP)
        paint.shader = ComposeShader(grad(across, b.right.toFloat(), b.top.toFloat()), grad(down, b.left.toFloat(), b.bottom.toFloat()), PorterDuff.Mode.SRC_OVER)
    }
    override fun draw(canvas: Canvas) { canvas.drawRect(bounds, paint) }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT

    companion object {
        /** The featured title's fades (`bdgrad(.98)`). */
        fun hero() = Scrim(listOf(0f to 0.98f, 0.34f to 0.85f, 0.66f to 0.25f, 1f to 0.1f), listOf(0f to 0.55f, 0.22f to 0f, 0.5f to 0f, 1f to 0.96f))
        /** A title page's fades (`detail`). */
        fun detail() = Scrim(listOf(0f to 0.96f, 0.38f to 0.8f, 0.72f to 0.3f, 1f to 0.15f), listOf(0f to 0.35f, 0.25f to 0f, 0.48f to 0f, 0.86f to 0.96f, 1f to 0.96f))
    }
}
