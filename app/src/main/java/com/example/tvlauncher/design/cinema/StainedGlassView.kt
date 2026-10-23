package com.example.tvlauncher.design.cinema

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import com.example.tvlauncher.design.Motion

/**
 * The paused picture slowly turning to glass: a still of the frame seen through a backlit stained-glass window. It
 * ripples gently, frosts over, breaks into leaded panes that each bend the picture a little differently, and glows
 * warm from behind. [start] begins the change, which takes [MORPH_MS]; [stop] fades it away.
 *
 * The glass is worked out on the GPU (a shader, Android 13 and later) at a third of the size and scaled up, which
 * the frost hides; it redraws about 24 times a second while it turns, then holds still. Older systems get a plain soft blur.
 */
class StainedGlassView(context: Context, private val fallbackBlur: (Bitmap) -> Bitmap) : FrameLayout(context) {
    private var frame: Bitmap? = null
    private var startedAt = 0L
    private var running = false
    private val shader: Any? = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(GLASS) else null

    /** The picture itself, at half the screen's size; the glass is applied to it and it is drawn back at full size. */
    private val pane = object : View(context) {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dst = Rect()
        private val src = Rect()
        override fun onDraw(canvas: Canvas) {
            val f = frame ?: return
            // Cropped to fill the screen (a wide film's frame has no bars to keep): the whole window is glass.
            val scale = maxOf(width / f.width.toFloat(), height / f.height.toFloat())
            val sw = (width / scale).toInt().coerceAtMost(f.width); val sh = (height / scale).toInt().coerceAtMost(f.height)
            src.set((f.width - sw) / 2, (f.height - sh) / 2, (f.width + sw) / 2, (f.height + sh) / 2)
            dst.set(0, 0, width, height)
            canvas.drawBitmap(f, src, dst, paint)
        }
    }

    init {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        pane.pivotX = 0f; pane.pivotY = 0f; pane.scaleX = SCALE.toFloat(); pane.scaleY = SCALE.toFloat()
        addView(pane, LayoutParams(1, 1))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pane.layoutParams = LayoutParams((w + SCALE - 1) / SCALE, (h + SCALE - 1) / SCALE)
    }

    /** Begins turning [still] (the paused frame) into glass. */
    fun start(still: Bitmap) {
        stopNow()
        frame = if (shader == null) fallbackBlur(still) else still
        startedAt = SystemClock.uptimeMillis()
        running = true
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(if (Motion.animationsOn(this)) 1500L else 0L).start()
        pane.invalidate()
        if (shader == null) pane.setRenderEffect(null) else tick.run()
    }

    /** Fades the glass away (playback resumed, or the paused screen closed). */
    fun stop() {
        if (!running && visibility != VISIBLE) return
        running = false
        removeCallbacks(tick)
        animate().cancel()
        animate().alpha(0f).setDuration(220L).withEndAction { stopNow() }.start()
    }

