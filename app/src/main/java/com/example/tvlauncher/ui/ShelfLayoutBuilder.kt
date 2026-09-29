package com.example.tvlauncher.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvlauncher.R
import com.example.tvlauncher.data.AppEntry
import com.example.tvlauncher.design.LauncherTheme

/**
 * Builds tiles for the "Your apps" shelf, the "All apps" grid and search --
 * one round-icon treatment everywhere, sized either full (shelf) or small (grid).
 */
class ShelfLayoutBuilder(private val context: Context) {

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    /**
     * Lays out [tiles] as a single horizontal row of fixed-size tiles
     * (never stretched to fill the row, so a household with only 2-3 apps
     * still gets the intended tile size and composition). [container]'s
     * parent must be a HorizontalScrollView to allow scrolling past the
     * ~5 visible slots.
     */
    fun buildRow(container: LinearLayout, tiles: List<ShelfTile>, focusIndex: Int = 0) {
        container.removeAllViews()
        val gap = context.resources.getDimensionPixelSize(R.dimen.tile_gap)
        val width = context.resources.getDimensionPixelSize(R.dimen.tile_width)
        val height = context.resources.getDimensionPixelSize(R.dimen.tile_height)

        tiles.forEachIndexed { index, tile ->
            val view = buildFavoriteModule(tile)
            view.layoutParams = LinearLayout.LayoutParams(width, height).apply {
                if (index > 0) marginStart = gap
            }
            container.addView(view)
        }

        if (tiles.isNotEmpty()) {
            val safeIndex = focusIndex.coerceIn(0, tiles.size - 1)
            container.getChildAt(safeIndex)?.let { requestFocusRobust(container, it) }
        }
    }

    /** Builds the "More apps" rail: dark glass panels that light up ivory on focus. */
    fun buildFeatureRow(container: LinearLayout, tiles: List<ShelfTile>) {
        container.removeAllViews()
        val gap = context.resources.getDimensionPixelSize(R.dimen.card_gutter)
        val width = context.resources.getDimensionPixelSize(R.dimen.featured_tile_width)
        val height = context.resources.getDimensionPixelSize(R.dimen.featured_tile_height)
        val radius = context.resources.getDimensionPixelSize(R.dimen.radius_card).toFloat()
        tiles.forEachIndexed { index, tile ->
            val view = LayoutInflater.from(context).inflate(R.layout.item_featured, container, false) as FrameLayout
            view.findViewById<ImageView>(R.id.featureIcon).setImageDrawable(com.example.tvlauncher.design.RoundIcon.own(context, tile.artwork))
            view.findViewById<TextView>(R.id.featureLabel).text = tile.label
            view.contentDescription = tile.label

            LauncherTheme.bindPanelFocus(
                view,
                LauncherTheme.surface(context, R.color.surface),
                LauncherTheme.surface(context, R.color.focus)
            ) { hasFocus ->
                scrollFocusIntoView(view, hasFocus)
                tile.onFocus?.invoke(hasFocus)
            }
            view.setOnClickListener { com.example.tvlauncher.design.Motion.press(view, 1.05f); tile.onSelect() }
            tile.onLongSelect?.let { longSelect -> view.setOnLongClickListener { longSelect() } }
            view.layoutParams = LinearLayout.LayoutParams(width, height).apply {
                if (index > 0) marginStart = gap
            }
            container.addView(view)
        }
    }

