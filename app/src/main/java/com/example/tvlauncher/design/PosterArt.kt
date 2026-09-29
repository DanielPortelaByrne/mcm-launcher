package com.example.tvlauncher.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * A printed cover for a film with no poster: a field of one of the room's colours, one geometric motif in a
 * second colour, and the title set in the serif like a Criterion spine. Chosen from the title, so the same
 * film always gets the same cover.
 */
object PosterArt {
    private val fields = intArrayOf(0xFFAF5938.toInt(), 0xFF28766C.toInt(), 0xFFB07C22.toInt(), 0xFF454A2B.toInt(), 0xFF304E79.toInt(), 0xFF795638.toInt())
    private const val IVORY = 0xFFF3E9CF.toInt()

    /** [title] may carry its year as "Name (1994)". Drawn [widthDp] x [heightDp]. */
    fun typographic(context: Context, title: String, widthDp: Int = 160, heightDp: Int = 240): Bitmap {
        val d = context.resources.displayMetrics.density
        val w = (widthDp * d).toInt(); val h = (heightDp * d).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val year = Regex("""\((\d{4})\)\s*$""").find(title)?.groupValues?.get(1)
        val name = title.replace(Regex("""\s*\(\d{4}\)\s*$"""), "")
        val hash = title.hashCode() and 0x7fffffff
        val field = fields[hash % fields.size]
        val motif = fields[(hash / 7) % fields.size].let { if (it == field) fields[(hash / 7 + 1) % fields.size] else it }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.color = field; c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // The motif sits low on the cover, cropped by its edge: a sun, an arch or a disc on a band.
        p.color = motif
        val u = w.toFloat()
        when ((hash / 13) % 3) {
            0 -> c.drawCircle(u * 0.62f, h * 0.74f, u * 0.42f, p)
            1 -> c.drawRoundRect(RectF(u * 0.18f, h * 0.52f, u * 0.82f, h * 1.2f), u * 0.32f, u * 0.32f, p)
            else -> { c.drawRect(0f, h * 0.7f, u, h * 0.78f, p); c.drawCircle(u * 0.32f, h * 0.66f, u * 0.2f, p) }
        }
        val margin = 17f * d
        p.color = IVORY; p.alpha = 64; p.style = Paint.Style.STROKE; p.strokeWidth = 1f * d
        c.drawRect(6f * d, 6f * d, w - 6f * d, h - 6f * d, p)
        p.style = Paint.Style.FILL
        // Title: serif, ivory, top-left, up to four lines.
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = IVORY; typeface = Typeface.create("serif", Typeface.BOLD); textSize = 25f * d
        }
        val layout = StaticLayout.Builder.obtain(name, 0, name.length, tp, (w - 2 * margin).toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.05f)
            .setMaxLines(4).setEllipsize(TextUtils.TruncateAt.END).build()
        c.save(); c.translate(margin, margin + 6f * d); layout.draw(c); c.restore()
        // A hairline under the title, and the year in tracked caps at the foot.
        p.color = IVORY; p.alpha = 150
        val ruleY = margin + 6f * d + layout.height + 10f * d
        c.drawRect(margin, ruleY, margin + 28f * d, ruleY + 1.5f * d, p)
        year?.let {
            val yp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = IVORY; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textSize = 13f * d; letterSpacing = 0.2f
            }
            c.drawText(it, margin, ruleY + 22f * d, yp)
        }
        return bitmap
    }
}
