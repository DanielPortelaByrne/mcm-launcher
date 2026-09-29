package com.example.tvlauncher.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.example.tvlauncher.data.ArtLibrary

class ArtModeOverlay(private val overlay: View, private val onExit: () -> Unit) {
    val library = ArtLibrary(overlay.context)
    private val handler = Handler(Looper.getMainLooper())
    private val picture = (overlay as FrameLayout).getChildAt(0) as ImageView
    private val caption = TextView(overlay.context).apply {
        com.example.tvlauncher.design.Type.apply(this, com.example.tvlauncher.design.Type.Style.CAPTION, context.getColor(com.example.tvlauncher.R.color.text))
        setPadding(32, 16, 32, 16); setBackgroundColor(0xcc211d14.toInt())
        layoutParams = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
    }
    private var paused = false
    private val slide = object : Runnable {
        override fun run() { if (isVisible && !paused) { library.select(library.index + 1); render(false); schedule() } }
    }
    init {
        (overlay as FrameLayout).addView(caption)
        overlay.isFocusable = true
        overlay.isFocusableInTouchMode = true
        overlay.setBackgroundColor(0xff211d14.toInt())
        picture.scaleType = ImageView.ScaleType.FIT_CENTER
        overlay.setOnKeyListener { _, key, event ->
            if (key !in listOf(19,20,21,22,23,66,4)) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) when (key) {
                21,22 -> { library.select(library.index + if (key == 22) 1 else -1); render(true); schedule() }
                23,66 -> { paused = !paused; render(true); schedule() }
                19,20 -> render(true)
                4 -> { hide(); onExit() }
            }
            true
        }
    }
    val isVisible get() = overlay.visibility == View.VISIBLE
    fun show() { overlay.visibility = View.VISIBLE; overlay.keepScreenOn = true; overlay.requestFocus(); render(true); schedule() }
    fun hide() { handler.removeCallbacksAndMessages(null); overlay.keepScreenOn = false; overlay.visibility = View.GONE }
    fun pause() { handler.removeCallbacksAndMessages(null); overlay.keepScreenOn = false }
    fun resume() { if (isVisible) { overlay.keepScreenOn = true; schedule() } }
    private fun schedule() { handler.removeCallbacks(slide); if (!paused) handler.postDelayed(slide, library.interval) }
    private fun render(showCaption: Boolean) {
        library.show(picture)
        caption.text = "${library.paintings[library.index].title}   ·   ${library.index+1} / ${library.paintings.size}   ·   MCM originals\nLeft / Right  Browse     OK  ${if(paused) "Play" else "Pause"}     Back  Home"
        if (showCaption) { caption.visibility = View.VISIBLE; handler.postDelayed({ caption.visibility = View.GONE }, 5000) }
    }
}
