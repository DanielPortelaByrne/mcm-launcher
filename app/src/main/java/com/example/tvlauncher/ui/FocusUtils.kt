package com.example.tvlauncher.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.example.tvlauncher.design.Motion

/**
 * Requests focus onto [target] now, and re-checks shortly after.
 *
 * A freshly-built subtree (a rebuilt shelf row, a just-shown overlay)
 * races an immediate requestFocus() against the platform's own
 * window/layout-transition focus handling: the immediate call frequently
 * wins, but not always -- if it loses, whatever was focused *before* the
 * change simply stays focused (overlapping views don't steal focus from
 * each other automatically), which silently leaves a background view
 * focused underneath a new overlay. The delayed re-check catches that.
 */
fun requestFocusRobust(root: ViewGroup, target: View) {
    target.post { target.requestFocus() }
    target.postDelayed({ if (root.findFocus() == null) target.requestFocus() }, 300)
}

/**
 * Keeps the focused item in view by *panning*, never jumping.
 *
 * Vertically, the page only moves when the item leaves a comfort zone, and then it glides so the item
 * settles about a quarter of the way down the screen, which reads as calm, deliberate scrolling rather than
 * an edge-chasing nudge. Horizontally, rails glide just far enough to keep the item and a little of its
 * neighbours in view. Other (plain) scroll views keep Android's own behaviour.
 */
fun scrollFocusIntoView(view: View, hasFocus: Boolean) {
    if (!hasFocus) return
    view.post {
        // Plain scroll views (drawer, edit list) still rely on Android to reveal the item.
        view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
        val density = view.resources.displayMetrics.density
        var parent: android.view.ViewParent? = view.parent
        while (parent != null) {
            when (parent) {
                is CalmScrollView -> panVertically(parent, view, density)
                is CalmHorizontalScrollView -> panHorizontally(parent, view, density)
            }
            parent = parent.parent
        }
    }
}

private fun panVertically(sv: CalmScrollView, view: View, density: Float) {
    val bounds = Rect(0, 0, view.width, view.height)
    sv.offsetDescendantRectToMyCoords(view, bounds)
    val h = sv.height
    // 1) Anything that fits on the first screen keeps the page at the top: the greeting, film card and
    //    Your apps never slide away while you move between them.
    if (bounds.bottom < (h * 0.92f)) { Motion.scrollVerticalTo(sv, 0); return }
    // 2) Inside the comfort zone: stay put, so moving sideways along a row never moves the page.
    val comfortTop = sv.scrollY + (h * 0.05f).toInt()
    val comfortBottom = sv.scrollY + (h * 0.88f).toInt()
    if (bounds.top >= comfortTop && bounds.bottom <= comfortBottom) return
    // 3) Otherwise glide so the row settles about a third of the way down the screen, with the row above still peeking in.
    Motion.scrollVerticalTo(sv, bounds.top - (h * 0.34f).toInt())
}

private fun panHorizontally(sv: CalmHorizontalScrollView, view: View, density: Float) {
    val bounds = Rect(0, 0, view.width, view.height)
    sv.offsetDescendantRectToMyCoords(view, bounds)
    val margin = (72 * density).toInt()
    val left = sv.scrollX + margin
    val right = sv.scrollX + sv.width - margin
    val target = when {
        bounds.left < left -> bounds.left - margin
        bounds.right > right -> bounds.right - sv.width + margin
        else -> return
    }
    Motion.scrollHorizontalTo(sv, target)
}
