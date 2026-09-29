package com.example.tvlauncher.ui.room

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.example.tvlauncher.data.listening.ListeningConfig
import com.example.tvlauncher.data.listening.ListeningSource
import com.example.tvlauncher.data.listening.ListeningStatus
import com.example.tvlauncher.data.listening.ConfiguredListeningSource
import com.example.tvlauncher.data.listening.Person
import com.example.tvlauncher.data.listening.PersonalListeningState
import com.example.tvlauncher.ui.scrollFocusIntoView
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private fun Context.px(v: Int) = (v * resources.displayMetrics.density).toInt()

private val OCHRE = 0xFFDDBB57.toInt()
private val BRASS_LIGHT = 0xFFD2AC55.toInt()
private val BRASS_DARK = 0xFF9C7A2B.toInt()
private val PLAQUE_INK = 0xFF2A1F10.toInt()
private val IVORY = 0xFFF3E9CF.toInt()

// ---------------------------------------------------------------------------------------------
// The deck
// ---------------------------------------------------------------------------------------------

/**
 * A record deck drawn on a 260 x 170 canvas, in the manner of a Braun or Rega: walnut plinth, brushed
 * aluminium plate, black vinyl with a still sheen, slim aluminium tonearm and a small pilot light.
 * While [render]ed as playing, the label turns and the arm settles onto the record.
 */
class TurntableView(context: Context, private val accent: Int) : View(context) {

    var lit: Boolean = false
        set(value) { field = value; invalidate() }

