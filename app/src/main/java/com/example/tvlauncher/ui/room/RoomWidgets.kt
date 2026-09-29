package com.example.tvlauncher.ui.room

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.tvlauncher.R
import com.example.tvlauncher.data.home.Project
import com.example.tvlauncher.data.home.Recipe
import com.example.tvlauncher.data.home.RecipeDeck
import com.example.tvlauncher.data.home.TonightFilm
import com.example.tvlauncher.data.home.TonightPlan
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.scrollFocusIntoView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PAPER_TOP = 0xFFF3E7C6.toInt()
private val PAPER_BOTTOM = 0xFFE6D5A8.toInt()
private val PAPER_INK = 0xFF2B2117.toInt()
private val PAPER_INK_DIM = 0xFF6B5A46.toInt()
private val TERRACOTTA = 0xFFAF5938.toInt()
private val WALNUT_FRAME = 0xFF5A3F28.toInt()

private fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

private fun Context.text(value: String, sp: Float, color: Int, style: Int = Typeface.NORMAL, family: String = "sans-serif-medium") =
    TextView(this).apply {
        text = value; textSize = sp; setTextColor(color)
        typeface = Typeface.create(family, style)
    }

private fun paper(context: Context, radiusDp: Int): GradientDrawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(PAPER_TOP, PAPER_BOTTOM)).apply {
    cornerRadius = context.dp(radiusDp).toFloat()
}

// ---------------------------------------------------------------------------------------------
// Project shelf
// ---------------------------------------------------------------------------------------------

/** The sideboard: projects standing on a walnut ledge. One caption below the ledge follows the focused object. */
class ProjectShelf(private val context: Context, projects: List<Project>, private val onOpen: (Project) -> Unit) {

    val view: View
    private val title = Type.text(context, "Currently making", Type.Style.HEADING)
    private val sub = Type.text(context, "Move along the shelf", Type.Style.CAPTION, ContextCompat.getColor(context, R.color.accent))

    init {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val frame = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        root.addView(frame, LinearLayout.LayoutParams(-1, context.dp(190)))

        frame.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF8A6440.toInt(), 0xFF5A3F28.toInt(), 0xFF3F2B1B.toInt())).apply { cornerRadius = context.dp(8).toFloat() }
            elevation = context.dp(4).toFloat()
        }, FrameLayout.LayoutParams(-1, context.dp(26), Gravity.BOTTOM))

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(context.dp(16), 0, 0, 0) }
        frame.addView(row, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { bottomMargin = context.dp(14) })

        projects.forEach { project ->
            val art = ProjectArtView(context, project.art)
            val item = FrameLayout(context).apply {
                isFocusable = true; isClickable = true; clipChildren = false
                contentDescription = "${project.title}. ${project.nextAction}"
                addView(art, FrameLayout.LayoutParams(-1, -1))
            }
            // Image-card focus: the print gets the ivory mat as it lifts off the ledge.
            val mat = LauncherTheme.imageMat(context, context.dp(6).toFloat()).apply { alpha = 0 }
            item.foreground = mat
            item.setOnFocusChangeListener { v, hasFocus ->
                art.lit = hasFocus
                LauncherTheme.fadeDrawable(v, mat, hasFocus)
                v.animate().translationY(if (hasFocus) -context.dp(12).toFloat() else 0f)
                    .scaleX(if (hasFocus) 1.08f else 1f).scaleY(if (hasFocus) 1.08f else 1f).setDuration(if (hasFocus) com.example.tvlauncher.design.Motion.FOCUS_IN_MS else com.example.tvlauncher.design.Motion.FOCUS_OUT_MS).setInterpolator(com.example.tvlauncher.design.Motion.SETTLE).start()
                if (hasFocus) { show(project); scrollFocusIntoView(v, true) }
            }
            item.setOnClickListener { onOpen(project) }
            row.addView(item, LinearLayout.LayoutParams(context.dp(150), context.dp(150)).apply { marginEnd = context.dp(28) })
        }
        trapEdges(row)

        val caption = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, LauncherTheme.px(context, R.dimen.space_3), 0, 0) }
        listOf(title, sub).forEach {
            Type.onPainting(it); it.maxLines = 1; it.ellipsize = TextUtils.TruncateAt.END
            caption.addView(it)
        }
        root.addView(caption)
        view = root
    }

    private fun show(project: Project) {
        Motion.swapText(title, project.title)
        Motion.swapText(sub, listOfNotNull("Next: ${project.nextAction}".takeIf { project.nextAction.isNotBlank() }, project.status).joinToString("   ·   "))
    }
}