    /**
     * Lays out [tiles] as a uniform, fixed-size grid of round-icon items
     * (used by the All apps and Search overlays). Fixed width, not
     * weight-based, so a short final row doesn't stretch across the row.
     */
    fun buildGrid(container: LinearLayout, tiles: List<ShelfTile>, columns: Int, focusFirst: Boolean = false) {
        container.removeAllViews()
        // Tiles carry their own spacing (all_apps_tile_width includes it), so columns sit edge to edge on the margin.
        val gap = 0
        val rowGap = context.resources.getDimensionPixelSize(R.dimen.space_2)
        val tileWidth = context.resources.getDimensionPixelSize(R.dimen.all_apps_tile_width)
        val tileHeight = context.resources.getDimensionPixelSize(R.dimen.all_apps_tile_height)

        val available = context.resources.displayMetrics.widthPixels - 2 * context.resources.getDimensionPixelSize(R.dimen.shelf_side_margin)
        val fittingColumns = ((available + gap) / (tileWidth + gap)).coerceIn(1, columns)
        tiles.chunked(fittingColumns).forEachIndexed { rowIndex, rowTiles ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                clipToPadding = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    tileHeight
                ).apply { if (rowIndex > 0) topMargin = rowGap }
            }
            rowTiles.forEachIndexed { colIndex, tile ->
                val item = buildRoundItem(
                    tile,
                    iconSize = context.resources.getDimensionPixelSize(R.dimen.tile_icon_size_small),
                    labelSizePx = context.resources.getDimension(R.dimen.tile_label_text_size_small),
                    labelWidth = tileWidth
                )
                item.layoutParams = LinearLayout.LayoutParams(tileWidth, LinearLayout.LayoutParams.MATCH_PARENT)
                    .apply { if (colIndex > 0) marginStart = gap }
                row.addView(item)
            }
            container.addView(row)
        }

        if (focusFirst && tiles.isNotEmpty()) {
            container.post { (container.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus() }
        }
    }

    /** Marks a shelf item as picked up for reorder (butter ring that persists while focused). */
    fun markPicked(view: View) {
        view.tag = PICKED
        view.findViewById<TextView>(R.id.tileLabel)?.apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(context.getColor(R.color.butter)) }
            setTextColor(context.getColor(R.color.ink))
        }
        // Lifted off the shelf: the held app is larger and floats above its neighbours.
        view.elevation = dp(16f).toFloat()
        view.animate().scaleX(1.14f).scaleY(1.14f).translationY(-dp(8f).toFloat()).setDuration(200).setInterpolator(com.example.tvlauncher.design.Motion.SETTLE).start()
    }

    private fun buildFavoriteModule(tile: ShelfTile): View = buildRoundItem(
        tile,
        iconSize = context.resources.getDimensionPixelSize(R.dimen.tile_icon_size),
        labelSizePx = context.resources.getDimension(R.dimen.tile_label_text_size)
    )

    /** One round icon inside a focus ring, label beneath. Focus = ivory ring + warm label + a slight lift. */
    private fun buildRoundItem(tile: ShelfTile, iconSize: Int, labelSizePx: Float, labelWidth: Int = context.resources.getDimensionPixelSize(R.dimen.tile_width)): View {
        val view = LayoutInflater.from(context).inflate(R.layout.item_round, null) as FrameLayout
        val ring = view.findViewById<FrameLayout>(R.id.tileRing)
        val icon = view.findViewById<ImageView>(R.id.tileIcon)
        val label = view.findViewById<TextView>(R.id.tileLabel)

        icon.layoutParams = (icon.layoutParams as FrameLayout.LayoutParams).apply { width = iconSize; height = iconSize }
        // Room for the focus halo around the disc.
        val pad = context.resources.getDimensionPixelSize(R.dimen.focus_halo)
        ring.setPadding(pad, pad, pad, pad)
        val mat = LauncherTheme.iconMat(context).apply { alpha = 0 }
        ring.background = mat
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, labelSizePx)
        icon.setImageDrawable(com.example.tvlauncher.design.RoundIcon.own(context, tile.artwork))
        label.text = tile.label
        view.contentDescription = tile.label

        // The one focus rule for an image object: the disc gets its ivory mat and lifts, and its name lights as an ivory pill.
        val restPill = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(0) }
        val litPill = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(context.getColor(R.color.focus)) }
        val pill = android.graphics.drawable.TransitionDrawable(arrayOf(restPill, litPill)).apply { isCrossFadeEnabled = true }
        label.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2f) }
        // Names use the whole slot and the lighter weight, so "Paramount+" or "PrivadoVPN" fit without an ellipsis.
        label.maxWidth = labelWidth
        label.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        label.setPadding(dp(8f), dp(2f), dp(8f), dp(3f))
        label.background = pill
        view.setOnFocusChangeListener { v, hasFocus ->
            scrollFocusIntoView(v, hasFocus)
            if (v.tag == PICKED) {
                label.background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(context.getColor(R.color.butter)) }
                label.setTextColor(context.getColor(R.color.ink))
            } else {
                if (hasFocus) pill.startTransition(com.example.tvlauncher.design.Motion.FOCUS_IN_MS.toInt()) else pill.reverseTransition(com.example.tvlauncher.design.Motion.FOCUS_OUT_MS.toInt())
                // The shadow only helps ivory text on the painting; ink on the lit pill stays crisp.
                if (hasFocus) label.setShadowLayer(0f, 0f, 0f, 0) else label.setShadowLayer(4f, 0f, 1f, 0xB3000000.toInt())
                com.example.tvlauncher.design.Motion.tweenTextColor(label, context.getColor(if (hasFocus) R.color.on_focus else R.color.text))
            }
            LauncherTheme.fadeDrawable(ring, mat, hasFocus || v.tag == PICKED)
            LauncherTheme.animateFocus(ring, hasFocus, 1.08f)
            if (v.tag != PICKED) com.example.tvlauncher.design.Motion.liftIcon(ring, hasFocus)
            tile.onFocus?.invoke(hasFocus)
        }
        view.setOnClickListener { com.example.tvlauncher.design.Motion.press(ring, 1.08f); tile.onSelect() }
        tile.onLongSelect?.let { longSelect -> view.setOnLongClickListener { longSelect() } }
        return view
    }

    private companion object {
        const val PICKED = "picked"
    }
}

/** One tile's content and behaviour, independent of how/where it's laid out. */
class ShelfTile(
    val label: String,
    val artwork: Drawable,
    /** Null for synthetic tiles ("All apps") that don't correspond to a real app. */
    val packageName: String? = null,
    val onSelect: () -> Unit,
    /** Long-press OK -- used for shelf reorder pickup / grid context actions. */
    val onLongSelect: (() -> Boolean)? = null,
    /** Notified on focus gain/loss -- drives the context-sensitive footer hints. */
    val onFocus: ((Boolean) -> Unit)? = null
) {
    companion object {
        fun forApp(
            entry: AppEntry,
            onLaunch: (AppEntry) -> Unit,
            onLongSelect: (() -> Boolean)? = null,
            onFocus: ((Boolean) -> Unit)? = null
        ): ShelfTile =
            ShelfTile(
                label = entry.label,
                artwork = entry.icon,
                packageName = entry.packageName,
                onSelect = { onLaunch(entry) },
                onLongSelect = onLongSelect,
                onFocus = onFocus
            )
    }
}