    private var playing = false
    private var armDown = false
    private var spin = 0f
    private var arm = 0f          // 0 = resting off the record, 1 = stylus on the groove
    private var pulse = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1800; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; pulse = ((sin(Math.toRadians(spin.toDouble())) + 1) / 2).toFloat(); if (isShown) invalidate() }
    }
    private var armAnimator: ValueAnimator? = null

    fun render(status: ListeningStatus) {
        playing = status == ListeningStatus.PLAYING_NOW
        armDown = status == ListeningStatus.PLAYING_NOW || status == ListeningStatus.PAUSED
        moveArm(if (armDown) 1f else 0f)
        if (playing && isAttachedToWindow) { if (!spinAnimator.isStarted) spinAnimator.start() }
        else { spinAnimator.cancel(); pulse = 0f }
        invalidate()
    }

    private fun moveArm(target: Float) {
        armAnimator?.cancel()
        armAnimator = ValueAnimator.ofFloat(arm, target).apply {
            duration = 700
            addUpdateListener { arm = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (playing && !spinAnimator.isStarted) spinAnimator.start() }
    override fun onDetachedFromWindow() { spinAnimator.cancel(); armAnimator?.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val s = min(width / W, height / H)
        c.save(); c.translate((width - W * s) / 2f, (height - H * s) / 2f); c.scale(s, s)
        drawPlinth(c); drawPlatter(c); drawRecord(c); drawArm(c); drawControls(c)
        c.restore()
    }

    private fun flat(color: Int) = paint.apply { shader = null; style = Paint.Style.FILL; this.color = color }

    /** A walnut plinth with a brushed aluminium top plate: Braun/Rega restraint, no cartoon cabinet. */
    private fun drawPlinth(c: Canvas) {
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, 0f, 0f, H, WALNUT_LIGHT, WALNUT_DARK, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 0f, W, H), 12f, 12f, paint)
        paint.shader = LinearGradient(0f, 12f, 0f, H - 12f, PLATE_LIGHT, PLATE_DARK, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(10f, 10f, W - 10f, H - 10f), 6f, 6f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1f; paint.color = 0x33000000
        c.drawRoundRect(RectF(10f, 10f, W - 10f, H - 10f), 6f, 6f, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawPlatter(c: Canvas) {
        c.drawCircle(CX, CY, 66f, flat(0xFF8F887B.toInt()))            // platter rim
        c.drawCircle(CX, CY, 63.5f, flat(0xFF2B2A26.toInt()))          // mat
    }

    private fun drawRecord(c: Canvas) {
        c.drawCircle(CX, CY, 60f, flat(0xFF18181A.toInt()))
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.6f; paint.color = 0x1FF3E9CF
        for (r in listOf(28f, 36f, 44f, 52f)) c.drawCircle(CX, CY, r, paint)
        // A still sheen, like light from the lamp across the grooves.
        paint.style = Paint.Style.FILL
        paint.shader = android.graphics.SweepGradient(CX, CY, intArrayOf(0x00F3E9CF, 0x22F3E9CF, 0x00F3E9CF, 0x00F3E9CF, 0x18F3E9CF, 0x00F3E9CF), floatArrayOf(0f, 0.12f, 0.24f, 0.5f, 0.62f, 0.74f))
        c.drawCircle(CX, CY, 58f, paint); paint.shader = null
        c.save(); c.rotate(spin, CX, CY)
        c.drawCircle(CX, CY, 18f, flat(accent))
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f; paint.color = 0x80F3E9CF.toInt()
        c.drawLine(CX, CY - 7f, CX, CY - 15f, paint)                   // a mark on the label that shows the turn
        paint.style = Paint.Style.FILL
        c.restore()
        c.drawCircle(CX, CY, 2.4f, flat(0xFFD8D2C4.toInt()))           // spindle
    }

    /** A slim aluminium tonearm on a round bearing, with a counterweight behind it. */
    private fun drawArm(c: Canvas) {
        val px = 204f; val py = 32f
        val angle = Math.toRadians((4f + 27f * arm).toDouble())
        val tipX = px - (88f * sin(angle)).toFloat(); val tipY = py + (88f * cos(angle)).toFloat()
        val tailX = px + (18f * sin(angle)).toFloat(); val tailY = py - (18f * cos(angle)).toFloat()
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 2.4f; paint.color = 0xFFDCD6C8.toInt()
        c.drawLine(tailX, tailY, tipX, tipY, paint)
        paint.style = Paint.Style.FILL
        c.drawCircle(tailX, tailY, 5.5f, flat(0xFF3A3833.toInt()))      // counterweight
        c.drawCircle(px, py, 8f, flat(0xFF8F887B.toInt()))              // bearing
        c.drawCircle(px, py, 3.5f, flat(0xFFDCD6C8.toInt()))
        c.save(); c.rotate(Math.toDegrees(-angle).toFloat() + 14f, tipX, tipY)
        c.drawRoundRect(RectF(tipX - 4f, tipY - 1f, tipX + 4f, tipY + 11f), 1.5f, 1.5f, flat(0xFF2B2A26.toInt()))
        c.restore()
    }

    /** Two small speed buttons and a pilot light that is only lit while playing. */
    private fun drawControls(c: Canvas) {
        c.drawCircle(214f, 128f, 4.5f, flat(0xFF8F887B.toInt()))
        c.drawCircle(230f, 128f, 4.5f, flat(0xFF8F887B.toInt()))
        c.drawCircle(222f, 106f, 2.6f, flat(if (playing) 0xFFE8A544.toInt() else 0xFF6E6A62.toInt()))
    }

    private companion object {
        const val W = 260f
        const val H = 170f
        const val CX = 100f
        const val CY = 85f
        val WALNUT_LIGHT = 0xFF7A5738.toInt()
        val WALNUT_DARK = 0xFF55391F.toInt()
        val PLATE_LIGHT = 0xFFD9D2C3.toInt()
        val PLATE_DARK = 0xFFBDB5A4.toInt()
    }
}

// ---------------------------------------------------------------------------------------------
// The record sleeve propped beside the deck
// ---------------------------------------------------------------------------------------------

/** A square record sleeve: real artwork when there is a URL, otherwise a painted MCM cover made from the track's name. */
class SleeveView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var seed: String? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val palette = intArrayOf(0xFFAF5938.toInt(), 0xFF28766C.toInt(), 0xFFB07C22.toInt(), 0xFF6B6F3A.toInt(), 0xFF3E5C86.toInt(), 0xFF795638.toInt())

    fun show(seed: String?, artwork: Bitmap?) { this.seed = seed; bitmap = artwork; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        paint.color = 0xFFEADFBF.toInt(); c.drawRoundRect(RectF(0f, 0f, w, h), 4f, 4f, paint)
        val art = RectF(w * 0.06f, h * 0.06f, w * 0.94f, h * 0.94f)
        val s = seed
        when {
            s == null -> {
                // Nothing playing: an empty inner sleeve, plain paper.
                paint.color = 0xFFE3D7B6.toInt(); c.drawRect(art, paint)
                paint.color = 0x14261E14; c.drawCircle(art.centerX(), art.centerY(), art.width() * 0.08f, paint)
            }
            bitmap != null -> c.drawBitmap(bitmap!!, null, art, Paint(Paint.FILTER_BITMAP_FLAG))
            else -> paintCover(c, art, s.hashCode() and 0x7fffffff)
        }
    }

    private fun paintCover(c: Canvas, r: RectF, hash: Int) {
        val base = palette[hash % palette.size]
        val alt = palette[(hash / 7) % palette.size].let { if (it == base) palette[(hash / 7 + 1) % palette.size] else it }
        c.save(); c.clipRect(r)
        paint.color = base; c.drawRect(r, paint)
        val u = r.width()
        when ((hash / 13) % 4) {
            0 -> { paint.color = alt; c.drawCircle(r.centerX(), r.top + u * 0.42f, u * 0.28f, paint); paint.color = IVORY; c.drawRect(r.left, r.top + u * 0.72f, r.right, r.bottom, paint) }
            1 -> { paint.color = alt; c.drawRect(r.left, r.top, r.centerX(), r.bottom, paint); paint.color = IVORY; c.drawCircle(r.centerX(), r.centerY(), u * 0.22f, paint) }
            2 -> { paint.color = alt; c.drawRoundRect(RectF(r.left + u * 0.2f, r.top + u * 0.25f, r.right - u * 0.2f, r.bottom + u), u * 0.3f, u * 0.3f, paint); paint.color = IVORY; c.drawCircle(r.centerX(), r.top + u * 0.4f, u * 0.09f, paint) }
            else -> { for (i in 0..2) { paint.color = if (i % 2 == 0) alt else IVORY; c.drawRect(r.left, r.top + u * (0.2f + i * 0.25f), r.right, r.top + u * (0.36f + i * 0.25f), paint) } }
        }
        c.restore()
    }
}

// ---------------------------------------------------------------------------------------------
// One person's module
// ---------------------------------------------------------------------------------------------

/** EVA or DANIEL: name and status above a deck, with the sleeve propped beside it and a brass plaque underneath. */
class TurntableModule(private val context: Context, val person: Person, accent: Int, private val onOpen: (PersonalListeningState) -> Unit) {
    val view: FrameLayout
    private val deck = TurntableView(context, accent)
    private val sleeve = SleeveView(context)
    private val name = com.example.tvlauncher.design.Type.onPainting(com.example.tvlauncher.design.Type.text(context, person.displayName, com.example.tvlauncher.design.Type.Style.EYEBROW))
    private val status = com.example.tvlauncher.design.Type.onPainting(com.example.tvlauncher.design.Type.text(context, "", com.example.tvlauncher.design.Type.Style.CAPTION))
    private val title = com.example.tvlauncher.design.Type.text(context, "", com.example.tvlauncher.design.Type.Style.BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val artist = com.example.tvlauncher.design.Type.text(context, "", com.example.tvlauncher.design.Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private var state: PersonalListeningState? = null

    init {
        val r = context.resources
        fun px(res: Int) = r.getDimensionPixelSize(res)
        val root = FrameLayout(context).apply { isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false }

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(name)
        header.addView(status, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(com.example.tvlauncher.R.dimen.space_3) })
        root.addView(header, FrameLayout.LayoutParams(-1, context.px(24), Gravity.TOP or Gravity.START))

        val deckFrame = FrameLayout(context).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, context.px(12).toFloat()) }
            }
            elevation = context.px(6).toFloat()
            addView(deck, FrameLayout.LayoutParams(-1, -1))
        }
        val mat = com.example.tvlauncher.design.LauncherTheme.imageMat(context, context.px(12).toFloat()).apply { alpha = 0 }
        deckFrame.foreground = mat
        root.addView(deckFrame, FrameLayout.LayoutParams(context.px(260), context.px(170), Gravity.TOP or Gravity.START).apply { topMargin = context.px(34) })

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(sleeve.apply { elevation = context.px(6).toFloat() }, LinearLayout.LayoutParams(context.px(112), context.px(112)))
        val plaque = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(com.example.tvlauncher.R.dimen.space_2) + context.px(4), px(com.example.tvlauncher.R.dimen.space_2), px(com.example.tvlauncher.R.dimen.space_2), px(com.example.tvlauncher.R.dimen.space_2))
            background = com.example.tvlauncher.design.LauncherTheme.surface(context, com.example.tvlauncher.R.color.surface, com.example.tvlauncher.R.dimen.radius_small)
        }
        plaque.addView(title); plaque.addView(artist)
        column.addView(plaque, LinearLayout.LayoutParams(context.px(148), -2).apply { topMargin = px(com.example.tvlauncher.R.dimen.space_2) })
        root.addView(column, FrameLayout.LayoutParams(context.px(150), -2, Gravity.TOP or Gravity.START).apply { marginStart = context.px(276); topMargin = context.px(34) })

        root.setOnFocusChangeListener { v, hasFocus ->
            deck.lit = hasFocus
            com.example.tvlauncher.design.LauncherTheme.fadeDrawable(deckFrame, mat, hasFocus)
            com.example.tvlauncher.design.LauncherTheme.animateFocus(deckFrame, hasFocus)
            if (hasFocus) scrollFocusIntoView(v, true)
        }
        root.setOnClickListener { state?.let(onOpen) }
        view = root
    }

    fun bind(newState: PersonalListeningState, artwork: Bitmap?, nowMs: Long) {
        val changedTrack = state?.trackTitle != newState.trackTitle || state?.artistName != newState.artistName
        // A new record (not the first reading, and not the same one re-polled) is swapped in rather than cut to.
        val animate = changedTrack && state != null
        state = newState
        deck.render(newState.status)
        val has = newState.status != ListeningStatus.NOTHING_AVAILABLE && newState.trackTitle != null
        val newTitle = if (has) newState.trackTitle!! else "Nothing on the platter"
        val newArtist = if (has) (newState.artistName ?: "") else ""
        if (animate) { com.example.tvlauncher.design.Motion.swapText(title, newTitle); com.example.tvlauncher.design.Motion.swapText(artist, newArtist) }
        else { title.text = newTitle; artist.text = newArtist }
        title.typeface = Typeface.create(if (has) "sans-serif-medium" else "serif", if (has) Typeface.NORMAL else Typeface.ITALIC)
        artist.visibility = if (has) View.VISIBLE else View.GONE
        val seed = if (has) "${newState.trackTitle}|${newState.artistName}" else null
        if (animate) com.example.tvlauncher.design.Motion.swapImage(sleeve) { sleeve.show(seed, artwork) }
        else if (changedTrack || artwork != null) sleeve.show(seed, artwork)
        tick(nowMs)
        view.contentDescription = "${person.displayName}. ${status.text}. ${title.text}, ${artist.text}"
    }

    /** Cheap once-a-second refresh of the status line (elapsed time). */
    fun tick(nowMs: Long) { state?.let { status.text = it.statusLine(nowMs) } }
}