    private fun stopNow() {
        running = false
        removeCallbacks(tick)
        animate().cancel()
        visibility = GONE
        if (Build.VERSION.SDK_INT >= 31) pane.setRenderEffect(null)
        frame = null
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running || !isAttachedToWindow || Build.VERSION.SDK_INT < 33) return
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val still = !Motion.animationsOn(this@StainedGlassView)
            val linear = if (still) 1f else (elapsed / MORPH_MS.toFloat()).coerceIn(0f, 1f)
            val amount = linear * linear * (3 - 2 * linear)   // ease in and out
            apply(amount, if (still) 0f else elapsed / 1000f)
            // Once it has turned, it holds still: a moving window would cost the paused screen its smoothness.
            if (!still && linear < 1f) postDelayed(this, FRAME_MS)
        }
    }

    @RequiresApi(33)
    private fun apply(amount: Float, seconds: Float) {
        val s = shader as RuntimeShader
        val w = pane.width.coerceAtLeast(1).toFloat(); val h = pane.height.coerceAtLeast(1).toFloat()
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("t", seconds)
        s.setFloatUniform("amount", amount)
        // The frost thickens with the glass: the blur goes in first, the panes and the light are drawn over what it leaves.
        val radius = 1f + 32f / SCALE * amount
        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        pane.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(s, "image"), blur))
    }

    override fun onDetachedFromWindow() { stopNow(); super.onDetachedFromWindow() }

    companion object {
        /** How long the picture takes to turn fully to glass. */
        const val MORPH_MS = 3200L
        private const val FRAME_MS = 42L
        /** The glass is worked out at 1/SCALE of the screen's size. */
        private const val SCALE = 3

        /** The glass, in AGSL. `amount` 0 is the plain picture, 1 the finished window. */
        private const val GLASS = """
uniform shader image;
uniform float2 size;
uniform float t;
uniform float amount;

float2 hash2(float2 p) {
    p = float2(dot(p, float2(127.1, 311.7)), dot(p, float2(269.5, 183.3)));
    return fract(sin(p) * 43758.5453);
}

half4 main(float2 xy) {
    float2 uv = xy / size;
    float aspect = size.x / size.y;
    float2 q = float2(uv.x * aspect, uv.y);

    // Rolled glass: slow, crossing ripples.
    float w1 = sin(q.y * 13.0 + t * 0.55 + sin(q.x * 5.0 + t * 0.30) * 1.6);
    float w2 = sin(q.x * 10.0 - t * 0.40 + sin(q.y * 4.5 - t * 0.22) * 1.9);
    float2 wave = float2(w1, w2) * 0.016 * amount;

    // Leaded panes: irregular cells that drift very slightly.
    float2 g = q * 5.2 + wave * 5.0;
    float2 ip = floor(g);
    float2 fp = fract(g);
    float d1 = 8.0;
    float d2 = 8.0;
    float2 cell = float2(0.0);
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            float2 o = float2(float(i), float(j));
            float2 h = hash2(ip + o);
            float2 r = o + 0.5 + 0.38 * sin(t * 0.12 + 6.2831 * h) - fp;
            float d = dot(r, r);
            if (d < d1) { d2 = d1; d1 = d; cell = ip + o; }
            else if (d < d2) { d2 = d; }
        }
    }
    float edge = sqrt(d2) - sqrt(d1);
    float2 h = hash2(cell);

    // Each pane bends the picture its own way.
    float2 bend = (h - 0.5) * 0.035 * amount;
    float2 at = clamp(uv + wave + bend, 0.0, 1.0) * size;
    float3 c = float3(image.eval(at).rgb);

    // Lit from behind: richer colour, brighter, a warm glow from up and to the right.
    float lum = dot(c, float3(0.299, 0.587, 0.114));
    float3 col = mix(float3(lum), c, 1.0 + 0.7 * amount);
    float3 tint = mix(float3(1.0, 0.80, 0.52), mix(float3(0.95, 0.45, 0.32), float3(0.55, 0.72, 1.0), h.y), h.x);
    col *= mix(float3(1.0), tint * 1.2, 0.30 * amount);
    float2 lp = float2(0.68, 0.18);
    float glow = exp(-length((uv - lp) * float2(aspect, 1.0)) * 1.5);
    col += float3(1.0, 0.76, 0.42) * glow * 0.32 * amount;
    col *= 1.0 + 0.30 * amount;

    // Light catching the ripples, and a bevel just inside each lead line.
    col += pow(0.5 + 0.5 * w1, 8.0) * 0.07 * amount;
    float bevel = smoothstep(0.05, 0.09, edge) * (1.0 - smoothstep(0.09, 0.17, edge));
    col += bevel * 0.07 * amount;

    // The lead itself.
    float lead = 1.0 - smoothstep(0.018, 0.05, edge);
    col = mix(col, col * 0.18, lead * 0.85 * amount);

    return half4(half3(clamp(mix(c, col, amount), 0.0, 1.0)), 1.0);
}
"""
    }
}