// ---------------------------------------------------------------------------------------------
// Detail sheet
// ---------------------------------------------------------------------------------------------

/** One button on an [InfoSheet]. The sheet closes first, then [run] happens. */
/** [sublabel] adds a smaller second line; [primary] gives the button the terracotta accent. */
class SheetAction(val label: String, val sublabel: String? = null, val primary: Boolean = false, val run: () -> Unit)

/**
 * A calm centred card for project / film / recipe details, and the launcher's own replacement for
 * stock Android dialogs. Optional artwork sits on the left; [actions] become pill buttons above Close.
 * Back or OK on Close dismisses it and returns focus to where it was.
 */
class InfoSheet(private val container: FrameLayout) {
    private val context = container.context
    private var previousFocus: View? = null
    private var lastButtons: List<View> = emptyList()
    private var card: View? = null
    private var flight: Flight? = null
    private var surfaceFade: android.animation.ValueAnimator? = null
    val isVisible: Boolean get() = container.visibility == View.VISIBLE
    /** True while the sheet is animating away: it no longer takes keys, and the page is already coming back. */
    var isClosing = false
        private set

    /**
     * A picture that travels from the page into the sheet (and back when it closes). [source] is the
     * picture on the page, [target] its place in the sheet, [reveal] the parts of the sheet that appear
     * around it once it is on its way.
     */
    class Flight(val source: View, val target: View, val reveal: List<View>) {
        internal val from = android.graphics.Rect()
    }

