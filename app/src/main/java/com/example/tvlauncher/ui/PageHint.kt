package com.example.tvlauncher.ui

import android.os.Handler
import android.os.Looper
import android.widget.TextView
import com.example.tvlauncher.design.Motion

/**
 * The launcher's one hint line, bottom-right, only for the focused item's hold (or non-obvious) action --
 * "Hold OK to rearrange", "OK to shuffle". MainActivity clears it on every focus change; the newly focused
 * item sets it again from its own focus listener if it has something to say.
 *
 * It behaves like a quiet note, not a banner: it waits until focus has settled before appearing (so it
 * never flickers while you move along a row), steps away after a few seconds so it doesn't sit on the
 * content, and stops repeating a hint once it has been seen a few times this session.
 */
object PageHint {
    private const val SETTLE_MS = 600L
    private const val VISIBLE_MS = 4000L
    private const val MAX_SHOWS = 3

    private var view: TextView? = null
    /** Where tips sit (level with the header, set by MainActivity) and where a mode's longer line sits. */
    var tipAt: android.widget.FrameLayout.LayoutParams? = null
    var modeAt: android.widget.FrameLayout.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val shown = HashMap<String, Int>()
    private var pending: String? = null

    private val reveal = Runnable {
        val v = view ?: return@Runnable
        val text = pending ?: return@Runnable
        shown[text] = (shown[text] ?: 0) + 1
        place(v, tipAt)
        v.text = text
        v.animate().alpha(1f).setDuration(Motion.FADE_MS).start()
        handler.postDelayed(retire, VISIBLE_MS)
    }
    private val retire = Runnable { view?.animate()?.alpha(0f)?.setDuration(Motion.FADE_MS * 2)?.start() }

    fun attach(target: TextView) { view = target; target.alpha = 0f }

    private fun place(v: TextView, at: android.widget.FrameLayout.LayoutParams?) {
        if (at != null && v.layoutParams !== at) v.layoutParams = at
    }

    /** A mode the remote is in (dragging an app): shown at once and kept up until replaced. */
    fun showMode(text: String) {
        val v = view ?: return
        handler.removeCallbacks(reveal)
        handler.removeCallbacks(retire)
        pending = null
        place(v, modeAt)
        v.text = text
        v.animate().alpha(1f).setDuration(Motion.FADE_MS).start()
    }

    fun show(text: String?) {
        val v = view ?: return
        handler.removeCallbacks(reveal)
        handler.removeCallbacks(retire)
        pending = text
        v.animate().alpha(0f).setDuration(Motion.FOCUS_OUT_MS).start()
        if (text.isNullOrBlank() || (shown[text] ?: 0) >= MAX_SHOWS) return
        handler.postDelayed(reveal, SETTLE_MS)
    }
}
