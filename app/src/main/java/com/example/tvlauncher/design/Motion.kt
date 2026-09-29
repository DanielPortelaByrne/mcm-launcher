package com.example.tvlauncher.design

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import android.provider.Settings.Global
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.example.tvlauncher.R
import kotlin.math.abs
import kotlin.math.max

/**
 * The launcher's one motion language: slow, soft and slightly lagging, like a Mid-Century room that
 * happens to move. Everything animates through these curves and durations so the feel stays consistent.
 * If the system's animation scale is 0 (accessibility "remove animations") everything becomes instant.
 */
object Motion {
    /** Long soft tail: it leaves quickly, then drifts to rest. Used for panning the page and rails. */
    val SWOOSH = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** Gentle ease-out with no bounce. Used for focus, fades and sheets. */
    val SETTLE = PathInterpolator(0f, 0f, 0.2f, 1f)

    /** Master switch: false makes every scroll, focus and select change instant. */
    const val ENABLED = true

    const val FOCUS_IN_MS = 160L
    const val FOCUS_OUT_MS = 120L
    const val FADE_MS = 200L

    private class Running(val animator: ValueAnimator, val target: Int)

    fun animationsOn(view: View): Boolean = ENABLED && try {
        Global.getFloat(view.context.contentResolver, Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    } catch (_: Exception) { true }

    /** How long a pan of [distancePx] takes: never abrupt, never tedious. */
    fun panDuration(distancePx: Int): Long = (300 + abs(distancePx) * 0.06f).toLong().coerceIn(320, 380)

    // ---- panning -----------------------------------------------------------------------------------

    fun scrollVerticalTo(sv: ScrollView, target: Int) {
        val content = sv.getChildAt(0) ?: return
        val max = max(0, content.height - (sv.height - sv.paddingTop - sv.paddingBottom))
        pan(sv, target.coerceIn(0, max), sv.scrollY) { sv.scrollTo(sv.scrollX, it) }
    }

    fun scrollHorizontalTo(sv: HorizontalScrollView, target: Int) {
        val content = sv.getChildAt(0) ?: return
        val max = max(0, content.width - (sv.width - sv.paddingLeft - sv.paddingRight))
        pan(sv, target.coerceIn(0, max), sv.scrollX) { sv.scrollTo(it, sv.scrollY) }
    }

    private fun pan(view: View, to: Int, from: Int, apply: (Int) -> Unit) {
        val running = view.getTag(R.id.motion_pan) as? Running
        if (running != null && running.target == to && running.animator.isRunning) return
        running?.animator?.cancel()
        if (to == from) return
        if (!animationsOn(view)) { apply(to); return }
        // Restart from where the view is right now, so quick D-pad presses glide on instead of jerking.
        val animator = ValueAnimator.ofInt(from, to).apply {
            duration = panDuration(to - from)
            interpolator = SWOOSH
            addUpdateListener { apply(it.animatedValue as Int) }
        }
        view.setTag(R.id.motion_pan, Running(animator, to))
        animator.start()
    }

    // ---- small pieces ------------------------------------------------------------------------------

    /** Fades a drawable's alpha; [onEnd] runs when it finishes (not if cancelled). */
    fun fadeDrawable(view: View, drawable: Drawable, from: Int, to: Int, ms: Long, onEnd: (() -> Unit)? = null) {
        val old = view.getTag(R.id.motion_fade) as? Animator
        old?.cancel()
        if (!animationsOn(view)) { drawable.alpha = to; onEnd?.invoke(); return }
        val a = ObjectAnimator.ofInt(drawable, "alpha", from, to).apply {
            duration = ms; interpolator = SETTLE
            if (onEnd != null) addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) onEnd() }
            })
        }
        view.setTag(R.id.motion_fade, a)
        a.start()
    }

    /** Glides a label's colour instead of snapping it. */
    fun tweenTextColor(tv: TextView, to: Int, ms: Long = 80L) {
        (tv.getTag(R.id.motion_text) as? Animator)?.cancel()
        val from = tv.currentTextColor
        if (from == to) return
        if (!animationsOn(tv)) { tv.setTextColor(to); return }
        val a = ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = ms; interpolator = SETTLE
            addUpdateListener { tv.setTextColor(it.animatedValue as Int) }
        }
        tv.setTag(R.id.motion_text, a)
        a.start()
    }

    /** A soft dip and a gentle settle back, so "select" feels pressed rather than switched. */
    fun press(view: View, restScale: Float) {
        if (!animationsOn(view)) return
        view.animate().cancel()
        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(60).setInterpolator(SETTLE).withEndAction {
            view.animate().scaleX(restScale).scaleY(restScale).setDuration(160).setInterpolator(SETTLE).start()
        }.start()
    }

    /** Fades [container] in and lifts [card] a short way into place (no scale), for sheets and menus. */
    fun enter(container: View, card: View?, risePx: Float) {
        if (!animationsOn(container)) return
        container.animate().cancel()
        container.alpha = 0f
        container.animate().alpha(1f).setDuration(220).setInterpolator(SETTLE).start()
        card?.let {
            val rise = kotlin.math.min(abs(risePx), 12f * container.resources.displayMetrics.density) * (if (risePx < 0) -1f else 1f)
            it.animate().cancel()
            it.translationY = rise
            it.animate().translationY(0f).setDuration(220).setInterpolator(SWOOSH).start()
        }
    }
}