    fun show(
        kicker: String, title: String, subtitle: String?, lines: List<String>,
        image: android.graphics.Bitmap? = null, actions: List<SheetAction> = emptyList(),
        refresh: Boolean = false
    ) {
        // A refresh redraws the same sheet in place (for results that arrive late): keep the way back and the focused button.
        val focusedIndex = if (refresh) lastButtons.indexOfFirst { it.isFocused }.coerceAtLeast(0) else 0
        if (!refresh) rememberFocus()
        settle()
        container.removeAllViews()
        container.setBackgroundColor(ContextCompat.getColor(context, R.color.sheet_scrim))
        val pad = LauncherTheme.px(context, R.dimen.space_6)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false; clipToPadding = false
            setPadding(pad, pad, pad, pad)
            background = LauncherTheme.surface(context, R.color.surface_solid)
            elevation = context.dp(24).toFloat()
        }
        if (image != null) {
            card.addView(android.widget.ImageView(context).apply {
                setImageBitmap(image); scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, LauncherTheme.px(context, R.dimen.radius_small).toFloat()) }
                }
            }, LinearLayout.LayoutParams(context.dp(150), context.dp(225)).apply { marginEnd = LauncherTheme.px(context, R.dimen.space_5) })
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        card.addView(column, LinearLayout.LayoutParams(0, -2, 1f))

        val gap = LauncherTheme.px(context, R.dimen.space_2)
        column.addView(Type.text(context, kicker, Type.Style.EYEBROW))
        column.addView(Type.text(context, title, Type.Style.TITLE).apply { setPadding(0, gap, 0, 0) })
        subtitle?.let { column.addView(Type.text(context, it, Type.Style.BODY, ContextCompat.getColor(context, R.color.accent)).apply { setPadding(0, gap, 0, 0) }) }
        lines.forEach { column.addView(Type.reading(Type.text(context, it, Type.Style.BODY, ContextCompat.getColor(context, R.color.text_muted))).apply { setPadding(0, gap + gap / 2, 0, 0) }) }

        var first: View? = null
        val built = mutableListOf<View>()
        val buttons = actions + SheetAction("Close") { }
        val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val controlHeight = LauncherTheme.px(context, R.dimen.control_height)
        buttons.forEachIndexed { i, action ->
            val label = if (action.sublabel == null) android.text.SpannableString(action.label) else
                android.text.SpannableString(action.label + "\n" + action.sublabel).apply {
                    setSpan(android.text.style.RelativeSizeSpan(0.8f), action.label.length + 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.StyleSpan(Typeface.NORMAL), action.label.length + 1, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            val extraLines = action.sublabel?.count { it == '\n' }?.plus(1) ?: 0
            val button = Type.text(context, label, Type.Style.BODY).apply {
                gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
                setPadding(LauncherTheme.px(context, R.dimen.space_4), 0, LauncherTheme.px(context, R.dimen.space_4), 0)
                tag = extraLines
            }
            LauncherTheme.bindPanelFocus(
                button,
                LauncherTheme.surface(context, if (action.primary) R.color.action else R.color.surface_raised, R.dimen.radius_pill),
                LauncherTheme.surface(context, R.color.focus, R.dimen.radius_pill), 1f
            )
            button.setOnClickListener { hide(); if (i != buttons.lastIndex) action.run() }
            stack.addView(button, LinearLayout.LayoutParams(-1, controlHeight + context.dp(20) * extraLines).apply { topMargin = if (i == 0) 0 else gap })
            built += button
            if (first == null) first = button
        }
        column.addView(stack, LinearLayout.LayoutParams(-1, -2).apply { topMargin = LauncherTheme.px(context, R.dimen.space_4) })

        container.addView(card, FrameLayout.LayoutParams(context.dp(if (image != null) 720 else 560), -2, Gravity.CENTER))
        container.visibility = View.VISIBLE
        this.card = card
        if (!refresh) com.example.tvlauncher.design.Motion.enter(container, card, context.dp(28).toFloat())
        lastButtons = built
        (built.getOrNull(focusedIndex) ?: first)?.post { (built.getOrNull(focusedIndex) ?: first)?.requestFocus() }
    }

    /** Shows any view centred over a scrim, focusing [focus]. Back dismisses it like every other sheet. */
    fun showCustom(view: View, focus: View?, flight: Flight? = null) {
        rememberFocus()
        settle()
        container.removeAllViews()
        container.setBackgroundColor(ContextCompat.getColor(context, R.color.sheet_scrim))
        container.clipChildren = false
        // Measured before anything moves: the page is about to step back behind the sheet.
        val flying = flight != null && Motion.animationsOn(container) &&
            flight.source.isShown && flight.source.getGlobalVisibleRect(flight.from)
        container.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        container.visibility = View.VISIBLE
        card = view
        lastButtons = emptyList()
        if (flying) { this.flight = flight; flyIn(view, flight!!) } else Motion.enter(container, view, context.dp(28).toFloat())
        focus?.post { focus.requestFocus() }
    }

    fun hide() {
        if (!isVisible || isClosing) return
        isClosing = true
        // Focus goes home at once, so the remote answers immediately; the sheet only has to finish leaving.
        container.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        previousFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        container.requestLayout()   // lets the page start coming back while the sheet leaves
        val f = flight
        if (f != null && f.source.isAttachedToWindow && Motion.animationsOn(container)) flyOut(f)
        else Motion.exit(container, card) { settle() }
    }

    private fun rememberFocus() {
        // Reopened while still closing: the way back is the one already remembered.
        if (!isClosing) previousFocus = container.rootView.findFocus()
    }

    /** Ends any entrance or exit in its final state and puts the container back to rest. */
    private fun settle() {
        surfaceFade?.cancel(); surfaceFade = null
        container.animate().cancel()
        card?.animate()?.cancel()
        flight?.let { f ->
            f.target.animate().cancel()
            f.source.alpha = 1f
        }
        flight = null
        if (isClosing) {
            isClosing = false
            container.visibility = View.GONE
            container.removeAllViews()
            card = null
        }
        container.alpha = 1f
        container.descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        (container.background as? ColorDrawable)?.alpha = 255
    }

    /** The picture lifts off the page and settles into the sheet; the sheet's surface and words gather around it. */
    private fun flyIn(card: View, f: Flight) {
        f.source.alpha = 0f
        val scrim = container.background as? ColorDrawable
        val surface = card.background
        scrim?.alpha = 0; surface?.alpha = 0
        f.reveal.forEach { it.alpha = 0f; it.translationY = context.dp(10).toFloat() }
        f.target.visibility = View.INVISIBLE
        card.post {
            if (flight !== f || isClosing) return@post
            placeAt(f.target, f.from)
            f.target.visibility = View.VISIBLE
            f.target.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(Motion.FLIGHT_MS).setInterpolator(Motion.SWOOSH).start()
            fadeSurfaces(scrim, surface, 255, Motion.SHEET_IN_MS)
            f.reveal.forEach {
                it.animate().alpha(1f).translationY(0f).setStartDelay(Motion.FLIGHT_MS / 3)
                    .setDuration(Motion.SHEET_IN_MS).setInterpolator(Motion.SETTLE).start()
            }
        }
    }

    /** The reverse: the sheet dissolves from around the picture, which drops back into its place on the page. */
    private fun flyOut(f: Flight) {
        val scrim = container.background as? ColorDrawable
        val surface = card?.background
        f.reveal.forEach { it.animate().alpha(0f).setStartDelay(0).setDuration(Motion.SHEET_OUT_MS / 2).setInterpolator(Motion.SETTLE).start() }
        fadeSurfaces(scrim, surface, 0, Motion.SHEET_OUT_MS)
        // Back to where the picture was taken from: the page comes forward to exactly that position.
        val o = offsetsTo(f.target, f.from)
        f.target.animate().translationX(o[0]).translationY(o[1]).scaleX(o[2]).scaleY(o[3])
            .setDuration(Motion.FLIGHT_MS - 60).setInterpolator(Motion.SWOOSH)
            .withEndAction { if (flight === f) settle() }.start()
    }

    private fun fadeSurfaces(scrim: ColorDrawable?, surface: android.graphics.drawable.Drawable?, to: Int, ms: Long) {
        surfaceFade?.cancel()
        surfaceFade = android.animation.ValueAnimator.ofInt(scrim?.alpha ?: surface?.alpha ?: 255, to).apply {
            duration = ms; interpolator = Motion.SETTLE
            addUpdateListener { val a = it.animatedValue as Int; scrim?.alpha = a; surface?.alpha = a }
            start()
        }
    }

    /** Puts [view] (pivot at its top-left) exactly over [rect] in screen space. */
    private fun placeAt(view: View, rect: android.graphics.Rect) {
        val o = offsetsTo(view, rect)
        view.translationX = o[0]; view.translationY = o[1]; view.scaleX = o[2]; view.scaleY = o[3]
    }

    /** Translation and scale that put [view]'s untransformed layout box over [rect]; sets the pivot to top-left. */
    private fun offsetsTo(view: View, rect: android.graphics.Rect): FloatArray {
        val saved = floatArrayOf(view.translationX, view.translationY, view.scaleX, view.scaleY)
        view.translationX = 0f; view.translationY = 0f; view.scaleX = 1f; view.scaleY = 1f
        val at = IntArray(2); view.getLocationOnScreen(at)
        view.translationX = saved[0]; view.translationY = saved[1]; view.scaleX = saved[2]; view.scaleY = saved[3]
        view.pivotX = 0f; view.pivotY = 0f
        val w = view.width.coerceAtLeast(1); val h = view.height.coerceAtLeast(1)
        return floatArrayOf((rect.left - at[0]).toFloat(), (rect.top - at[1]).toFloat(), rect.width() / w.toFloat(), rect.height() / h.toFloat())
    }
}