// ---------------------------------------------------------------------------------------------
// The section
// ---------------------------------------------------------------------------------------------

/**
 * "Now spinning": Eva's and Daniel's decks side by side. Polls [source] while the home screen is
 * visible ([start] / [stop]) and animates whichever decks are playing.
 */
class NowSpinning(private val activity: android.app.Activity, private val sheet: InfoSheet, private val source: ListeningSource = ConfiguredListeningSource(activity)) {

    private val pollMs = ListeningConfig(activity).pollSeconds * 1000L
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var running = false
    private val artwork = HashMap<String, Bitmap>()
    private val latest = HashMap<Person, PersonalListeningState>()

    private val modules = mapOf(
        Person.EVA to TurntableModule(activity, Person.EVA, 0xFFAF5938.toInt(), ::open),
        Person.DANIEL to TurntableModule(activity, Person.DANIEL, 0xFF28766C.toInt(), ::open)
    )

    /** The row containing both modules, ready to add to the page. */
    val row: View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
        modules.values.forEach { m -> addView(m.view, LinearLayout.LayoutParams(activity.px(432), activity.px(214))) }   // 2 x 432 = the 864dp between the margins
        trapEdges(this)
    }

    private val poll = object : Runnable { override fun run() { refresh(); if (running) handler.postDelayed(this, pollMs) } }
    private val tick = object : Runnable {
        override fun run() { val now = System.currentTimeMillis(); modules.values.forEach { it.tick(now) }; if (running) handler.postDelayed(this, 1000) }
    }

    fun start() { if (running) return; running = true; handler.post(poll); handler.postDelayed(tick, 1000) }
    fun stop() { running = false; handler.removeCallbacks(poll); handler.removeCallbacks(tick) }
    fun close() { stop(); worker.shutdownNow() }

    private fun refresh() {
        worker.execute {
            Person.values().forEach { person ->
                val state = source.fetch(person)
                val art = state.artworkUrl?.let { url -> artwork[url] ?: download(url)?.also { artwork[url] = it } }
                handler.post { latest[person] = state; modules[person]?.bind(state, art, System.currentTimeMillis()) }
            }
        }
    }

    private fun open(state: PersonalListeningState) {
        val now = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        state.albumName?.let { lines += "Album: $it" }
        state.positionMs(now)?.let { pos -> state.durationMs?.let { lines += "Position: ${clock(pos)} of ${clock(it)}" } }
        // Demo entries always carry a duration; real Last.fm data never does.
        lines += if (source.isDemo && state.durationMs != null)
            "Demo data. Add a Last.fm user and key in assets/home/listening.json to show real listening."
        else "Source: ${state.provider.label}"
        sheet.show("${state.person.displayName} · ${state.status.name.lowercase(Locale.UK).replace('_', ' ')}", state.trackTitle ?: "Nothing on the platter", state.artistName, lines)
    }

    private fun clock(ms: Long) = String.format(Locale.UK, "%d:%02d", ms / 60000, (ms / 1000) % 60)

    private fun download(url: String): Bitmap? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        try { c.inputStream.use { BitmapFactory.decodeStream(it) } } finally { c.disconnect() }
    } catch (_: Exception) { null }
}
