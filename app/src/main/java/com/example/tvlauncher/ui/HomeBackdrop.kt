package com.example.tvlauncher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.example.tvlauncher.data.ArtLibrary
import com.example.tvlauncher.design.SectionTheme
import java.util.concurrent.Executors

/**
 * The painting behind the home screen. It crossfades to the next painting in the
 * library on every visit to home and, while home stays open, every [AUTO_MS].
 * Decoding happens off the UI thread, sized to the screen rather than the
 * full-resolution source.
 *
 * It is also the far wall of the room: it drifts up more slowly than the page as you scroll (a few percent,
 * inside a little overscan, so no edge ever shows), and a low-alpha wash over it takes on the mood of the
 * focused section (see [SectionTheme]).
 */
class HomeBackdrop(private val base: ImageView, private val library: ArtLibrary) {
    private val handler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val fade = ImageView(base.context).apply {
        scaleType = base.scaleType
        alpha = 0f
        importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val wash = View(base.context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var generation = 0
    private var running = false

    private val tick = object : Runnable {
        override fun run() { advance(); handler.postDelayed(this, AUTO_MS) }
    }

    init {
        val parent = base.parent as ViewGroup
        parent.addView(fade, parent.indexOfChild(base) + 1, ViewGroup.LayoutParams(-1, -1))
        parent.addView(wash, parent.indexOfChild(fade) + 1, ViewGroup.LayoutParams(-1, -1))
        wash.setBackgroundColor(SectionTheme.Mood.NEUTRAL.tint)
        // Overscan for the parallax: scaled from the top edge, so the spare height is all below.
        for (v in listOf(base, fade)) { v.pivotY = 0f; v.scaleX = OVERSCAN; v.scaleY = OVERSCAN }
    }

    /** Page scrolled to [scrollY]: the painting follows at a fraction of the speed, within its overscan. */
    fun onPageScrolled(scrollY: Int) {
        val headroom = base.height * (OVERSCAN - 1f)
        val y = -(scrollY * PARALLAX).coerceIn(0f, headroom)
        base.translationY = y; fade.translationY = y
    }

    /** Shows the library's current painting immediately (no fade). */
    fun showCurrent() = load(library.index, animate = false)

    /** Moves to the next painting with a slow crossfade. */
    fun advance() {
        library.select(library.index + 1)
        load(library.index, animate = true)
    }

    /** The focused section changed: shift the room's light towards its mood. */
    fun setMood(mood: SectionTheme.Mood) = SectionTheme.apply(wash, mood)

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
        val effect = if (blurred) android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP) else null
        base.setRenderEffect(effect)
        fade.setRenderEffect(effect)
    }

    private fun load(index: Int, animate: Boolean) {
        val ticket = ++generation
        val resource = library.paintings[index].resource
        val width = base.resources.displayMetrics.widthPixels.coerceAtLeast(1280)
        decoder.execute {
            val bitmap = decode(resource, width) ?: return@execute
            handler.post { if (ticket == generation) apply(bitmap, animate) }
        }
    }

    private fun apply(bitmap: Bitmap, animate: Boolean) {
        if (!animate) {
            base.setImageBitmap(bitmap)
            fade.animate().cancel()
            fade.alpha = 0f
            return
        }
        fade.setImageBitmap(bitmap)
        fade.animate().cancel()
        fade.animate().alpha(1f).setDuration(FADE_MS).withEndAction {
            base.setImageBitmap(bitmap)
            fade.alpha = 0f
        }.start()
    }

    private fun decode(resource: Int, targetWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(base.resources, resource, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
        BitmapFactory.decodeResource(base.resources, resource, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }

    private companion object {
        const val AUTO_MS = 120_000L
        const val FADE_MS = 1600L
        const val OVERSCAN = 1.06f
        const val PARALLAX = 0.06f
    }
}
