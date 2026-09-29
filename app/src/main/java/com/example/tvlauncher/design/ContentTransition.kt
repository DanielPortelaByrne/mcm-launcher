package com.example.tvlauncher.design

import android.animation.ValueAnimator
import android.view.View
import android.widget.ImageView
import android.widget.TextView

/**
 * Animated content replacement for dynamic home widgets:
 * - Random Letterboxd film changes
 * - Recipe changes
 * - Currently playing track changes
 * - Project status changes
 * - Other home widgets refreshing
 *
 * Transitions are restrained and only applied when the section is visible.
 * Avoids animating every background-refresh if the user isn't looking.
 */
object ContentTransition {
    private const val FADE_THROUGH_MS = 180L
    private const val SLIDE_FADE_MS = 220L

    /**
     * Simple fade-through: old content fades out, new content fades in.
     * Used for text replacements, status changes, etc.
     */
    fun fadeThroughText(view: TextView, oldText: CharSequence, newText: CharSequence) {
        if (!Motion.animationsOn(view)) {
            view.text = newText
            return
        }

        // Fade out
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(FADE_THROUGH_MS / 2)
            .setInterpolator(Motion.SETTLE)
            .withEndAction {
                // Switch text and fade back in
                view.text = newText
                view.animate()
                    .alpha(1f)
                    .setDuration(FADE_THROUGH_MS / 2)
                    .setInterpolator(Motion.SETTLE)
                    .start()
            }
            .start()
    }

    /**
     * Slide and fade: old image recedes to the left and fades while new image enters from right.
     * Used for poster/image replacements in Continue Watching, painting shelf, etc.
     */
    fun slideAndFadeImage(
        view: ImageView,
        fromTranslation: Float = 0f,
        newDrawable: android.graphics.drawable.Drawable?
    ) {
        if (!Motion.animationsOn(view)) {
            view.setImageDrawable(newDrawable)
            view.alpha = 1f
            view.translationX = 0f
            return
        }

        // Recede outgoing image to left
        view.animate().cancel()
        view.animate()
            .translationX(-12f * view.resources.displayMetrics.density)
            .alpha(0f)
            .setDuration(SLIDE_FADE_MS)
            .setInterpolator(Motion.SETTLE)
            .withEndAction {
                // Set new image with incoming from-right offset
                view.setImageDrawable(newDrawable)
                view.translationX = 12f * view.resources.displayMetrics.density
                view.alpha = 0f
                // Slide in new image
                view.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(SLIDE_FADE_MS)
                    .setInterpolator(Motion.SWOOSH)
                    .start()
            }
            .start()
    }

    /**
     * Crossfade for image replacement (simpler than slide, good for thumbnails).
     * Old image fades out while new image fades in on top.
     */
    fun crossfadeImage(view: ImageView, newDrawable: android.graphics.drawable.Drawable?) {
        if (!Motion.animationsOn(view)) {
            view.setImageDrawable(newDrawable)
            return
        }

        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(FADE_THROUGH_MS)
            .setInterpolator(Motion.SETTLE)
            .withEndAction {
                view.setImageDrawable(newDrawable)
                view.animate()
                    .alpha(1f)
                    .setDuration(FADE_THROUGH_MS)
                    .setInterpolator(Motion.SETTLE)
                    .start()
            }
            .start()
    }

    /**
     * Animated visibility toggle: fade between visible and gone states.
     * Used when widgets appear/disappear based on data availability.
     */
    fun fadeVisibility(view: View, show: Boolean, duration: Long = FADE_THROUGH_MS) {
        if (!Motion.animationsOn(view)) {
            view.alpha = if (show) 1f else 0f
            view.visibility = if (show) View.VISIBLE else View.GONE
            return
        }

        val targetAlpha = if (show) 1f else 0f
        view.animate().cancel()
        view.animate()
            .alpha(targetAlpha)
            .setDuration(duration)
            .setInterpolator(Motion.SETTLE)
            .withEndAction {
                if (!show) view.visibility = View.GONE
            }
            .start()

        if (show) view.visibility = View.VISIBLE
    }
}
