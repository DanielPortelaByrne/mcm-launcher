package com.example.tvlauncher.design.cinema

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
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
import java.util.concurrent.Executors
import kotlin.math.floor
import kotlin.math.tanh

/**
 * The paused picture slowly turning to backlit hammered glass: molten amber blobs with bright rims and dark creases,
 * the frame behind broken up by refraction. [start] begins the change, which takes [MORPH_MS]; [stop] fades it away.
 *
 * The glass surface (a bumpy height field and its slopes) is worked out once on the CPU and kept; a GPU shader
 * (Android 13 and later) bends the picture through it and lights it, at a third of the size scaled up. It redraws only while
 * it turns, then holds still. Older systems get a plain soft blur.
 */
class HammeredGlassView(context: Context, private val fallbackBlur: (Bitmap) -> Bitmap) : FrameLayout(context) {
    /** How far the glass has formed, 0 to 1, as it changes (the paused screen eases its dimming back with it). */
    var onAmount: (Float) -> Unit = {}
    private var frame: Bitmap? = null
    private var startedAt = 0L
    private var running = false
    private val shader: Any? = if (Build.VERSION.SDK_INT >= 33) RuntimeShader(GLASS) else null
    private var surfaceBound = false

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
        if (shader != null) prepareSurface()
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
        animate().alpha(1f).setDuration(if (Motion.animationsOn(this)) 1200L else 0L).start()
        pane.invalidate()
        if (shader != null) tick.run()
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
            // The surface is still being worked out (only on the very first pause): try again shortly.
            if (surface == null) { postDelayed(this, 100); return }
            val elapsed = SystemClock.uptimeMillis() - startedAt
            val still = !Motion.animationsOn(this@HammeredGlassView)
            val linear = if (still) 1f else (elapsed / MORPH_MS.toFloat()).coerceIn(0f, 1f)
            apply(linear * linear * (3 - 2 * linear))
            // Once it has turned, it holds still.
            if (!still && linear < 1f) postDelayed(this, FRAME_MS)
        }
    }

    @RequiresApi(33)
    private fun apply(amount: Float) {
        val s = shader as RuntimeShader
        val map = surface ?: return
        if (!surfaceBound) { s.setInputShader("normals", BitmapShader(map, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)); surfaceBound = true }
        s.setFloatUniform("size", pane.width.coerceAtLeast(1).toFloat(), pane.height.coerceAtLeast(1).toFloat())
        s.setFloatUniform("mapSize", map.width.toFloat(), map.height.toFloat())
        s.setFloatUniform("amount", amount)
        s.setFloatUniform("refract", 34f / SCALE)
        // The glass slides the last little way into place as it forms.
        s.setFloatUniform("settle", 52f / SCALE * (1f - amount))
        // (A RenderEffect blur's radius is about twice the spread it gives.)
        val radius = 0.5f + 24f / SCALE * amount
        onAmount(amount)
        val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        pane.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(s, "image"), blur))
    }

    override fun onDetachedFromWindow() { stopNow(); super.onDetachedFromWindow() }

    companion object {
        /** How long the picture takes to turn fully to glass. */
        const val MORPH_MS = 3200L
        private const val FRAME_MS = 33L
        /** The glass is worked out at 1/SCALE of the screen's size. */
        private const val SCALE = 3

        // --- The glass surface, made once ----------------------------------------------------------------

        @Volatile private var surface: Bitmap? = null
        private val maker = Executors.newSingleThreadExecutor { r -> Thread(r, "glass-surface").apply { priority = Thread.MIN_PRIORITY } }
        @Volatile private var making = false

        private fun prepareSurface() {
            if (surface != null || making) return
            making = true
            maker.execute { surface = makeSurface(1920 / SCALE, 1080 / SCALE) }
        }

        // Blob size in surface pixels (wider than tall, like rolled glass), how far the blobs are pushed about, how sharp
        // their crests are, and how strongly the slopes are felt.
        private const val CELL_X = 60f / SCALE
        private const val CELL_Y = 40f / SCALE
        private const val WARP = 0.9f
        private const val SHARP = 4f
        private const val BUMP = 30f / SCALE

        /**
         * The surface as a bitmap: red and green hold the slope across and down (0.5 is flat, the full range ±4),
         * blue the height. Value noise in three octaves, its coordinates pushed about by a broader noise, its levels
         * pushed towards the ends so the blobs have flat tops and steep sides.
         */
        fun makeSurface(w: Int, h: Int): Bitmap {
            val height = FloatArray(w * h)
            for (py in 0 until h) for (px in 0 until w) {
                var x = px / CELL_X; var y = py / CELL_Y
                val wx = (noise(x * 0.45f, y * 0.45f, 11) - 0.5f) * WARP
                val wy = (noise(x * 0.45f, y * 0.45f, 12) - 0.5f) * WARP
                x += wx; y += wy
                val v = (noise(x, y, 1) + 0.5f * noise(x * 2.03f, y * 2.03f, 2) + 0.25f * noise(x * 4.1f, y * 4.1f, 3)) / 1.75f
                height[py * w + px] = 0.5f + 0.5f * tanh((v - 0.5f) * SHARP)
            }
            val pixels = IntArray(w * h)
            for (py in 0 until h) for (px in 0 until w) {
                val l = height[py * w + (px - 1).coerceAtLeast(0)]; val r = height[py * w + (px + 1).coerceAtMost(w - 1)]
                val u = height[(py - 1).coerceAtLeast(0) * w + px]; val d = height[(py + 1).coerceAtMost(h - 1) * w + px]
                val gx = (r - l) * 0.5f * BUMP; val gy = (d - u) * 0.5f * BUMP
                fun enc(g: Float) = ((g / 4f * 0.5f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                pixels[py * w + px] = (0xFF shl 24) or (enc(gx) shl 16) or (enc(gy) shl 8) or (height[py * w + px] * 255f).toInt()
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        private fun hash(ix: Int, iy: Int, seed: Int): Float {
            var h = ix * 374761393 + iy * 668265263 + seed * 2147483647
            h = (h xor (h ushr 13)) * 1274126177
            return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
        }

        private fun noise(x: Float, y: Float, seed: Int): Float {
            val fx0 = floor(x); val fy0 = floor(y)
            val ix = fx0.toInt(); val iy = fy0.toInt()
            val fx = x - fx0; val fy = y - fy0
            val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
            val a = hash(ix, iy, seed); val b = hash(ix + 1, iy, seed)
            val c = hash(ix, iy + 1, seed); val d = hash(ix + 1, iy + 1, seed)
            return (a + (b - a) * ux) * (1 - uy) + (c + (d - c) * ux) * uy
        }

        /** The glass, in AGSL. `amount` 0 is the plain picture, 1 the finished glass. */
        private const val GLASS = """
uniform shader image;
uniform shader normals;
uniform float2 size;
uniform float2 mapSize;
uniform float amount;
uniform float settle;
uniform float refract;

half4 main(float2 xy) {
    float2 m = clamp((xy + float2(0.0, settle)) * mapSize / size, float2(0.5), mapSize - 0.5);
    float3 nm = float3(normals.eval(m).rgb);
    float2 g = (nm.xy * 2.0 - 1.0) * 4.0 * amount;

    // Bent through the glass.
    float3 c0 = float3(image.eval(xy).rgb);
    float2 at = clamp(xy - g * refract, float2(0.5), size - 0.5);
    float3 c = float3(image.eval(at).rgb);

    // Lit from above left; highlights on the crests, shade on the far sides and in the creases.
    float3 n = normalize(float3(-g, 1.0));
    float3 L = normalize(float3(-0.35, -0.55, 0.76));
    float3 hv = normalize(L + float3(0.0, 0.0, 1.0));
    float diff = max(dot(n, L), 0.0);
    float spec = pow(max(dot(n, hv), 0.0), 50.0);
    float slope = 1.0 - n.z;

    // Backlit amber: brightness through dark brown, orange and pale gold, over the scene's own (richer) colour.
    float lum = dot(c, float3(0.299, 0.587, 0.114));
    float3 col = lum + (c - lum) * 1.5;
    float t = clamp(lum * 1.25, 0.0, 1.0) * 3.0;
    float3 s0 = float3(0.10, 0.02, 0.01);
    float3 s1 = float3(0.55, 0.16, 0.03);
    float3 s2 = float3(0.93, 0.48, 0.10);
    float3 s3 = float3(1.0, 0.80, 0.45);
    float3 amber = t < 1.0 ? mix(s0, s1, t) : (t < 2.0 ? mix(s1, s2, t - 1.0) : mix(s2, s3, t - 2.0));
    col = mix(col, amber, 0.8) * 1.5;
    col *= 1.0 - 0.45 * clamp(slope * 2.0, 0.0, 1.0);
    col *= 0.1 + 0.9 * diff;
    col += spec * (col * 1.6 + float3(1.0, 0.72, 0.38) * 0.25) * 1.8;

    return half4(half3(clamp(mix(c0, col, amount), 0.0, 1.0)), 1.0);
}
"""
    }
}
