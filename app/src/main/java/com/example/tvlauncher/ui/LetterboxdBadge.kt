package com.example.tvlauncher.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvlauncher.R
import com.example.tvlauncher.design.Type

/**
 * Letterboxd's three coloured dots followed by the rating ("● ● ● 3.5"). Used top-right on Eva's film
 * card and under the poster in the film sheet. The dots are Letterboxd's own colours (a brand mark, like an
 * app icon), not UI colours.
 */
class LetterboxdBadge(context: Context) {
    val view: LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val rating: TextView = Type.text(context, "", Type.Style.BODY)

    init {
        val size = (9 * context.resources.displayMetrics.density).toInt()
        val gap = context.resources.getDimensionPixelSize(R.dimen.space_1)
        DOTS.forEach { color ->
            view.addView(View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } },
                LinearLayout.LayoutParams(size, size).apply { marginEnd = gap })
        }
        view.addView(rating, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.resources.getDimensionPixelSize(R.dimen.space_1) })
        set(null)
    }

    /** [label] is the plain rating ("3.5"); null hides the badge. */
    fun set(label: String?) {
        rating.text = label ?: ""
        view.visibility = if (label == null) View.INVISIBLE else View.VISIBLE
    }

    private companion object {
        val DOTS = intArrayOf(0xFFFF8000.toInt(), 0xFF00E054.toInt(), 0xFF40BCF4.toInt())
    }
}
