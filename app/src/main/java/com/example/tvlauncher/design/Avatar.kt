package com.example.tvlauncher.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.example.tvlauncher.system.TvAccount
import com.example.tvlauncher.R

/** A round monogram avatar: solid MCM-palette disc, ivory initial. */
object Avatar {
    private val palette = intArrayOf(R.color.terracotta, R.color.deep_teal, R.color.walnut, R.color.cobalt, R.color.olive)

    fun monogram(context: Context, seed: String, initial: String, size: Int = 160): Drawable {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, palette[Math.floorMod(seed.hashCode(), palette.size)])
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.ivory)
            textSize = size * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(initial, size / 2f, baseline, text)
        return BitmapDrawable(context.resources, bitmap)
    }

    /** The account's profile photo (assets/home/avatars, named in accounts.json) as a circle, or a monogram when there is none. */
    fun of(context: Context, account: TvAccount): Drawable {
        account.photo?.let { path ->
            try {
                context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.let { photo ->
                    val side = minOf(photo.width, photo.height)
                    val square = Bitmap.createBitmap(photo, (photo.width - side) / 2, (photo.height - side) / 2, side, side)
                    return RoundedBitmapDrawableFactory.create(context.resources, square).apply { isCircular = true; setAntiAlias(true) }
                }
            } catch (_: Exception) { /* fall back to the monogram */ }
        }
        return monogram(context, account.email, account.initial)
    }

    /** Neutral "no account known" avatar. */
    fun unknown(context: Context): Drawable = monogram(context, "?", "?")
}
