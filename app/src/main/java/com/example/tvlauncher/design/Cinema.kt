package com.example.tvlauncher.design

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.example.tvlauncher.R
import com.example.tvlauncher.design.cinema.StripeDrawable
import kotlin.math.roundToInt

/**
 * The Cinema section's own look: espresso ground, cream text, translucent cream "glass" surfaces and the 70s stripe set
 * (saffron, orange, terracotta, red, maroon). Only the Movies page, its title pages, search and the player use it, so
 * the rest of the launcher keeps [LauncherTheme] and [Type] untouched.
 *
 * Sizes are written in the mockups' pixels (a 1920x1080 frame) and converted with [px]: the TV runs at 320dpi, so one
 * mockup pixel is half a dp and the numbers in `docs/ui-review/movies-reimagined/_src/mock.html` can be copied as-is.
 */
object Cinema {
    // --- Colour ------------------------------------------------------------------------------------
    const val BG = 0xFF0B0807.toInt()
    const val CREAM = 0xFFF6ECDB.toInt()
    const val MUTED = 0xFFB6A896.toInt()
    const val DIM = 0xFF7F7366.toInt()
    const val READING = 0xFFD7CBB9.toInt()      // synopsis text
    const val CARD_TITLE = 0xFFCDBFAD.toInt()   // a poster's name at rest
    const val RAIL_ICON = 0xFFA99B89.toInt()
    const val INK = 0xFF1B120C.toInt()          // dark text on cream
    const val SAFFRON = 0xFFEDBE56.toInt()
    const val TERRA = 0xFFE27A49.toInt()
    const val DANGER_TEXT = 0xFFF0A48A.toInt()
    const val TEAL = 0xFF3AA597.toInt()
    const val PLACEHOLDER = 0xFF221A15.toInt()  // a poster that has not arrived yet

    // The stripe set, in the order the bands are drawn (top to bottom, or left to right).
    const val STRIPE_SAFFRON = 0xFFF6BF33.toInt()
    const val STRIPE_ORANGE = 0xFFF08A25.toInt()
    const val STRIPE_TERRA = 0xFFE2562B.toInt()
    const val STRIPE_RED = 0xFFC2281D.toInt()
    const val STRIPE_MAROON = 0xFF6B1A10.toInt()
    const val MAROON = 0xFF5D1710.toInt()
    val STRIPES = intArrayOf(STRIPE_SAFFRON, STRIPE_ORANGE, STRIPE_TERRA, STRIPE_RED, STRIPE_MAROON)
    /** The rings of the logo and the mic, outside in. */
    val RINGS = intArrayOf(MAROON, STRIPE_RED, STRIPE_TERRA, STRIPE_ORANGE, STRIPE_SAFFRON)

    const val GLASS = 0x13FFF2DE          // rgba(255,242,222,.075)
    const val GLASS_STRONG = 0x21FFF2DE   // .13
    const val GLASS_FOCUS = 0x24FFF2DE    // .14, a focused card
    const val HAIRLINE = 0x24FFF2DE       // .14
    const val RAIL_FILL = 0x9E120D0A.toInt()   // rgba(18,13,10,.62)

