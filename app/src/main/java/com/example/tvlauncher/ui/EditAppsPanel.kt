package com.example.tvlauncher.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.tvlauncher.R
import com.example.tvlauncher.data.AppEntry
import com.example.tvlauncher.data.FavoritesController
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.Type

/**
 * The guaranteed normal-select alternative to long-press shelf reorder:
 * a plain list, every action a short OK press, in three groups -- Your apps
 * (always the first seven), More apps, and Hidden. Up/Down move an app; moving
 * one across the Your apps line swaps it with its neighbour on the other side,
 * so Your apps never grows past seven. Hide/Show takes an app off the home
 * screen entirely. No long-press required anywhere on this screen.
 */
class EditAppsPanel(
    private val overlay: View,
    private val list: LinearLayout,
    private val favorites: FavoritesController,
    private val onChanged: () -> Unit
) {
    private var allApps: List<AppEntry> = emptyList()
    private var selectedPackage: String? = null

    // Closing on Back is centralised in MainActivity's OnBackPressedCallback.

    val isVisible: Boolean get() = overlay.visibility == View.VISIBLE

    fun show(apps: List<AppEntry>) {
        allApps = apps
        overlay.visibility = View.VISIBLE
        com.example.tvlauncher.design.Motion.enter(overlay, null, 0f)
        render()
    }

    fun hide() {
        overlay.visibility = View.GONE
    }

    private fun render() {
        val context = list.context
        list.removeAllViews()

        val visible = favorites.currentShelfOrder(allApps)
        val hidden = favorites.excludedApps(allApps)
        val shelf = visible.take(com.example.tvlauncher.data.PRIMARY_SHELF_LIMIT)
        val more = visible.drop(com.example.tvlauncher.data.PRIMARY_SHELF_LIMIT)

        var firstFocusable: View? = null
        fun group(title: String, entries: List<AppEntry>, isHidden: Boolean) {
            if (entries.isEmpty()) return
            list.addView(Type.text(context, title, Type.Style.EYEBROW), LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = if (list.childCount == 0) 0 else px(R.dimen.space_5)
                bottomMargin = px(R.dimen.space_1)
                marginStart = px(R.dimen.space_3)
            })
            entries.forEach { entry ->
                val index = visible.indexOfFirst { it.packageName == entry.packageName }
                val row = row(context, entry, index, isHidden)
                if (firstFocusable == null || selectedPackage == entry.packageName) firstFocusable = row.getChildAt(row.childCount - 1)
                list.addView(row)
            }
        }
        group(context.getString(R.string.your_apps), shelf, false)
        group(context.getString(R.string.top_picks), more, false)
        group(context.getString(R.string.hidden_apps), hidden, true)

        firstFocusable?.let { requestFocusRobust(list, it) }
    }

    /** One list row: icon, name, then Up/Down (visible apps) and Hide/Show. Moving across the Your apps line swaps two apps. */
    private fun row(context: android.content.Context, entry: AppEntry, index: Int, isHidden: Boolean): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(R.dimen.row_height)).apply { topMargin = px(R.dimen.space_2) }
            setPadding(px(R.dimen.space_3), 0, px(R.dimen.space_2), 0)
            background = LauncherTheme.surface(context, R.color.surface_raised, R.dimen.radius_pill)
            if (isHidden) alpha = 0.72f
        }
        row.addView(ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(px(R.dimen.space_6), px(R.dimen.space_6))
            setImageDrawable(com.example.tvlauncher.design.RoundIcon.own(context, entry.icon))
            contentDescription = null
        })
        row.addView(Type.text(context, entry.label, Type.Style.BODY).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(R.dimen.space_3) }
        })
        if (!isHidden) {
            row.addView(control(context, R.drawable.ic_chevron_up, "Move ${entry.label} up") { move(entry, index, -1) })
            row.addView(control(context, R.drawable.ic_chevron_down, "Move ${entry.label} down") { move(entry, index, 1) }
                .apply { (layoutParams as LinearLayout.LayoutParams).marginStart = px(R.dimen.space_1) })
        }
        val toggle = Type.text(context, if (isHidden) "Show" else "Hide", Type.Style.BODY)
        toggle.apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            contentDescription = if (isHidden) "Show ${entry.label} on the home screen" else "Hide ${entry.label} from the home screen"
            setOnClickListener {
                selectedPackage = entry.packageName
                favorites.setIncluded(allApps, entry.packageName, isHidden)
                onChanged()
                render()
            }
            layoutParams = LinearLayout.LayoutParams(px(R.dimen.space_8) + px(R.dimen.space_3), px(R.dimen.space_6)).apply { marginStart = px(R.dimen.space_3) }
        }
        LauncherTheme.bindPanelFocus(
            toggle,
            LauncherTheme.surface(context, R.color.surface_selected, R.dimen.radius_pill),
            LauncherTheme.surface(context, R.color.focus, R.dimen.radius_pill)
        ) { lit -> scrollFocusIntoView(toggle, lit) }
        row.addView(toggle)
        return row
    }

    private fun move(entry: AppEntry, index: Int, delta: Int) {
        selectedPackage = entry.packageName
        favorites.move(allApps, index, delta)
        onChanged()
        render()
    }

    /** A round icon button (Up / Down): the glyph on nothing at rest, ink on a lit ivory disc when focused. */
    private fun control(context: android.content.Context, icon: Int, description: String, onClick: () -> Unit): ImageView =
        ImageView(context).apply {
            val size = px(R.dimen.space_6)
            layoutParams = LinearLayout.LayoutParams(size, size)
            val pad = px(R.dimen.space_2)
            setPadding(pad, pad, pad, pad)
            setImageResource(icon)
            contentDescription = description
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            val rest = ColorDrawable(Color.TRANSPARENT)
            val focused = LauncherTheme.circleBackgroundFocused(context)
            background = rest
            imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text))
            setOnFocusChangeListener { v, hasFocus ->
                scrollFocusIntoView(v, hasFocus)
                v.background = if (hasFocus) focused else rest
                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, if (hasFocus) R.color.on_focus else R.color.text))
                LauncherTheme.animateFocus(v, hasFocus)
            }
            setOnClickListener { onClick() }
        }

    private fun px(dimen: Int): Int = list.resources.getDimensionPixelSize(dimen)
}
