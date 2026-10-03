package com.example.tvlauncher.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Turns any app icon into a full-bleed circle: the artwork fills the whole
 * disc rather than sitting as a small rectangle inside a coloured ring.
 *
 * [from] keeps the app's own colours. [mcm] re-draws the logo as an ivory silhouette
 * on a single mid-century palette disc with an abstract motif behind it, so wildly
 * different brand colours sit together calmly.
 */
object RoundIcon {

    private const val SIZE = 256
    private const val ALPHA_THRESHOLD = 40
    private const val FALLBACK_BG = 0xFF3A2E22.toInt()
    private const val IVORY = 0xFFF3E9CF.toInt()

    /** Mid-century palette: terracotta, deep teal, ochre, olive, walnut, indigo. */
    private val PALETTE = intArrayOf(0xFFAF5938.toInt(), 0xFF28766C.toInt(), 0xFFB07C22.toInt(), 0xFF6B6F3A.toInt(), 0xFF795638.toInt(), 0xFF3E5C86.toInt())

    fun from(context: Context, source: Drawable): Drawable = circular(context, renderSquare(source))

    /** An abstract MCM version of [source]; falls back to the app's own colours if the logo can't be isolated. */
    fun mcm(context: Context, source: Drawable, seed: String): Drawable {
        val art = renderSquare(source)
        val mask = logoMask(source, art) ?: return circular(context, art)
        val hash = seed.hashCode() and 0x7fffffff
        val base = PALETTE[hash % PALETTE.size]

        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(base)
        drawMotif(canvas, base, (hash / PALETTE.size) % MOTIFS)

        val glyph = IntArray(SIZE * SIZE) { i -> (IVORY and 0x00FFFFFF) or ((mask[i] * 255f).toInt().coerceIn(0, 255) shl 24) }
        val glyphBitmap = Bitmap.createBitmap(glyph, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        canvas.save()
        canvas.scale(GLYPH_SCALE, GLYPH_SCALE, SIZE / 2f, SIZE / 2f)
        canvas.drawBitmap(glyphBitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        canvas.restore()
        return circular(context, out)
    }

    /** A solid disc with a tinted glyph, for synthetic tiles such as "All apps". */
    fun glyph(context: Context, @DrawableRes glyph: Int, @ColorRes background: Int): Drawable {
        val disc = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, background))
        }
        val mark = ContextCompat.getDrawable(context, glyph)!!.mutate().apply {
            setTint(ContextCompat.getColor(context, com.example.tvlauncher.R.color.ivory))
        }
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        disc.setBounds(0, 0, SIZE, SIZE)
        disc.draw(canvas)
        val inset = (SIZE * 0.27f).toInt()
        mark.setBounds(inset, inset, SIZE - inset, SIZE - inset)
        mark.draw(canvas)
        return circular(context, bitmap)
    }

    /**
     * A private copy of a cached icon. Cached drawables are shared, and a drawable keeps ONE set of bounds:
     * showing the same instance in a big shelf disc and a small badge makes them fight over its size.
     */
    fun own(context: Context, d: Drawable): Drawable =
        if (d is androidx.core.graphics.drawable.RoundedBitmapDrawable && d.bitmap != null) circular(context, d.bitmap!!)
        else d.constantState?.newDrawable(context.resources)?.mutate() ?: d

    private fun circular(context: Context, bitmap: Bitmap): Drawable =
        RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply {
            isCircular = true
            setAntiAlias(true)
        }

    // --- Rendering ----------------------------------------------------------

    private fun renderSquare(source: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (android.os.Build.VERSION.SDK_INT >= 26 && source is AdaptiveIconDrawable) {
            drawLayer(canvas, source.background)
            drawLayer(canvas, source.foreground)
        } else drawLegacy(canvas, source)
        return bitmap
    }

    /** Adaptive icons: the 72/108 visible window of a layer, scaled to fill the square. */
    private fun drawLayer(canvas: Canvas, layer: Drawable?) {
        val overscan = SIZE / 4
        layer?.apply { setBounds(-overscan, -overscan, SIZE + overscan, SIZE + overscan); draw(canvas) }
    }

    /** Legacy icons: crop away transparent padding, then fill the disc, backed by the icon's own edge colour. */
    private fun drawLegacy(canvas: Canvas, icon: Drawable) {
        val raw = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val rawCanvas = Canvas(raw)
        val w = icon.intrinsicWidth.takeIf { it > 0 } ?: SIZE
        val h = icon.intrinsicHeight.takeIf { it > 0 } ?: SIZE
        val scale = SIZE.toFloat() / max(w, h)
        val dw = (w * scale).toInt()
        val dh = (h * scale).toInt()
        val left = (SIZE - dw) / 2
        val top = (SIZE - dh) / 2
        icon.setBounds(left, top, left + dw, top + dh)
        icon.draw(rawCanvas)

        val content = opaqueBounds(raw)
        if (content == null) {
            canvas.drawColor(FALLBACK_BG)
            return
        }
        val side = minOf(SIZE, max(content.width(), content.height()))
        val src = Rect(
            (content.centerX() - side / 2).coerceIn(0, SIZE - side),
            (content.centerY() - side / 2).coerceIn(0, SIZE - side),
            0, 0
        ).also { it.right = it.left + side; it.bottom = it.top + side }

        canvas.drawColor(edgeColor(raw, src))
        canvas.drawBitmap(raw, src, Rect(0, 0, SIZE, SIZE), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
    }

    // --- Logo isolation -----------------------------------------------------

    /**
     * 0..1 coverage of the logo. Adaptive icons use their foreground layer's own
     * transparency; everything else is "whatever differs from the icon's edge colour".
     * Returns null when nothing usable stands out, so the caller can keep the original.
     */
    private fun logoMask(source: Drawable, art: Bitmap): FloatArray? {
        val n = SIZE * SIZE
        if (android.os.Build.VERSION.SDK_INT >= 26 && source is AdaptiveIconDrawable && source.foreground != null) {
            val fg = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            drawLayer(Canvas(fg), source.foreground)
            val px = IntArray(n).also { fg.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
            val coverage = px.count { Color.alpha(it) > ALPHA_THRESHOLD }.toFloat() / n
            if (coverage in 0.02f..0.72f) return FloatArray(n) { Color.alpha(px[it]) / 255f }
        }
        val px = IntArray(n).also { art.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
        val bg = edgeColor(art, Rect(0, 0, SIZE, SIZE))
        val mask = FloatArray(n) { i ->
            val p = px[i]
            val distance = sqrt(
                sq(Color.red(p) - Color.red(bg)) + sq(Color.green(p) - Color.green(bg)) + sq(Color.blue(p) - Color.blue(bg))
            ) / 441f
            smooth(distance, 0.26f, 0.55f) * (Color.alpha(p) / 255f)
        }
        val coverage = mask.count { it > 0.5f }.toFloat() / n
        return if (coverage in 0.015f..0.75f) mask else null
    }

    private fun sq(v: Int) = (v * v).toFloat()

    private fun smooth(x: Float, lo: Float, hi: Float): Float {
        val t = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // --- Abstract MCM motifs ------------------------------------------------

    private const val MOTIFS = 5
    private const val GLYPH_SCALE = 0.86f

    private fun drawMotif(canvas: Canvas, base: Int, kind: Int) {
        val s = SIZE.toFloat()
        val light = blend(base, Color.WHITE, 0.16f)
        val dark = blend(base, Color.BLACK, 0.20f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (kind) {
            0 -> { paint.color = light; canvas.drawCircle(s * 0.70f, s * 0.30f, s * 0.30f, paint) }          // sun
            1 -> { paint.color = dark; canvas.drawRect(0f, s * 0.52f, s, s, paint) }                          // horizon
            2 -> {                                                                                            // diagonal band
                paint.color = light
                canvas.drawPath(Path().apply { moveTo(0f, s * 0.64f); lineTo(s, s * 0.20f); lineTo(s, s * 0.46f); lineTo(0f, s * 0.90f); close() }, paint)
            }
            3 -> { paint.color = light; canvas.drawRoundRect(RectF(s * 0.20f, s * 0.36f, s * 0.80f, s * 1.10f), s * 0.30f, s * 0.30f, paint) } // arch
            else -> { paint.color = dark; canvas.drawCircle(0f, s, s * 0.74f, paint) }                       // quarter circle
        }
    }

    private fun blend(from: Int, to: Int, amount: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt()
    )

    // --- Pixel helpers ------------------------------------------------------

    private fun opaqueBounds(bitmap: Bitmap): Rect? {
        val pixels = IntArray(SIZE * SIZE)
        bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        var minX = SIZE; var minY = SIZE; var maxX = -1; var maxY = -1
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (Color.alpha(pixels[y * SIZE + x]) > ALPHA_THRESHOLD) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        return if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)
    }

    /** Average of opaque pixels sampled just inside the four edge midpoints of [area]. */
    private fun edgeColor(bitmap: Bitmap, area: Rect): Int {
        val inset = (area.width() * 0.05f).toInt().coerceAtLeast(2)
        val points = listOf(
            area.centerX() to area.top + inset,
            area.centerX() to area.bottom - inset - 1,
            area.left + inset to area.centerY(),
            area.right - inset - 1 to area.centerY()
        )
        var r = 0; var g = 0; var b = 0; var n = 0
        points.forEach { (x, y) ->
            val p = bitmap.getPixel(x.coerceIn(0, SIZE - 1), y.coerceIn(0, SIZE - 1))
            if (Color.alpha(p) > 200) { r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++ }
        }
        return if (n == 0) FALLBACK_BG else Color.rgb(r / n, g / n, b / n)
    }

}
