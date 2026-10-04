package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.tvlauncher.R
import com.example.tvlauncher.data.parents.Link
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.scrollFocusIntoView

/**
 * Shared pieces for the parents' home. Everything here is built from the existing tokens (palette,
 * type, radii, the ivory image mat and the shared focus motion), so the new sections are more objects in
 * the same room rather than a second design system.
 */
object Kit {
    val PAPER_TOP = 0xFFF3E7C6.toInt()
    val PAPER_BOTTOM = 0xFFE6D5A8.toInt()
    val PAPER_INK = 0xFF2B2117.toInt()
    val PAPER_INK_DIM = 0xFF6B5A46.toInt()
    val TERRACOTTA = 0xFFAF5938.toInt()
    val WALNUT_FRAME = 0xFF5A3F28.toInt()
    /** Photo-print paper: whiter than the recipe-card cream, like a real print's border. */
    val PRINT_PAPER = 0xFFF6F1E6.toInt()

    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()
    fun dpf(context: Context, v: Float) = v * context.resources.displayMetrics.density

    fun paper(context: Context, radiusDp: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(PAPER_TOP, PAPER_BOTTOM)).apply { cornerRadius = dpf(context, radiusDp.toFloat()) }

    /** A walnut frame with a paper mount inside it, as on the Tonight print. */
    fun framed(context: Context, insetDp: Int = 9): LayerDrawable = LayerDrawable(arrayOf(
        GradientDrawable().apply { cornerRadius = dpf(context, 10f); setColor(WALNUT_FRAME) },
        paper(context, 4)
    )).apply { val i = dp(context, insetDp); setLayerInset(1, i, i, i, i) }

    fun rounded(view: View, radiusPx: Float) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) { outline.setRoundRect(0, 0, v.width, v.height, radiusPx) }
        }
    }

    fun line(context: Context, value: String, style: Type.Style, color: Int? = null, lines: Int = 1): TextView =
        Type.text(context, value, style, color).apply { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }

    /**
     * The one focus rule for image objects (prints, posters, sleeves, covers): the ivory mat fades in over
     * the object's edge and it lifts a little, exactly as the painting shelf does. [also] runs on each change.
     */
    fun imageFocus(target: View, frame: View, image: View?, caption: View?, radiusPx: Float, also: (Boolean) -> Unit = {}) {
        val mat = LauncherTheme.imageMat(target.context, radiusPx).apply { alpha = 0 }
        frame.foreground = mat
        target.setOnFocusChangeListener { v, focused ->
            LauncherTheme.fadeDrawable(frame, mat, focused)
            Motion.focusImageCard(frame, image, caption, focused)
            scrollFocusIntoView(v, focused)
            also(focused)
        }
    }

    /** Paper rows (a TV guide line, a podcast line) light as a deeper paper band; the card gets the mat. */
    fun paperRowFocus(row: View, card: View, cardMat: android.graphics.drawable.Drawable, rows: () -> List<View>, also: (Boolean) -> Unit = {}) {
        val lit = LauncherTheme.surface(row.context, R.color.paper_lit, R.dimen.radius_small)
        row.setOnFocusChangeListener { v, focused ->
            v.background = if (focused) lit else null
            LauncherTheme.fadeDrawable(card, cardMat, rows().any { it.isFocused })
            if (focused) scrollFocusIntoView(v, true)
            also(focused)
        }
    }

    /** Left on the first and Right on the last item stay put, so a row never leaks focus into another. */
    fun trapEdges(items: List<View>) {
        items.forEachIndexed { i, v ->
            v.setOnKeyListener { _, keyCode, event ->
                event.action == KeyEvent.ACTION_DOWN &&
                    ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT && i == 0) || (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && i == items.lastIndex))
            }
        }
    }

    /** A photo print: paper border round the picture, standing at a small tilt that straightens when picked up. */
    class Print(context: Context, val tilt: Float, borderDp: Int = 7) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF3B2C1F.toInt())
        }
        val frame = FrameLayout(context).apply {
            val b = dp(context, borderDp)
            setPadding(b, b, b, b)
            background = GradientDrawable().apply { cornerRadius = dpf(context, 3f); setColor(PRINT_PAPER) }
            elevation = dpf(context, 5f)
            rotation = tilt
            isFocusable = true; isClickable = true
            addView(image, FrameLayout.LayoutParams(-1, -1))
        }

        fun set(bitmap: Bitmap?) {
            if (bitmap == null) return
            if (image.drawable == null) Motion.swapImage(image) { image.setImageBitmap(bitmap) } else image.setImageBitmap(bitmap)
        }

        fun bindFocus(caption: View?, also: (Boolean) -> Unit) {
            // No inner zoom: the picture sits under paper, not glass, so only the print itself lifts.
            imageFocus(frame, frame, null, caption, dpf(frame.context, 3f)) { focused ->
                frame.animate().rotation(if (focused) 0f else tilt).setDuration(if (focused) Motion.FOCUS_IN_MS else Motion.FOCUS_OUT_MS).setInterpolator(Motion.SETTLE).start()
                also(focused)
            }
        }
    }

    /** A section on the page: its heading and body. Hidden whole when there is nothing to show. */
    class Section(val context: Context, val title: String, subtitle: String? = null, mood: com.example.tvlauncher.design.SectionTheme.Mood) {
        // Always built with a subtitle slot, so a section can say something later ("7 photos · last added today").
        private val header = com.example.tvlauncher.design.SectionHeader.build(context, title, subtitle ?: " ")
        val subtitleView: TextView? = (header as LinearLayout).getChildAt(1) as? TextView
        init { subtitle(subtitle) }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            addView(header)
            addView(body, LinearLayout.LayoutParams(-1, -2))
            com.example.tvlauncher.design.SectionTheme.tag(this, mood)
            setTag(com.example.tvlauncher.R.id.usage_where, title)
            visibility = View.GONE
        }
        var shown: Boolean
            get() = view.visibility == View.VISIBLE
            set(value) { view.visibility = if (value) View.VISIBLE else View.GONE }

        fun subtitle(text: String?) { subtitleView?.text = text.orEmpty(); subtitleView?.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE }
    }
}

