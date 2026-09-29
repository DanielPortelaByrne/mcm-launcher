package com.example.tvlauncher.design

import android.animation.ObjectAnimator
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.example.tvlauncher.R

/**
 * Enhanced sheet and overlay transitions that feel like physical UI layers rather than dialogs.
 *
 * When a sheet opens:
 * - Background page recedes very slightly (scale ~0.99, opacity -5%)
 * - Dimming/scrim interpolates smoothly
 * - Sheet enters with small rise and fade-in
 * - Focused object remains visually connected if relevant
 *
 * When closing: animation reverses smoothly.
 */
object SheetTransition {
    private const val SHEET_ENTER_DURATION_MS = 280L
    private const val SCRIM_FADE_MS = 220L
    private const val BACKGROUND_RECEDE_MS = 250L

    /**
     * Animate a sheet/overlay entrance with surrounding context:
     * - scrim fades in over duration
     * - sheet rises and fades in
     * - background page slightly recedes and dims
     */
    fun enterSheet(
        sheet: View,
        scrim: View?,
        backgroundContent: View?,
        risePx: Float = 24f
    ) {
        if (!Motion.animationsOn(sheet)) {
            sheet.alpha = 1f
            sheet.translationY = 0f
            scrim?.alpha = 1f
            backgroundContent?.alpha = 1f
            backgroundContent?.scaleX = 1f
            backgroundContent?.scaleY = 1f
            return
        }

        // Sheet: rise and fade in
        sheet.translationY = risePx
        sheet.alpha = 0f
        sheet.animate().cancel()
        sheet.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(SHEET_ENTER_DURATION_MS)
            .setInterpolator(Motion.SWOOSH)
            .start()

        // Scrim: fade in over slightly longer duration for atmospheric effect
        scrim?.alpha = 0f
        scrim?.animate()?.cancel()
        scrim?.animate()
            ?.alpha(1f)
            ?.setDuration(SCRIM_FADE_MS)
            ?.setInterpolator(Motion.SETTLE)
            ?.start()

        // Background: subtle recede and dim
        backgroundContent?.animate()?.cancel()
        backgroundContent?.animate()
            ?.scaleX(0.99f)
            ?.scaleY(0.99f)
            ?.alpha(0.95f)
            ?.setDuration(BACKGROUND_RECEDE_MS)
            ?.setInterpolator(Motion.SETTLE)
            ?.start()
    }

    /**
     * Animate a sheet/overlay exit with coordinated background restoration.
     */
    fun exitSheet(
        sheet: View,
        scrim: View?,
        backgroundContent: View?,
        exitDistance: Float = 24f,
        onEnd: (() -> Unit)? = null
    ) {
        if (!Motion.animationsOn(sheet)) {
            sheet.alpha = 0f
            sheet.translationY = exitDistance
            scrim?.alpha = 0f
            backgroundContent?.alpha = 1f
            backgroundContent?.scaleX = 1f
            backgroundContent?.scaleY = 1f
            onEnd?.invoke()
            return
        }

        // Sheet: recede down and fade out
        sheet.animate().cancel()
        sheet.animate()
            .translationY(exitDistance)
            .alpha(0f)
            .setDuration(SHEET_ENTER_DURATION_MS)
            .setInterpolator(Motion.SETTLE)
            .withEndAction { onEnd?.invoke() }
            .start()

        // Scrim: fade out
        scrim?.animate()?.cancel()
        scrim?.animate()
            ?.alpha(0f)
            ?.setDuration(SCRIM_FADE_MS)
            ?.setInterpolator(Motion.SETTLE)
            ?.start()

        // Background: restore scale and opacity
        backgroundContent?.animate()?.cancel()
        backgroundContent?.animate()
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.alpha(1f)
            ?.setDuration(BACKGROUND_RECEDE_MS)
            ?.setInterpolator(Motion.SETTLE)
            ?.start()
    }
}