// ---------------------------------------------------------------------------------------------
// Tonight card
// ---------------------------------------------------------------------------------------------

/** "Tonight?": a framed paper print with three lines to choose from -- Watch, Make, Eat. */
class TonightCard(
    private val context: Context,
    private val onWatch: (TonightFilm) -> Unit,
    private val onMake: (Project) -> Unit,
    private val onEat: (Recipe) -> Unit
) {
    val view: View
    private var plan: TonightPlan? = null

    private class Line(val label: TextView, val title: TextView, val sub: TextView, val row: View)
    private val frameMat = LauncherTheme.imageMat(context, context.dp(10).toFloat()).apply { alpha = 0 }
    private val lines = mutableListOf<Line>()

    /** The three focusable lines (Watch, Make, Eat), top to bottom. */
    val rows: List<View> get() = lines.map { it.row }

    init {
        val frame = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(28), context.dp(22), context.dp(28), context.dp(20))
            background = LayerDrawable(arrayOf(
                GradientDrawable().apply { cornerRadius = context.dp(10).toFloat(); setColor(WALNUT_FRAME) },
                paper(context, 4)
            )).apply { val i = context.dp(9); setLayerInset(1, i, i, i, i) }
            elevation = context.dp(6).toFloat()
            foreground = frameMat
        }
        val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Type.text(context, "Tonight?", Type.Style.TITLE, PAPER_INK).apply { typeface = Typeface.create("serif", Typeface.BOLD_ITALIC) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Type.text(context, SimpleDateFormat("EEEE", Locale.UK).format(Date()), Type.Style.EYEBROW, TERRACOTTA))
        frame.addView(head, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.dp(6) })

        listOf("Watch", "Make", "Eat").forEachIndexed { index, name -> frame.addView(buildRow(name, index), LinearLayout.LayoutParams(-1, context.dp(62))) }
        view = frame
    }

    fun update(newPlan: TonightPlan) {
        plan = newPlan
        set(0, newPlan.film?.let { it.title + (it.year?.let { y -> " ($y)" } ?: "") }, newPlan.film?.note)
        set(1, newPlan.project?.title, newPlan.project?.nextAction?.let { "Next: $it" })
        set(2, newPlan.recipe?.title, listOfNotNull(newPlan.recipe?.cuisine, newPlan.recipe?.descriptor).joinToString(" · ").ifBlank { null })
    }

    private fun set(index: Int, title: String?, sub: String?) {
        Motion.swapText(lines[index].title, title ?: "Nothing yet")
        Motion.swapText(lines[index].sub, sub ?: "")
        lines[index].sub.visibility = if (sub.isNullOrBlank()) View.GONE else View.VISIBLE
        lines[index].row.isEnabled = title != null
    }

    private fun buildRow(name: String, index: Int): View {
        val label = Type.text(context, name, Type.Style.EYEBROW, TERRACOTTA)
        val title = Type.text(context, "", Type.Style.HEADING, PAPER_INK).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = Type.text(context, "", Type.Style.CAPTION, PAPER_INK_DIM).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        column.addView(title); column.addView(sub)
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true
            setPadding(context.dp(16), 0, context.dp(16), 0)
            background = ColorDrawable(Color.TRANSPARENT)
            addView(label, LinearLayout.LayoutParams(context.dp(74), -2))
            addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        }
        // On paper, ivory is the paper itself, so a line lights as a deeper paper band; the whole print gets the mat.
        val lit = LauncherTheme.surface(context, R.color.paper_lit, R.dimen.radius_small)
        row.setOnFocusChangeListener { v, hasFocus ->
            v.background = if (hasFocus) lit else ColorDrawable(Color.TRANSPARENT)
            LauncherTheme.fadeDrawable(view, frameMat, rows.any { it.isFocused })
            if (hasFocus) scrollFocusIntoView(v, true)
        }
        row.setOnClickListener {
            val p = plan ?: return@setOnClickListener
            when (index) {
                0 -> p.film?.let(onWatch)
                1 -> p.project?.let(onMake)
                else -> p.recipe?.let(onEat)
            }
        }
        lines += Line(label, title, sub, row)
        return row
    }
}