/** Opens a feed link in the first listed app that is installed and accepts it. */
internal object Opener {
    fun open(activity: Activity, link: Link, label: String) {
        val pm = activity.packageManager
        val where = com.example.tvlauncher.data.UsageLog.whereOf(activity.currentFocus)
        val packages = com.example.tvlauncher.data.StandIns.preferring(pm, link.packages)
        // The first listed app that takes the link, or (failing that) the first installed one opened at its start.
        val pick = packages.firstNotNullOfOrNull { pkg ->
            Intent(Intent.ACTION_VIEW, Uri.parse(link.uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .takeIf { it.resolveActivity(pm) != null }?.let { pkg to it }
        } ?: packages.firstNotNullOfOrNull { pkg -> (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.let { pkg to it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } }
        if (pick == null) {
            Toast.makeText(activity, "Couldn't open $label on this TV", Toast.LENGTH_LONG).show()
            com.example.tvlauncher.data.UsageLog.event("error", where = where, what = label, detail = "Couldn't open on this TV")
            return
        }
        val (pkg, intent) = pick
        val start = {
            try { activity.startActivity(intent); Log.i("ParentFeed", "Opened ${link.uri.substringBefore('?').take(48)} in $pkg"); com.example.tvlauncher.data.UsageLog.opened(where, label, pkg) }
            catch (e: Exception) { Log.w("ParentFeed", "Open failed in $pkg: ${e.javaClass.simpleName}"); Toast.makeText(activity, "Couldn't open $label on this TV", Toast.LENGTH_LONG).show() }
        }
        // Apps that only play from abroad get their VPN first.
        val route = com.example.tvlauncher.system.VpnPilot.routeFor(activity, pkg)
        if (route != null) com.example.tvlauncher.system.VpnPilot.connectThen(activity, route) { start() } else start()
    }

    fun installed(activity: Activity, link: Link): Boolean =
        link.packages.any { pkg -> Intent(Intent.ACTION_VIEW, Uri.parse(link.uri)).setPackage(pkg).resolveActivity(activity.packageManager) != null }
}

internal fun Context.serifItalic(): Typeface = Typeface.create("serif", Typeface.ITALIC)

internal fun FrameLayout.LayoutParams.at(gravity: Int) = apply { this.gravity = gravity }

internal fun LinearLayout.row(context: Context) = apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; gravity = Gravity.TOP }
