package com.example.tvlauncher.ui.room

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.example.tvlauncher.data.home.ArtType
import kotlin.math.min

/**
 * One project as a small framed print: a matte cream mat in a walnut frame holding a flat, restrained
 * mid-century emblem (no faces, no gradients on the emblem). Drawn on a 100 x 100 canvas.
 * Add a new [ArtType] by adding a branch in [onDraw] and a `drawXxx` function.
 * When [lit] (focused) a soft warm light blooms behind the print.
 */
class ProjectArtView(context: Context, private val art: ArtType) : View(context) {

    companion object {
        /** The print as a bitmap [sizeDp] square, for showing it large (in a sheet) without a live view. */
        fun render(context: Context, art: ArtType, sizeDp: Int): android.graphics.Bitmap {
            val px = (sizeDp * context.resources.displayMetrics.density).toInt()
            val bitmap = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
            ProjectArtView(context, art).apply { layout(0, 0, px, px); draw(Canvas(bitmap)) }
            return bitmap
        }
    }

    var lit: Boolean = false
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val walnut = 0xFF5A3F28.toInt()
    private val walnutDark = 0xFF3F2B1B.toInt()
    private val mat = 0xFFF1E6CA.toInt()
    private val matShade = 0xFFE4D5B0.toInt()
    private val ink = 0xFF2B2A20.toInt()
    private val olive = 0xFF6E7B32.toInt()
    private val ochre = 0xFFC08A2E.toInt()
    private val terracotta = 0xFFAF5938.toInt()
    private val teal = 0xFF28766C.toInt()

    // Frame and mat occupy this box; the emblem is drawn inside [art].
    private val frame = RectF(12f, 6f, 88f, 94f)
    private val paper = RectF(17f, 11f, 83f, 89f)

    override fun onDraw(canvas: Canvas) {
        val scale = min(width, height) / 100f
        canvas.save()
        canvas.translate((width - 100 * scale) / 2f, (height - 100 * scale) / 2f)
        canvas.scale(scale, scale)
        drawGlow(canvas)
        drawFrame(canvas)
        when (art) {
            ArtType.FIGURINE -> drawFrog(canvas)
            ArtType.TAYTO -> drawTayto(canvas)
            ArtType.MIRROR -> drawMirror(canvas)
            ArtType.SOFA -> drawSofa(canvas)
            ArtType.GENERIC -> drawGeneric(canvas)
        }
        canvas.restore()
    }

    private fun fill(color: Int) = paint.apply { shader = null; style = Paint.Style.FILL; this.color = color }

    private fun drawGlow(c: Canvas) {
        if (!lit) return
        paint.shader = RadialGradient(50f, 50f, 64f, 0x66E8C878, 0x00E8C878, Shader.TileMode.CLAMP)
        c.drawCircle(50f, 50f, 64f, paint)
        paint.shader = null
    }

    private fun drawFrame(c: Canvas) {
        c.drawRoundRect(RectF(frame.left + 1.5f, frame.top + 3f, frame.right + 1.5f, frame.bottom + 3f), 3f, 3f, fill(0x33000000))
        paint.shader = LinearGradient(frame.left, frame.top, frame.right, frame.bottom, 0xFFA47A4E.toInt(), 0xFF6E4C30.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(frame, 3f, 3f, paint)
        paint.shader = null
        c.drawRoundRect(paper, 1.5f, 1.5f, fill(mat))
        // A whisper of shadow under the frame's inner lip, so the mat sits behind the frame.
        c.drawRect(paper.left, paper.top, paper.right, paper.top + 1.6f, fill(matShade))
        // A short rule near the foot of the mat, like a gallery print's caption line.
        c.drawRect(38f, 80f, 62f, 81f, fill(terracotta))
    }

    private fun oval(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int) = c.drawOval(l, t, r, b, fill(color))
    private fun round(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) = c.drawRoundRect(RectF(l, t, r, b), rad, rad, fill(color))
    private fun dot(c: Canvas, x: Float, y: Float, r: Float, color: Int) = c.drawCircle(x, y, r, fill(color))

    // --- Emblems ---------------------------------------------------------------

    /** Freddo: a dome of green with two round eyes, reduced to shapes. */
    private fun drawFrog(c: Canvas) {
        c.drawPath(Path().apply { addArc(RectF(26f, 34f, 74f, 82f), 180f, 180f); lineTo(74f, 72f); lineTo(26f, 72f); close() }, fill(olive))
        round(c, 22f, 68f, 78f, 74f, 3f, olive)
        dot(c, 39f, 42f, 8f, mat); dot(c, 61f, 42f, 8f, mat)
        dot(c, 39f, 42f, 3f, ink); dot(c, 61f, 42f, 3f, ink)
    }

    /** Mr Tayto: an ochre egg with a terracotta bow tie. */
    private fun drawTayto(c: Canvas) {
        oval(c, 32f, 20f, 68f, 70f, ochre)
        val bow = fill(terracotta)
        c.drawPath(Path().apply { moveTo(50f, 62f); lineTo(35f, 54f); lineTo(35f, 70f); close() }, bow)
        c.drawPath(Path().apply { moveTo(50f, 62f); lineTo(65f, 54f); lineTo(65f, 70f); close() }, bow)
        dot(c, 50f, 62f, 3.2f, walnut)
    }

    /** Timigotchi: an oval mirror, a terracotta ring around teal glass with one bright stripe. */
    private fun drawMirror(c: Canvas) {
        oval(c, 32f, 17f, 68f, 76f, terracotta)
        oval(c, 37f, 22f, 63f, 71f, teal)
        c.drawPath(Path().apply { moveTo(43f, 31f); lineTo(49f, 28f); lineTo(42f, 55f); lineTo(38f, 52f); close() }, fill(0x66F1E6CA))
    }

    /** Camaleonda: three tufted modules as soft arches on a low base. */
    private fun drawSofa(c: Canvas) {
        round(c, 26f, 38f, 42f, 70f, 8f, olive)
        round(c, 42f, 30f, 58f, 70f, 8f, olive)
        round(c, 58f, 38f, 74f, 70f, 8f, olive)
        round(c, 22f, 58f, 78f, 72f, 5f, 0xFF585F28.toInt())
        for (x in listOf(34f, 50f, 66f)) dot(c, x, if (x == 50f) 42f else 48f, 1.6f, mat)
        round(c, 28f, 72f, 32f, 77f, 1f, walnut); round(c, 68f, 72f, 72f, 77f, 1f, walnut)
    }

    private fun drawGeneric(c: Canvas) {
        oval(c, 32f, 22f, 68f, 58f, ochre)
        round(c, 36f, 44f, 64f, 72f, 12f, teal)
    }
}
