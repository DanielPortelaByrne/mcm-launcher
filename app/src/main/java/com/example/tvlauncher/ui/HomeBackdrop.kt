package com.example.tvlauncher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.example.tvlauncher.R
import com.example.tvlauncher.data.ArtLibrary
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.SectionTheme
import java.util.concurrent.Executors

/**
 * The painting behind the home screen. It crossfades to the next painting in the
 * library on every visit to home and, while home stays open, every [AUTO_MS].
 * Decoding happens off the UI thread, sized to the screen rather than the
 * full-resolution source. Its light takes on the mood of the focused section (see [SectionTheme]).
 *
 * This TV's GPU is short of fill rate, so the painting is the only full-screen layer: the page vignette is
 * baked into each decoded bitmap once, the mood is a colour filter on the painting itself, and the page's
 * soft edges are drawn by an [EdgeVeil] rather than by offscreen layers.
 */
class HomeBackdrop(private val base: ImageView, private val library: ArtLibrary) {
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val fade = ImageView(base.context).apply {
        scaleType = base.scaleType
        alpha = 0f
        importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private var filter: ColorFilter? = null
    private var tint = SectionTheme.Mood.NEUTRAL.tint
    private var mood = SectionTheme.Mood.NEUTRAL
    private var moodAnimator: android.animation.ValueAnimator? = null
    private var veil: EdgeVeil? = null
    /** Runs once, when the first painting is on screen (the window's own ground can then be dropped). */
    var onFirstPainting: (() -> Unit)? = null
    private var generation = 0
    private var running = false

    private val tick = object : Runnable {
        override fun run() { advance(); handler.postDelayed(this, AUTO_MS) }
    }

    init {
        val parent = base.parent as ViewGroup
        parent.addView(fade, parent.indexOfChild(base) + 1, ViewGroup.LayoutParams(-1, -1))
    }

    /**
     * Gives [page] its soft edges: a veil just above it redraws thin strips of the painting over the page
     * wherever content has scrolled towards an edge. Returns the veil so its alpha can follow the page's.
     */
    fun attachVeil(page: CalmScrollView): View {
        val v = EdgeVeil(page)
        val parent = page.parent as ViewGroup
        parent.addView(v, parent.indexOfChild(page) + 1, ViewGroup.LayoutParams(-1, -1))
        veil = v
        return v
    }

    /** The page scrolled: which edges fade may have changed. */
    fun onPageScrolled() { veil?.invalidate() }

    /** Shows the library's current painting immediately (no fade). */
    fun showCurrent() = load(library.index, animate = false)

    /** Moves to the next painting with a slow crossfade. */
    fun advance() {
        library.select(library.index + 1)
        load(library.index, animate = true)
    }

    /** The focused section changed: shift the room's light towards its mood. */
    fun setMood(mood: SectionTheme.Mood) {
        if (mood == this.mood) return
        this.mood = mood
        moodAnimator?.cancel()
        if (!Motion.animationsOn(base)) { applyTint(mood.tint); return }
        moodAnimator = android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), tint, mood.tint).apply {
            duration = SectionTheme.TRANSITION_MS
            interpolator = Motion.SETTLE
            addUpdateListener { applyTint(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyTint(color: Int) {
        tint = color
        filter = if (android.graphics.Color.alpha(color) == 0) null else android.graphics.PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        base.colorFilter = filter; fade.colorFilter = filter
        veil?.invalidate()
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(tick, AUTO_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun close() { stop(); decoder.shutdownNow() }

    /**
     * Softens the painting behind a full-screen panel or sheet, so the panel sits on colour and light
     * rather than on legible detail. Android 12+ only; older versions just keep the solid ground.
     */
    fun setBlurred(blurred: Boolean) {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        val r = 36f * base.resources.displayMetrics.density
        val effect = if (blurred) android.graphics.RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null
        base.setRenderEffect(effect)
        fade.setRenderEffect(effect)
    }

    private fun load(index: Int, animate: Boolean) {
        val ticket = ++generation
        val resource = library.paintings[index].resource
        val metrics = base.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1280)
        decoder.execute {
            val bitmap = decode(resource, width) ?: return@execute
            bakeVignette(bitmap, metrics.widthPixels, metrics.heightPixels)
            handler.post { if (ticket == generation) apply(bitmap, animate) }
        }
    }

    /** Paints the page vignette into [bitmap] exactly where it lands on a centre-cropped [w] x [h] screen. */
    private fun bakeVignette(bitmap: Bitmap, w: Int, h: Int) {
        val bw = bitmap.width.toFloat(); val bh = bitmap.height.toFloat()
        val s = maxOf(w / bw, h / bh)
        val canvas = Canvas(bitmap)
        canvas.translate(-(w - bw * s) / 2f / s, -(h - bh * s) / 2f / s)
        canvas.scale(1f / s, 1f / s)
        val vignette = androidx.core.content.ContextCompat.getDrawable(base.context, R.drawable.home_vignette)?.mutate() ?: return
        vignette.setBounds(0, 0, w, h)
        vignette.draw(canvas)
    }

    private fun apply(bitmap: Bitmap, animate: Boolean) {
        onFirstPainting?.let { onFirstPainting = null; it() }
        if (!animate) {
            base.setImageBitmap(bitmap)
            fade.animate().cancel()
            fade.alpha = 0f
            veil?.invalidate()
            return
        }
        fade.setImageBitmap(bitmap)
        fade.animate().cancel()
        fade.animate().alpha(1f).setDuration(FADE_MS).setUpdateListener { veil?.invalidate() }.withEndAction {
            base.setImageBitmap(bitmap)
            fade.alpha = 0f
            veil?.invalidate()
        }.start()
    }

    private fun decode(resource: Int, targetWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(base.resources, resource, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        BitmapFactory.decodeResource(base.resources, resource, BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true })
    } catch (e: Exception) { null }

    /**
     * The page's soft edges. Content dissolving into the painting at an edge looks the same as the painting
     * being laid back over the content with a gradient, so that is what this draws: thin strips of the same
     * painting (same crop, same mood filter, mid-crossfade too) fading from opaque at the screen edge to
     * clear. Top and bottom follow the page's scroll; the sides always veil the page margins, which only
     * rails scrolled past their start ever reach. No offscreen layers: only the strips are filled.
     */
    private inner class EdgeVeil(private val page: CalmScrollView) : View(page.context) {
        private val vertical = resources.getDimensionPixelSize(R.dimen.edge_fade).toFloat()
        private val side = resources.getDimensionPixelSize(R.dimen.page_margin).toFloat()
        private val strips = Array(4) { Strip() }

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val top = page.scrollY > 0
            val bottom = page.canScrollVertically(1)
            draw(c, base, 1f, w, h, top, bottom)
            if (fade.alpha > 0f) draw(c, fade, fade.alpha, w, h, top, bottom)
        }

        private fun draw(c: Canvas, iv: ImageView, alpha: Float, w: Float, h: Float, top: Boolean, bottom: Boolean) {
            val bitmap = (iv.drawable as? BitmapDrawable)?.bitmap ?: return
            val m = iv.imageMatrix
            // Sides: left, right. Then top and bottom when the page can scroll that way.
            strips[0].draw(c, bitmap, m, alpha, 0f, 0f, side, h, 0f, 0f, side, 0f)
            strips[1].draw(c, bitmap, m, alpha, w - side, 0f, w, h, w, 0f, w - side, 0f)
            if (top) strips[2].draw(c, bitmap, m, alpha, 0f, 0f, w, vertical, 0f, 0f, 0f, vertical)
            if (bottom) strips[3].draw(c, bitmap, m, alpha, 0f, h - vertical, w, h, 0f, h, 0f, h - vertical)
        }

        /** One edge strip; its shader is rebuilt only when the bitmap, crop or strip geometry changes. */
        private inner class Strip {
            private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            private var bitmap: Bitmap? = null
            private val matrix = Matrix()
            private var x0 = 0f; private var y0 = 0f; private var x1 = 0f; private var y1 = 0f

            fun draw(c: Canvas, bmp: Bitmap, m: Matrix, alpha: Float, l: Float, t: Float, r: Float, b: Float,
                     x0: Float, y0: Float, x1: Float, y1: Float) {
                if (bmp !== bitmap || m != matrix || x0 != this.x0 || y0 != this.y0 || x1 != this.x1 || y1 != this.y1) {
                    bitmap = bmp; matrix.set(m); this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1
                    val picture = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
                    val mask = LinearGradient(x0, y0, x1, y1, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
                    paint.shader = ComposeShader(picture, mask, PorterDuff.Mode.DST_IN)
                }
                paint.colorFilter = filter
                paint.alpha = (alpha * 255).toInt()
                c.drawRect(l, t, r, b, paint)
            }
        }
    }

    private companion object {
        const val AUTO_MS = 120_000L
        const val FADE_MS = 1600L
    }
}
