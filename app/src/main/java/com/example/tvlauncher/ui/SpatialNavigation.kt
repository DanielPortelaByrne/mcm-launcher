package com.example.tvlauncher.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.example.tvlauncher.R
import com.example.tvlauncher.design.Motion

/**
 * Micro-depth for the home page as it pans: rows leaving over the top edge recede very slightly (a little
 * smaller and dimmer, as if further away), rows arriving from below settle up the last few dp into place,
 * and headings arrive a touch later than their content. Everything is a pure function of the scroll
 * position, so rapid presses, retargeted pans and cancelled glides can never leave a row in between.
 */
object SpatialNavigation {
    private const val OUT_SCALE = 0.015f
    private const val OUT_ALPHA = 0.3f
    private const val IN_OFFSET_DP = 14f
    private const val HEADING_LAG = 1.5f

    /** Re-derives every layer's transform from [scrollY]. [page] is the scroll view's single child. */
    fun update(page: ViewGroup, scrollY: Int, viewport: Int) {
        if (viewport <= 0) return
        val on = Motion.animationsOn(page)
        val d = page.resources.displayMetrics.density
        forEachLayer(page) { layer, top ->
            if (layer.visibility != View.VISIBLE || layer.height == 0) return@forEachLayer
            if (!on) { reset(layer); return@forEachLayer }
            val center = top + layer.height / 2f
            val out = ((scrollY + viewport * 0.15f - center) / (viewport * 0.3f)).coerceIn(0f, 1f)
            val incoming = ((center - (scrollY + viewport * 0.85f)) / (viewport * 0.3f)).coerceIn(0f, 1f)
            val heading = layer is TextView || layer.getTag(R.id.section_heading) == true
            val scale = 1f - OUT_SCALE * out
            layer.pivotX = layer.width / 2f; layer.pivotY = layer.height.toFloat()
            layer.scaleX = scale; layer.scaleY = scale
            layer.translationY = incoming * IN_OFFSET_DP * d * (if (heading) HEADING_LAG else 1f)
            setAlpha(layer, 1f - OUT_ALPHA * out)
        }
    }

    /** Direct children of the page, with the discover column's sections treated as layers of their own. */
    private inline fun forEachLayer(page: ViewGroup, block: (View, Float) -> Unit) {
        for (i in 0 until page.childCount) {
            val child = page.getChildAt(i)
            if (child.id == R.id.discoverSections && child is ViewGroup) {
                for (j in 0 until child.childCount) block(child.getChildAt(j), (child.top + child.getChildAt(j).top).toFloat())
            } else block(child, child.top.toFloat())
        }
    }

    /** Dimmed rows get a cached GPU layer (their content is still while they recede), others draw directly. */
    private fun setAlpha(layer: View, alpha: Float) {
        if (layer.alpha == alpha) return
        layer.alpha = alpha
        val want = if (alpha < 1f && layer is ViewGroup) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        if (layer.layerType != want) layer.setLayerType(want, null)
    }

    private fun reset(layer: View) {
        layer.scaleX = 1f; layer.scaleY = 1f; layer.translationY = 0f
        setAlpha(layer, 1f)
    }
}
