package com.example.tvlauncher.ui

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvlauncher.R
import com.example.tvlauncher.design.Motion
import com.example.tvlauncher.system.PictureControl

/**
 * The side panel that slides in over whatever is playing when Settings is held on the remote --
 * our replacement for the Google TV picture panel. It is drawn by [com.example.tvlauncher.system.RemoteKeyService]
 * as an accessibility overlay that never takes focus, so the app underneath keeps playing; the
 * service hands it the remote's keys while it is up ([onKey]).
 *
 * Rows: picture preset, five sliders, colour temperature, then two ways out to fuller settings.
 */
class PictureOverlay(
    private val context: Context,
    private val control: PictureControl,
    private val onMorePicture: () -> Unit,
    private val onAllSettings: () -> Unit
) {
    private sealed class Row(val label: String) {
        class Choice(label: String, val options: List<Pair<Int, String>>, val read: () -> Int, val write: (Int) -> Boolean) : Row(label)
        class Slider(val setting: PictureControl.Setting) : Row(setting.label)
        class Action(label: String, val run: () -> Unit) : Row(label)
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { hide() }
    private val d = context.resources.displayMetrics.density

    private val rows: List<Row> = listOf(
        Row.Choice("Preset", control.presets, control::getPreset, control::setPreset),
        Row.Slider(PictureControl.Setting.BACKLIGHT),
        Row.Slider(PictureControl.Setting.BRIGHTNESS),
        Row.Slider(PictureControl.Setting.CONTRAST),
        Row.Slider(PictureControl.Setting.COLOUR),
        Row.Slider(PictureControl.Setting.SHARPNESS),
        Row.Choice("Colour temp", control.temperatures, control::getTemperature, control::setTemperature),
        Row.Action("More picture options") { hide(); onMorePicture() },
        Row.Action("All settings") { hide(); onAllSettings() }
    )

    private val values = IntArray(rows.size)
    private val rowViews = ArrayList<RowViews>()
    private var selected = 0
    private var root: FrameLayout? = null
    private var panel: View? = null

    val isShowing: Boolean get() = root != null

    fun show() {
        if (isShowing) { poke(); return }
        refreshValues()
        selected = 0
        val view = build()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never focusable or touchable: the playing app keeps focus and never pauses.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        root = view
        render()
        panel?.let {
            // Slides in from the right edge, like the old Google TV side panel.
            if (Motion.animationsOn(it)) {
                it.alpha = 0f; it.translationX = dp(48).toFloat()
                it.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(Motion.SWOOSH).start()
            }
        }
        poke()
    }

    fun hide() {
        handler.removeCallbacks(hideLater)
        val view = root ?: return
        root = null
        panel = null
        rowViews.clear()
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    /** Handles a remote key while the panel is up. Returns true when it was ours. */
    fun onKey(event: KeyEvent): Boolean {
        if (!isShowing) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) adjust(-1, event.repeatCount)
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) adjust(1, event.repeatCount)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (!down && event.repeatCount == 0) (rows[selected] as? Row.Action)?.run?.invoke()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) hide()
            else -> return false   // volume, play/pause, home etc. carry on to the TV as normal
        }
        poke()
        return true
    }

    private fun poke() {
        handler.removeCallbacks(hideLater)
        handler.postDelayed(hideLater, IDLE_HIDE_MS)
    }

    private fun move(delta: Int) {
        selected = (selected + delta).coerceIn(0, rows.lastIndex)
        render()
    }

    private fun adjust(direction: Int, repeat: Int) {
        when (val row = rows[selected]) {
            is Row.Slider -> {
                if (values[selected] < 0) return
                val step = if (repeat > 8) 3 else 1   // holding the arrow speeds up
                val next = (values[selected] + direction * step).coerceIn(0, 100)
                if (next == values[selected]) return
                if (control.set(row.setting, next)) values[selected] = next
            }
            is Row.Choice -> {
                if (repeat > 0) return   // one step per press: presets are heavy to switch
                val keys = row.options.map { it.first }
                val at = keys.indexOf(values[selected])
                val next = keys[if (at < 0) 0 else (at + direction + keys.size) % keys.size]
                if (row.write(next)) {
                    values[selected] = next
                    // A preset carries its own backlight/contrast/etc., so re-read the sliders.
                    if (selected == 0) refreshValues()
                }
            }
            is Row.Action -> return
        }
        render()
    }

    private fun refreshValues() {
        rows.forEachIndexed { i, row ->
            values[i] = when (row) {
                is Row.Slider -> control.get(row.setting)
                is Row.Choice -> row.read()
                is Row.Action -> 0
            }
        }
    }

    // ---- views ----------------------------------------------------------------------------------

    private class RowViews(val box: LinearLayout, val label: TextView, val value: TextView, val track: View?)

    private fun build(): FrameLayout {
        val frame = FrameLayout(context)
        // A narrow full-height card on the right edge: the home screen's current painting, sunk under
        // an espresso scrim with two quiet MCM shapes, and the rows on top.
        val card = FrameLayout(context).apply {
            background = rounded(R.color.menu_bg, 28)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dp(24).toFloat()
        }
        val art = android.widget.ImageView(context).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        card.addView(art, FrameLayout.LayoutParams(-1, -1))
        card.addView(View(context).apply { background = PanelArt() }, FrameLayout.LayoutParams(-1, -1))
        loadPainting(art)

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
        }
        side.addView(TextView(context).apply {
            text = "THE LIVING ROOM"; textSize = 11f; letterSpacing = 0.2f
            setTextColor(color(R.color.butter))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, 0)
        })
        side.addView(TextView(context).apply {
            text = "Picture"; textSize = 28f
            setTextColor(color(R.color.ivory))
            typeface = Typeface.create("serif", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, 0x99000000.toInt())
            setPadding(dp(12), 0, 0, dp(10))
        })
        rows.forEachIndexed { i, row ->
            // A hairline between the picture controls and the two ways out.
            if (row is Row.Action && rows[i - 1] !is Row.Action) side.addView(View(context).apply {
                setBackgroundColor(color(R.color.panel_stroke))
            }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(dp(12), dp(6), dp(12), dp(6)) })
            side.addView(buildRow(row))
        }
        side.addView(TextView(context).apply {
            text = "▲▼ choose   ◀▶ adjust   Back close"
            textSize = 11f
            setTextColor(color(R.color.ivory_text_dim))
            setPadding(dp(12), dp(10), 0, 0)
        })
        card.addView(side, FrameLayout.LayoutParams(-1, -1))
        frame.addView(card, FrameLayout.LayoutParams(dp(PANEL_WIDTH_DP), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            setMargins(0, dp(20), dp(28), dp(20))
        })
        panel = card
        return frame
    }

    /** The painting the home screen is showing, decoded small off the main thread and faded in. */
    private fun loadPainting(target: android.widget.ImageView) {
        val painting = com.example.tvlauncher.data.ArtLibrary(context).let { it.paintings[it.index] }
        val cached = paintingCache
        if (cached != null && cached.first == painting.resource) {
            target.setImageBitmap(cached.second); target.alpha = 1f; return
        }
        Thread {
            val bitmap = try {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeResource(context.resources, painting.resource, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= dp(PANEL_WIDTH_DP) * 2) sample *= 2
                android.graphics.BitmapFactory.decodeResource(context.resources, painting.resource,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) { null } ?: return@Thread
            handler.post {
                paintingCache = painting.resource to bitmap
                target.setImageBitmap(bitmap)
                target.animate().alpha(1f).setDuration(400).start()
            }
        }.start()
    }

    /**
     * The scrim and motifs over the painting: espresso deepening towards the bottom so the rows stay
     * legible, a low butter sun half off the top corner, and a teal quarter-circle rising from the
     * bottom-left -- the same shapes as the MCM app icons, kept faint.
     */
    private inner class PanelArt : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: android.graphics.Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, h,
                intArrayOf(0xC7302319.toInt(), 0xE0302319.toInt(), 0xF5302319.toInt()), floatArrayOf(0f, 0.35f, 1f),
                android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            paint.color = withAlpha(color(R.color.butter), 0x30)
            canvas.drawCircle(w * 0.86f, h * 0.02f, w * 0.34f, paint)
            paint.color = withAlpha(color(R.color.terracotta), 0x38)
            canvas.drawCircle(w * 0.86f, h * 0.02f, w * 0.17f, paint)
            paint.color = withAlpha(color(R.color.deep_teal), 0x2E)
            canvas.drawCircle(0f, h, w * 0.55f, paint)
        }

        private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun buildRow(row: Row): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }
        val label = TextView(context).apply {
            text = row.label; textSize = 15f; maxLines = 1
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val value = TextView(context).apply { textSize = 15f; gravity = Gravity.END; maxLines = 1 }
        box.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        var track: View? = null
        if (row is Row.Slider) {
            track = View(context)
            box.addView(track, LinearLayout.LayoutParams(dp(96), dp(4)).apply { marginEnd = dp(10) })
            box.addView(value, LinearLayout.LayoutParams(dp(30), -2))
        } else {
            box.addView(value, LinearLayout.LayoutParams(-2, -2))
        }
        box.layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) }
        rowViews.add(RowViews(box, label, value, track))
        return box
    }

    private fun render() {
        rowViews.forEachIndexed { i, v ->
            val lit = i == selected
            val row = rows[i]
            v.box.background = if (lit) rounded(R.color.ivory, 14) else null
            val ink = color(if (lit) R.color.ink else R.color.ivory)
            v.label.setTextColor(ink)
            v.value.setTextColor(if (lit) ink else color(R.color.ivory_text_dim))
            v.value.text = when (row) {
                is Row.Slider -> if (values[i] < 0) "–" else values[i].toString()
                is Row.Choice -> {
                    val name = row.options.firstOrNull { it.first == values[i] }?.second ?: if (values[i] < 0) "–" else "Custom"
                    if (lit) "◀ $name ▶" else name
                }
                is Row.Action -> if (lit) "OK  ›" else "›"
            }
            v.track?.background = track(values[i].coerceAtLeast(0), lit)
        }
    }

    private fun track(value: Int, lit: Boolean): LayerDrawable {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            setColor(if (lit) 0x3326271D else color(R.color.panel_stroke))
        }
        val fill = ClipDrawable(GradientDrawable().apply {
            cornerRadius = dp(3).toFloat()
            setColor(color(if (lit) R.color.terracotta else R.color.butter))
        }, Gravity.START, ClipDrawable.HORIZONTAL).apply { level = value * 100 }
        return LayerDrawable(arrayOf(bg, fill))
    }

    private fun rounded(colorRes: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat(); setColor(color(colorRes))
    }

    private fun color(res: Int) = context.getColor(res)
    private fun dp(v: Int) = (v * d).toInt()

    private var paintingCache: Pair<Int, android.graphics.Bitmap>? = null

    private companion object {
        const val IDLE_HIDE_MS = 12_000L
        const val PANEL_WIDTH_DP = 340
    }
}
