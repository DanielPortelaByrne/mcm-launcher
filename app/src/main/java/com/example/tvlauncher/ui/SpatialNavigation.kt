package com.example.tvlauncher.ui

import android.view.View
import com.example.tvlauncher.design.Motion
import kotlin.math.abs

/**
 * Utilities for implementing micro-parallax spatial navigation:
 * - Outgoing section opacity reduces subtly
 * - Outgoing section scale approaches 0.985
 * - Incoming section settles from a small vertical offset
 * - Section heading moves fractionally differently from content
 * - Backdrop/painting moves at different velocity from foreground
 *
 * Creates the sensation of navigating through one designed space rather than
 * independent flying content.
 *
 * Apply these to a CalmScrollView via onScrollChanged listener.
 */
object SpatialNavigation {
    /**
     * Update parallax effects based on vertical scroll position.
     * Call from the ScrollView's onScrollChanged override or a scroll listener.
     */
    fun updateParallax(
        scrollY: Int,
        viewHeight: Int,
        parallaxBackdrop: View?,
        sections: List<View>
    ) {
        if (parallaxBackdrop != null && !Motion.animationsOn(parallaxBackdrop)) return

        // Backdrop parallax: moves at ~0.4x foreground speed (slower for depth)
        parallaxBackdrop?.translationY = scrollY * -0.4f

        // Update each section's micro-parallax
        for (section in sections) {
            val sectionTop = section.top.toFloat()
            val sectionBottom = section.bottom.toFloat()
            val sectionCenter = (sectionTop + sectionBottom) / 2

            // Calculate how "off-screen" this section is (-1 = fully above, 0 = centered, 1 = fully below)
            val viewportCenter = scrollY + viewHeight / 2f
            val distanceFromCenter = (sectionCenter - viewportCenter) / (viewHeight / 2f)
            val absDistance = abs(distanceFromCenter)

            // Incoming section settles from slight vertical offset
            if (distanceFromCenter > 0) {
                // Section below current view: slight downward offset that eases as it enters
                section.translationY = (distanceFromCenter * 8f).coerceIn(0f, 8f)
            } else {
                // Section above current view: slight upward offset
                section.translationY = (distanceFromCenter * 8f).coerceIn(-8f, 0f)
            }

            // Outgoing section fades and slightly shrinks
            if (absDistance > 1f) {
                val fadeOutFraction = (absDistance - 1f).coerceIn(0f, 0.3f)  // Max 30% fade over scroll
                section.alpha = 1f - (fadeOutFraction * 0.25f)
                section.scaleY = 1f - (fadeOutFraction * 0.01f)
            } else {
                section.alpha = 1f
                section.scaleY = 1f
            }
        }
    }

    /**
     * Subtle parallax for horizontal scroll rails: content settles from offset as it enters view.
     */
    fun updateHorizontalParallax(
        scrollX: Int,
        viewWidth: Int,
        items: List<View>
    ) {
        // Subtle horizontal parallax: items offset slightly based on horizontal position
        for (item in items) {
            val itemLeft = item.left.toFloat()
            val itemRight = item.right.toFloat()
            val itemCenter = (itemLeft + itemRight) / 2

            val viewportCenter = scrollX + viewWidth / 2f
            val distanceFromCenter = (itemCenter - viewportCenter) / (viewWidth / 2f)

            // Slight horizontal offset as items enter
            item.translationX = (distanceFromCenter * 4f).coerceIn(-6f, 6f)
        }
    }
}
