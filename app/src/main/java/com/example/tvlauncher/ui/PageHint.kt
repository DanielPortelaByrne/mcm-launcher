package com.example.tvlauncher.ui

import android.widget.TextView
import com.example.tvlauncher.design.Motion

/**
 * The launcher's one hint line: bottom-left of the home page, only for the focused item's hold (or
 * non-obvious) action -- "Hold OK to rearrange", "OK to shuffle". MainActivity clears it on every focus
 * change; the newly focused item sets it again from its own focus listener if it has something to say.
 */
object PageHint {
    private var view: TextView? = null

    fun attach(target: TextView) { view = target; target.alpha = 0f }

    fun show(text: String?) {
        val v = view ?: return
        if (text.isNullOrBlank()) {
            v.animate().alpha(0f).setDuration(Motion.FOCUS_OUT_MS).start()
        } else {
            v.text = text
            v.animate().alpha(1f).setDuration(Motion.FADE_MS).start()
        }
    }
}
