package com.example.tvlauncher.design.cinema

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.ViewOutlineProvider
import com.example.tvlauncher.design.Cinema

/**
 * Frosted glass: a blurred copy of whatever lies beneath it ([sources], drawn in this view's parent's coordinates),
 * tinted with [tint] and edged with a hairline, inside a pill of [radiusPx].
 *
 * Android Views have no backdrop blur, so on API 31+ the sources are re-recorded into a [RenderNode] carrying a GPU
 * blur effect. That happens only when [refresh] is called (page scrolls, focus moves, images arrive), never every
 * frame on its own. Older systems get the tint alone, made nearly opaque so text behind stays out of the way.
 */
class FrostView(context: Context, private val sources: List<View>, private val radiusPx: Float) : View(context) {
    private val node: RenderNode? = if (Build.VERSION.SDK_INT >= 31) RenderNode("frost").apply {
        setRenderEffect(RenderEffect.createBlurEffect(Cinema.pxf(context, 44), Cinema.pxf(context, 44), Shader.TileMode.CLAMP))
    } else null
    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Cinema.HAIRLINE }
    private var animateUntil = 0L

    /** The tint over the blur. Without blur it is forced close to opaque. */
    var tint: Int = Cinema.RAIL_FILL
        set(value) { field = value; invalidate() }

    init {
        isFocusable = false; isClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, Cinema.pxf(context, radiusPx)) }
        }
        clipToOutline = true
    }

    /** Redraw the blur now, and keep redrawing every frame for [forMs] while something beneath animates. */
    fun refresh(forMs: Long = 0L) {
        invalidate()
        if (forMs <= 0L) return
        val until = System.currentTimeMillis() + forMs
        if (until <= animateUntil) return
        val running = System.currentTimeMillis() < animateUntil
        animateUntil = until
        if (!running) Choreographer.getInstance().postFrameCallback(tick)
    }

    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isAttachedToWindow) return
            invalidate()
            if (System.currentTimeMillis() < animateUntil) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = node
        val blurred = n != null && canvas.isHardwareAccelerated
        if (blurred && Build.VERSION.SDK_INT >= 31) {
            n!!.setPosition(0, 0, width, height)
            val rec = n.beginRecording()
            rec.translate(-left.toFloat(), -top.toFloat())
            sources.forEach { if (it.visibility == VISIBLE) it.draw(rec) }
            n.endRecording()
            canvas.drawRenderNode(n)
        }
        fill.color = if (blurred) tint else Cinema.alpha(tint, 0.94f)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val r = Cinema.pxf(context, radiusPx)
        canvas.drawRoundRect(0.5f, 0.5f, width - 0.5f, height - 0.5f, r, r, line)
    }
}