// ---------------------------------------------------------------------------------------------
// MESA recipe cards
// ---------------------------------------------------------------------------------------------

/** A small stack of vintage recipe cards. OK shuffles to another recipe; nothing else to learn. */
class RecipeCardStack(private val context: Context, private val deck: RecipeDeck) {
    val view: FrameLayout
    private val face = FrameLayout(context)
    private val cuisine = Type.text(context, "", Type.Style.EYEBROW, TERRACOTTA).apply { maxLines = 1 }
    private val title = Type.text(context, "", Type.Style.HEADING, PAPER_INK).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 0.95f) }
    private val descriptor = Type.text(context, "", Type.Style.CAPTION, PAPER_INK_DIM).apply { typeface = Typeface.create("serif", Typeface.ITALIC); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val motif = RecipeMotifView(context)
    private val faceMat = LauncherTheme.imageMat(context, context.dp(6).toFloat()).apply { alpha = 0 }
    private var busy = false

    init {
        view = FrameLayout(context).apply { isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false; contentDescription = "Recipe cards. OK to shuffle." }

        fun card(rotation: Float, dx: Int, dy: Int): FrameLayout = FrameLayout(context).apply {
            background = LayerDrawable(arrayOf(paper(context, 6), GradientDrawable().apply {
                cornerRadius = context.dp(4).toFloat(); setColor(Color.TRANSPARENT); setStroke(context.dp(1), 0x40261E14)
            })).apply { val i = context.dp(7); setLayerInset(1, i, i, i, i) }
            this.rotation = rotation; translationX = context.dp(dx).toFloat(); translationY = context.dp(dy).toFloat()
            elevation = context.dp(2).toFloat()
        }
        val w = context.dp(300); val h = context.dp(188)
        view.addView(card(-5f, -10, 6), FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = context.dp(8) })
        view.addView(card(3f, 8, 3), FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = context.dp(8) })

        face.apply {
            background = LayerDrawable(arrayOf(paper(context, 6), GradientDrawable().apply {
                cornerRadius = context.dp(4).toFloat(); setColor(Color.TRANSPARENT); setStroke(context.dp(1), 0x66261E14)
            })).apply { val i = context.dp(7); setLayerInset(1, i, i, i, i) }
            elevation = context.dp(5).toFloat()
            setPadding(context.dp(22), context.dp(18), context.dp(18), context.dp(16))
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(cuisine)
        column.addView(View(context).apply { setBackgroundColor(0x40261E14) }, LinearLayout.LayoutParams(-1, context.dp(1)).apply { topMargin = context.dp(6); bottomMargin = context.dp(8) })
        column.addView(title)
        column.addView(descriptor, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
        face.addView(column, FrameLayout.LayoutParams(-1, -1).apply { marginEnd = context.dp(64) })
        face.addView(Type.text(context, "MESA", Type.Style.EYEBROW, PAPER_INK_DIM), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
        face.foreground = faceMat
        face.addView(motif, FrameLayout.LayoutParams(context.dp(58), context.dp(58), Gravity.BOTTOM or Gravity.END))
        view.addView(face, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = context.dp(8) })

        view.setOnFocusChangeListener { v, hasFocus ->
            LauncherTheme.animateFocus(v, hasFocus, 1.05f)
            face.animate().translationY(if (hasFocus) -context.dp(6).toFloat() else 0f).setDuration(com.example.tvlauncher.design.Motion.FOCUS_IN_MS).setInterpolator(com.example.tvlauncher.design.Motion.SETTLE).start()
            LauncherTheme.fadeDrawable(face, faceMat, hasFocus)
            if (hasFocus) { scrollFocusIntoView(v, true); com.example.tvlauncher.ui.PageHint.show(context.getString(R.string.hint_shuffle)) }
        }
        view.setOnClickListener { shuffle() }
        bind(deck.current)
    }

    /** Shows [recipeId] without animation (used to open on tonight's recipe). */
    fun showRecipe(recipeId: String?) { deck.show(recipeId); bind(deck.current) }

    fun shuffle() {
        if (busy) return
        val next = deck.shuffle() ?: return
        busy = true
        val travel = context.dp(70).toFloat()
        face.animate().translationX(travel).rotation(7f).alpha(0f).setDuration(150).setInterpolator(com.example.tvlauncher.design.Motion.SETTLE).withEndAction {
            bind(next)
            face.translationX = -travel; face.rotation = -7f
            face.animate().translationX(0f).rotation(0f).alpha(1f).setDuration(250).setInterpolator(com.example.tvlauncher.design.Motion.SWOOSH).withEndAction { busy = false }.start()
        }.start()
    }

    private fun bind(recipe: Recipe?) {
        cuisine.text = (recipe?.cuisine ?: "Recipe").uppercase(Locale.UK)
        title.text = recipe?.title ?: "No recipes yet"
        descriptor.text = recipe?.descriptor ?: ""
        motif.theme = recipe?.theme ?: "sun"
    }
}

