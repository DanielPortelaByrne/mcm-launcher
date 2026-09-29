package com.example.tvlauncher.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.widget.HorizontalScrollView
import android.widget.ScrollView

/**
 * Scroll views that never jump. Android's own "scroll the focused child into view" is switched off, and
 * [scrollFocusIntoView] pans them with the launcher's slow, soft curve instead. Their soft edges are drawn
 * by the home backdrop's edge veil (see HomeBackdrop.attachVeil), not by offscreen layers.
 */
class CalmScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ScrollView(context, attrs) {
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
}

class CalmHorizontalScrollView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : HorizontalScrollView(context, attrs) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0
}