    /** [color] with its alpha replaced by [alpha] (0..1). */
    fun alpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha * 255).roundToInt().coerceIn(0, 255) shl 24)

    // --- Units -------------------------------------------------------------------------------------
    /** Mockup pixels to real pixels (one mockup pixel is half a dp). */
    fun px(context: Context, mock: Number): Int = (mock.toFloat() * context.resources.displayMetrics.density / 2f).roundToInt()
    fun pxf(context: Context, mock: Number): Float = mock.toFloat() * context.resources.displayMetrics.density / 2f

    // --- Type --------------------------------------------------------------------------------------
    private var serifFace: Typeface? = null
    private var sansFace: Typeface? = null

    private fun serif(context: Context): Typeface =
        serifFace ?: (runCatching { ResourcesCompat.getFont(context, R.font.fraunces) }.getOrNull() ?: Typeface.SERIF).also { serifFace = it }

    private fun sans(context: Context): Typeface =
        sansFace ?: (runCatching { ResourcesCompat.getFont(context, R.font.inter) }.getOrNull() ?: Typeface.SANS_SERIF).also { sansFace = it }

    /** The typeface and variation settings for [sizePx] at [weight]: optical size follows the size, as the browser does. */
    fun typeface(context: Context, serif: Boolean): Typeface = if (serif) serif(context) else sans(context)

    fun variation(serif: Boolean, sizePx: Float, weight: Int): String {
        val opsz = if (serif) sizePx.coerceIn(9f, 144f) else sizePx.coerceIn(14f, 32f)
        return "'opsz' ${opsz.roundToInt()}, 'wght' $weight"
    }

    /** Styles [view] at [sizePx] mockup pixels. Fraunces and Inter are variable fonts; older systems get their default weight. */
    fun style(view: TextView, sizePx: Number, color: Int = CREAM, serif: Boolean = false, weight: Int = 400, tracking: Float = 0f, caps: Boolean = false): TextView = view.apply {
        val size = sizePx.toFloat()
        setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(context, size))
        typeface = typeface(context, serif)
        if (Build.VERSION.SDK_INT >= 26) fontVariationSettings = variation(serif, size, weight)
        else if (weight >= 600) setTypeface(typeface, Typeface.BOLD)
        setTextColor(color)
        letterSpacing = tracking
        isAllCaps = caps
        includeFontPadding = !serif
    }

    /** Changes only the weight (for a label that turns semibold on focus). */
    fun weight(view: TextView, sizePx: Number, serif: Boolean, weight: Int) {
        if (Build.VERSION.SDK_INT >= 26) view.fontVariationSettings = variation(serif, sizePx.toFloat(), weight)
    }

    fun text(context: Context, value: CharSequence, sizePx: Number, color: Int = CREAM, serif: Boolean = false, weight: Int = 400, tracking: Float = 0f, caps: Boolean = false): TextView =
        style(TextView(context).apply { text = value }, sizePx, color, serif, weight, tracking, caps)

    /** A Fraunces display title: tight leading, as in the mockups (`.title`). */
    fun title(context: Context, value: CharSequence, sizePx: Number, maxLines: Int = 2): TextView =
        text(context, value, sizePx, CREAM, serif = true, weight = 400, tracking = -0.02f).apply {
            this.maxLines = maxLines; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 0.92f)
        }

    /** Reading text (a synopsis): Inter light, generous leading. */
    fun synopsis(context: Context, value: CharSequence, sizePx: Number = 26, maxLines: Int = 3): TextView =
        text(context, value, sizePx, READING, weight = 300).apply {
            this.maxLines = maxLines; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.32f)
        }

    /**
     * The eyebrow: a small horizontal stripe pill, then tracked saffron capitals, with [parts] separated by faint dots
     * ("FEATURED • #1 IN FILMS TODAY"). [mark] false leaves the stripe pill out.
     */
    fun eyebrow(context: Context, parts: List<String>, sizePx: Number = 17, color: Int = SAFFRON, mark: Boolean = true, markAlpha: Float = 1f): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (mark) row.addView(View(context).apply { background = StripeDrawable(horizontalBands = true, radius = pxf(context, 5)); alpha = markAlpha },
            LinearLayout.LayoutParams(px(context, 40), px(context, 10)).apply { marginEnd = px(context, 14) })
        parts.forEachIndexed { i, p ->
            if (i > 0) row.addView(dot(context, color, 5, 0.6f), LinearLayout.LayoutParams(px(context, 5), px(context, 5)).apply { marginStart = px(context, 14); marginEnd = px(context, 14) })
            row.addView(text(context, p, sizePx, color, weight = 600, tracking = 0.24f, caps = true).apply { maxLines = 1 })
        }
        return row
    }

    fun dot(context: Context, color: Int, sizePx: Number, alpha: Float = 1f): View = View(context).apply {
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(color) }
        this.alpha = alpha
    }

    /**
     * The meta line: certificate box, then the plain items with faint dots between, then the IMDb star. Only what is
     * known is shown; an empty list gives an empty row.
     */
    fun meta(context: Context, certificate: String?, items: List<String>, rating: String?, sizePx: Number = 23): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val gap = px(context, 16)
        fun sep() { if (row.childCount > 0) row.addView(dot(context, DIM, 4), LinearLayout.LayoutParams(px(context, 4), px(context, 4)).apply { marginStart = gap; marginEnd = gap }) }
        certificate?.takeIf { it.isNotBlank() }?.let { c ->
            row.addView(text(context, c, sizePx.toFloat() * 19f / 23f, CREAM, weight = 600, tracking = 0.03f).apply {
                setPadding(px(context, 9), px(context, 1), px(context, 9), px(context, 1))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = pxf(context, 6); setStroke(px(context, 1.5f).coerceAtLeast(1), alpha(CREAM, 0.45f)) }
            })
        }
        items.filter { it.isNotBlank() }.forEach { sep(); row.addView(text(context, it, sizePx, MUTED).apply { maxLines = 1 }) }
        rating?.let { r ->
            sep()
            row.addView(icon(context, R.drawable.ic_cin_star, SAFFRON), LinearLayout.LayoutParams(px(context, 22), px(context, 22)).apply { marginEnd = px(context, 8) })
            row.addView(text(context, r, sizePx, CREAM, weight = 500))
        }
        return row
    }

    /** "Streaming on HBO Max": a glass pill with a small gradient play disc, the service in saffron. */
    fun provider(context: Context, services: List<String>, sizePx: Number = 22): View? {
        if (services.isEmpty()) return null
        val names = services.joinToString(", ").replace(Regex(", ([^,]*)$"), " and $1")
        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(context, 10), px(context, 8), px(context, 18), px(context, 8))
            background = glass(context, 30f, GLASS)
        }
        val disc = android.widget.FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(TERRA, SAFFRON)).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
            addView(icon(context, R.drawable.ic_cin_play, 0xFF1A0F08.toInt()), android.widget.FrameLayout.LayoutParams(px(context, 14), px(context, 14), Gravity.CENTER))
        }
        pill.addView(disc, LinearLayout.LayoutParams(px(context, 28), px(context, 28)).apply { marginEnd = px(context, 12) })
        val line = SpannableStringBuilder("Streaming on ").apply {
            val start = length; append(names); setSpan(ForegroundColorSpan(SAFFRON), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        pill.addView(text(context, line, sizePx, CREAM).apply { maxLines = 1 })
        return pill
    }

    /**
     * A row heading (`.rowhead`): the vertical stripe marker, the serif title, an optional count and an optional
     * "SEE ALL" on the right. [width] is the full row width so "SEE ALL" can sit at the end.
     */
    fun rowHeading(context: Context, title: String, count: String? = null, seeAll: Boolean = false, sizePx: Number = 38): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(View(context).apply { background = StripeDrawable(horizontalBands = false, radius = pxf(context, 7)) },
            LinearLayout.LayoutParams(px(context, 14), px(context, 30)).apply { marginEnd = px(context, 18) })
        row.addView(text(context, title, sizePx, CREAM, serif = true, tracking = -0.01f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        count?.let { row.addView(text(context, it, 20, DIM, weight = 500, tracking = 0.04f), LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(context, 18); topMargin = px(context, 8) }) }
        if (seeAll) {
            row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            row.addView(text(context, "SEE ALL", 19, DIM, tracking = 0.06f))
            row.addView(icon(context, R.drawable.ic_cin_chev, DIM), LinearLayout.LayoutParams(px(context, 18), px(context, 18)).apply { marginStart = px(context, 8) })
        }
        return row
    }

    /** A vector icon tinted [color]. */
    fun icon(context: Context, res: Int, color: Int): ImageView = ImageView(context).apply {
        setImageResource(res); setColorFilter(color); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** A glass surface: translucent cream fill with a hairline edge. [radiusPx] in mockup pixels. */
    fun glass(context: Context, radiusPx: Float, fill: Int = GLASS, line: Int = HAIRLINE): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply { cornerRadius = pxf(context, radiusPx); setColor(fill); setStroke(1, line) }

    /** A chip (`.chip`): glass pill with an optional icon. */
    fun chip(context: Context, label: CharSequence, iconRes: Int? = null, heightPx: Int = 56, sizePx: Number = 23): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(context, 26), 0, px(context, 26), 0)
            background = glass(context, heightPx / 2f)
            minimumHeight = px(context, heightPx)
            iconRes?.let { addView(icon(context, it, 0xFFDDD0BD.toInt()), LinearLayout.LayoutParams(px(context, 28), px(context, 28)).apply { marginEnd = px(context, 10) }) }
            addView(text(context, label, sizePx, 0xFFDDD0BD.toInt()).apply { maxLines = 1 })
        }
}