/** A small abstract emblem for a recipe card, chosen by [theme]: sun, arch, leaf, bands or circles. */
class RecipeMotifView(context: Context) : View(context) {
    var theme: String = "sun"
        set(value) { field = value; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val terracotta = TERRACOTTA
    private val ochre = 0xFFB07C22.toInt()
    private val teal = 0xFF28766C.toInt()
    private val olive = 0xFF6B6F3A.toInt()

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) / 100f
        c.save(); c.scale(s, s)
        p.style = Paint.Style.FILL
        when (theme) {
            "arch" -> {
                p.color = teal; c.drawRoundRect(20f, 22f, 80f, 100f, 30f, 30f, p)
                p.color = ochre; c.drawCircle(50f, 30f, 12f, p)
            }
            "leaf" -> {
                p.color = olive
                c.drawPath(Path().apply { moveTo(50f, 92f); quadTo(6f, 56f, 50f, 8f); quadTo(94f, 56f, 50f, 92f); close() }, p)
                p.color = 0x99F3E9CF.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 3f
                c.drawLine(50f, 86f, 50f, 22f, p)
            }
            "bands" -> {
                listOf(terracotta, ochre, teal).forEachIndexed { i, col ->
                    p.color = col
                    c.drawPath(Path().apply { moveTo(0f, 30f + i * 24f); lineTo(100f, 6f + i * 24f); lineTo(100f, 26f + i * 24f); lineTo(0f, 50f + i * 24f); close() }, p)
                }
            }
            "circles" -> {
                p.color = terracotta; c.drawCircle(36f, 40f, 26f, p)
                p.color = 0xCC28766C.toInt(); c.drawCircle(64f, 40f, 26f, p)
                p.color = 0xCCB07C22.toInt(); c.drawCircle(50f, 66f, 26f, p)
            }
            else -> {
                p.color = ochre; c.drawCircle(50f, 50f, 26f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 5f; p.color = terracotta
                c.drawCircle(50f, 50f, 40f, p)
            }
        }
        c.restore()
    }
}

/** Consumes Left on the first child and Right on the last, so focus stays on the row instead of jumping to another one. */
internal fun trapEdges(row: LinearLayout) {
    val count = row.childCount
    for (i in 0 until count) {
        row.getChildAt(i).setOnKeyListener { _, keyCode, event ->
            event.action == android.view.KeyEvent.ACTION_DOWN &&
                ((keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT && i == 0) || (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT && i == count - 1))
        }
    }
}
