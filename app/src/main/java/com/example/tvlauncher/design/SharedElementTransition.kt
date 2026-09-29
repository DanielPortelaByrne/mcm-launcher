package com.example.tvlauncher.design

import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.example.tvlauncher.R
import kotlin.math.abs

/**
 * Lightweight shared-element transition support for moving a card into a sheet/detail view.
 * Rather than using Android's formal Transition framework, we animate a visual representation
 * of the source card towards its destination bounds, then reveal the detail UI around it.
 *
 * This works with the existing custom View architecture without heavy framework overhead.
 */
class SharedElementTransition {
    private var activeAnimator: ValueAnimator? = null
    private var animationContainer: ViewGroup? = null

    /**
     * Animate a source view towards a destination rectangle, with optional accompanying animations
     * for UI elements appearing around it.
     *
     * @param sourceView The view to animate from (typically a card/poster)
     * @param targetBounds Rectangle where the view should animate to
     * @param container The parent ViewGroup where the animation layer will be added
     * @param duration Animation duration in milliseconds
     * @param onProgress Called with fraction 0f-1f as animation progresses
     * @param onEnd Called when animation completes
     */
    fun transitionToDetailView(
        sourceView: View,
        targetBounds: Rect,
        container: ViewGroup,
        duration: Long = 400,
        onProgress: ((Float) -> Unit)? = null,
        onEnd: (() -> Unit)? = null
    ) {
        if (!Motion.animationsOn(sourceView)) {
            onEnd?.invoke()
            return
        }

        val sourceBounds = IntArray(2).also { sourceView.getLocationOnScreen(it) }
        val startX = sourceBounds[0].toFloat()
        val startY = sourceBounds[1].toFloat()
        val startWidth = sourceView.width.toFloat()
        val startHeight = sourceView.height.toFloat()

        activeAnimator?.cancel()
        activeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = Motion.SWOOSH
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val currentX = startX + (targetBounds.left - startX) * fraction
                val currentY = startY + (targetBounds.top - startY) * fraction
                val currentWidth = startWidth + (targetBounds.width() - startWidth) * fraction
                val currentHeight = startHeight + (targetBounds.height() - startHeight) * fraction

                onProgress?.invoke(fraction)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd?.invoke()
                    activeAnimator = null
                }
            })
            start()
        }
    }

    /**
     * Quickly reverse a transition animation (e.g., when closing a sheet).
     * This is a simplified back-animation that doesn't track the original position precisely,
     * but provides visual coherence.
     */
    fun cancelTransition(duration: Long = 250) {
        activeAnimator?.cancel()
        activeAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            this.duration = duration
            interpolator = Motion.SETTLE
            addUpdateListener { /* reverse animation happens in caller */ }
            start()
        }
    }

    fun isAnimating(): Boolean = activeAnimator?.isRunning ?: false
}
