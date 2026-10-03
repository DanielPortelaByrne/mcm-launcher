package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.example.tvlauncher.data.parents.FeedImages
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.Type

/**
 * Full-screen photographs: Left / Right to go through them, Back to close, OK to let them turn over by
 * themselves (a slow crossfade every ten seconds; OK again stops it). No pan, no zoom, no Ken Burns.
 * Portrait and landscape both sit whole on a darkened, softened copy of themselves, so nothing is cropped.
 * The counter and caption show for a few seconds after each change, then leave the photograph alone.
 */
class PhotoViewer(private val activity: Activity, private val root: FrameLayout, private val images: FeedImages) {

    data class Slide(val imageUrl: String, val thumbUrl: String, val caption: String?, val detail: String?)

    private val handler = Handler(Looper.getMainLooper())
    private val backing = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.32f }
    private val photo = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val incoming = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0f }
    private val caption = Type.onPainting(Kit.line(activity, "", Type.Style.TITLE, lines = 2))
    private val detail = Type.onPainting(Kit.line(activity, "", Type.Style.CAPTION))
    private val label = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val view = FrameLayout(activity).apply {
        setBackgroundColor(0xFF1A140E.toInt())
        isFocusable = true; isFocusableInTouchMode = true
        visibility = View.GONE
        descendantFocusability = FrameLayout.FOCUS_BLOCK_DESCENDANTS
    }

    private var slides: List<Slide> = emptyList()
    private var index = 0
    private var playing = false
    private var returnFocus: View? = null
    private var token = 0

    val isVisible get() = view.visibility == View.VISIBLE

    init {
        val m = Kit.dp(activity, 48)
        view.addView(backing, FrameLayout.LayoutParams(-1, -1))
        // A soft dark vignette over the backing, so the photograph itself is always the brightest thing.
        view.addView(View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x991A140E.toInt(), 0x661A140E, 0xCC1A140E.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        val pad = Kit.dp(activity, 24)
        view.addView(photo, FrameLayout.LayoutParams(-1, -1).apply { setMargins(pad, pad, pad, pad) })
        view.addView(incoming, FrameLayout.LayoutParams(-1, -1).apply { setMargins(pad, pad, pad, pad) })
        label.addView(caption); label.addView(detail)
        label.setPadding(Kit.dp(activity, 20), Kit.dp(activity, 12), Kit.dp(activity, 20), Kit.dp(activity, 14))
        label.background = GradientDrawable().apply { cornerRadius = Kit.dpf(activity, 14f); setColor(0xB31A140E.toInt()) }
        view.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(m, 0, m, Kit.dp(activity, 40)) })
        view.setOnKeyListener { _, keyCode, event -> onKey(keyCode, event) }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    fun show(slides: List<Slide>, start: Int, from: View?) {
        if (slides.isEmpty()) return
        this.slides = slides
        returnFocus = from
        playing = false
        index = start.coerceIn(0, slides.lastIndex)
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(Motion.SHEET_IN_MS).setInterpolator(Motion.SETTLE).start()
        view.requestFocus()
        photo.setImageDrawable(null); backing.setImageDrawable(null)
        display(fade = false)
    }

    fun hide() {
        if (!isVisible) return
        stop()
        token++
        view.animate().alpha(0f).setDuration(Motion.SHEET_OUT_MS).setInterpolator(Motion.SETTLE).withEndAction {
            view.visibility = View.GONE
            photo.setImageDrawable(null); incoming.setImageDrawable(null); backing.setImageDrawable(null)
        }.start()
        returnFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
    }

    private fun onKey(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return keyCode != KeyEvent.KEYCODE_BACK
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> { stop(); step(1) }
            KeyEvent.KEYCODE_DPAD_LEFT -> { stop(); step(-1) }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (playing) { stop(); showLabel("Paused") } else { playing = true; showLabel("Slideshow"); schedule() }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> showLabel(null)
            KeyEvent.KEYCODE_BACK -> return false     // the activity's Back handler closes us
            else -> return false
        }
        return true
    }

    private fun step(delta: Int) {
        if (slides.size < 2) { showLabel(null); return }
        index = Math.floorMod(index + delta, slides.size)
        display(fade = true)
    }

    private val advance = Runnable { if (playing && isVisible) { step(1); schedule() } }
    private fun schedule() { handler.removeCallbacks(advance); handler.postDelayed(advance, SLIDE_MS) }
    private fun stop() { playing = false; handler.removeCallbacks(advance) }

    private fun display(fade: Boolean) {
        val slide = slides[index]
        val mine = ++token
        val w = root.width.takeIf { it > 0 } ?: 1920
        val h = root.height.takeIf { it > 0 } ?: 1080
        showLabel(null)
        // The small rendition arrives first (it is usually cached), the full one replaces it when ready.
        images.load(slide.thumbUrl, 64, 36) { thumb -> if (mine == token && thumb != null) backing.setImageBitmap(thumb) }
        images.load(slide.thumbUrl, w / 2, h / 2) { thumb -> if (mine == token && thumb != null && photo.drawable == null) photo.setImageBitmap(thumb) }
        images.load(slide.imageUrl, w, h) { full -> if (mine == token) present(full, fade) }
        // Have the next one ready, so Right is instant.
        slides.getOrNull((index + 1) % slides.size)?.let { images.prefetch(listOf(it.imageUrl, it.thumbUrl)) }
    }

    private fun present(bitmap: Bitmap?, fade: Boolean) {
        if (bitmap == null) { if (photo.drawable == null) showLabel("This photo couldn't be loaded"); return }
        if (!fade || !Motion.animationsOn(view) || photo.drawable == null) { photo.setImageBitmap(bitmap); return }
        incoming.animate().cancel()
        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(CROSSFADE_MS).setInterpolator(Motion.SETTLE).withEndAction {
            photo.setImageBitmap(bitmap); incoming.alpha = 0f; incoming.setImageDrawable(null)
        }.start()
    }

    private val hideLabel = Runnable { label.animate().alpha(0f).setDuration(600).start() }

    private fun showLabel(note: String?) {
        val slide = slides.getOrNull(index) ?: return
        caption.text = note ?: slide.caption ?: slide.detail.orEmpty()
        caption.visibility = if (caption.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val count = if (slides.size > 1) "${index + 1} / ${slides.size}" else null
        detail.text = listOfNotNull(if (note == null && slide.caption != null) slide.detail else null, count).joinToString("   ·   ")
        detail.visibility = if (detail.text.isNullOrBlank()) View.GONE else View.VISIBLE
        label.visibility = if (caption.visibility == View.GONE && detail.visibility == View.GONE) View.GONE else View.VISIBLE
        label.animate().cancel(); label.alpha = 1f
        handler.removeCallbacks(hideLabel); handler.postDelayed(hideLabel, LABEL_MS)
    }

    fun close() { stop(); handler.removeCallbacksAndMessages(null) }

    private companion object {
        const val SLIDE_MS = 10_000L
        const val CROSSFADE_MS = 700L
        const val LABEL_MS = 4_000L
    }
}
