package com.example.tvlauncher.design

import android.graphics.Color
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.example.tvlauncher.R
import kotlin.math.abs

/**
 * Section-aware backdrop theme: the launcher subtly shifts atmospheric tone based on the
 * currently focused/active section. Transitions are slow (350–650ms) and atmospheric,
 * meant to be noticed subconsciously rather than as obvious colour changes.
 *
 * This centralizes theme state rather than embedding colours into individual sections.
 */
object SectionTheme {
    private val transitionCurve = PathInterpolator(0f, 0f, 0.2f, 1f)  // Same as Motion.SETTLE
    private const val BASE_TRANSITION_MS = 500L

    enum class Section {
        APPS,                    // Neutral warm walnut
        CONTINUE_WATCHING,       // Slightly darker/cooler
        TONIGHT,                 // Slightly warmer amber
        NOW_SPINNING,            // Restrained teal/rust influence
        PROJECTS,                // Olive/walnut
        ART_MODE                 // Art-focused
    }

    /**
     * Get the accent colour overlay tint for a section (very subtle, meant to influence backdrop).
     * Returns a 32-bit ARGB colour with low alpha (15-25%) for a subconscious effect.
     */
    fun getAccentTint(section: Section): Int = when (section) {
        Section.APPS -> Color.argb(0, 0, 0, 0)                      // Neutral, no tint
        Section.CONTINUE_WATCHING -> Color.argb(20, 20, 30, 60)     // Cool blue-grey
        Section.TONIGHT -> Color.argb(25, 180, 120, 60)             // Warm amber
        Section.NOW_SPINNING -> Color.argb(20, 80, 100, 90)         // Teal influence
        Section.PROJECTS -> Color.argb(15, 60, 70, 40)              // Olive
        Section.ART_MODE -> Color.argb(0, 0, 0, 0)                  // Art is the theme
    }

    /**
     * Transitions a view's background or tint colour to match the new section.
     * [view] should typically be the backdrop or a scrim/overlay layer.
     * This operates independently of the launcher's content animations.
     */
    fun transitionToSection(view: View, newSection: Section) {
        val from = (view.tag as? Int) ?: Color.argb(0, 0, 0, 0)
        val to = getAccentTint(newSection)
        view.tag = newSection

        if (from == to) return
        if (!Motion.animationsOn(view)) { view.setBackgroundColor(to); return }

        android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(), from, to).apply {
            duration = BASE_TRANSITION_MS
            interpolator = transitionCurve
            addUpdateListener { view.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }
}
