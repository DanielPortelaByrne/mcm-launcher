package com.example.tvlauncher.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.FocusFinder
import android.view.View
import android.widget.ScrollView

/**
 * The Cinema page's focus rules. Left at the edge of a row returns to the active rail item, rather than the nearest
 * unrelated one; Up and Down stay on the page (they never jump sideways into the rail), and at the page's ends the
 * key is simply absorbed.
 */
class CinemaContentScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {
    var navigationTarget: (() -> View?)? = null

    override fun focusSearch(focused: View?, direction: Int): View? {
        if (direction == View.FOCUS_LEFT) {
            val inside = FocusFinder.getInstance().findNextFocus(this, focused, direction)
            if (inside != null && focused != null) {
                val from = Rect().also { focused.getDrawingRect(it); offsetDescendantRectToMyCoords(focused, it) }
                val to = Rect().also { inside.getDrawingRect(it); offsetDescendantRectToMyCoords(inside, it) }
                // A hero button above the row is not a left neighbour of its first poster.
                if (from.top < to.bottom && to.top < from.bottom) return inside
            }
            navigationTarget?.invoke()?.takeIf { it.isShown && it.isFocusable }?.let { return it }
        }
        if (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) {
            return FocusFinder.getInstance().findNextFocus(this, focused, direction) ?: focused
        }
        return super.focusSearch(focused, direction)
    }
}
