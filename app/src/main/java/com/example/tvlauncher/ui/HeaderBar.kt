package com.example.tvlauncher.ui

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.tvlauncher.R
import com.example.tvlauncher.design.LauncherTheme

/** HOME is the top of the page; DANIEL, AMELIA and PADRAIG are places further down it. */
enum class NavTab { HOME, DANIEL, AMELIA, PADRAIG, APPS, ART }

/**
 * Wires the header's four nav labels and five right-hand icons. Focus is the
 * shared "lit ivory" treatment. The current page is shown as a soft
 * translucent pill behind its label -- no underline -- so the persistent
 * "you are here" state and the transient focus state stay visually distinct.
 */
class HeaderBar(
    navTabs: Map<NavTab, TextView>,
    iconViews: List<View>,
    private val onSelectTab: (NavTab) -> Unit
) {
    private val tabs = navTabs
    private var current = NavTab.HOME

    init {
        tabs.forEach { (tab, view) ->
            bindFocus(view, LauncherTheme.tileBackgroundFocused(view.context, PILL_RADIUS)) { restBackground(tab, view) }
            view.setOnClickListener { onSelectTab(tab) }
        }
        iconViews.forEach { view ->
            bindFocus(view, LauncherTheme.circleBackgroundFocused(view.context)) { null }
        }
        setSelected(NavTab.HOME)
    }

    fun setSelected(tab: NavTab) {
        current = tab
        tabs.forEach { (t, view) ->
            val selected = t == tab
            view.isSelected = selected
            view.setTypeface(Typeface.create("sans-serif-medium", if (selected) Typeface.BOLD else Typeface.NORMAL))
            view.background = if (view.hasFocus()) LauncherTheme.tileBackgroundFocused(view.context, PILL_RADIUS) else restBackground(t, view)
            view.setTextColor(textColor(view, view.hasFocus()))
        }
    }

    private fun restBackground(tab: NavTab, view: View): Drawable? =
        if (tab == current) LauncherTheme.selectedPill(view.context, PILL_RADIUS) else null

    private fun textColor(view: View, focused: Boolean): Int = ContextCompat.getColor(
        view.context,
        when {
            focused -> R.color.ink
            view.isSelected -> R.color.ivory
            else -> R.color.ivory_text_dim
        }
    )

    private fun bindFocus(view: View, focused: Drawable, rest: () -> Drawable?) {
        view.background = rest()
        view.setOnFocusChangeListener { v, hasFocus ->
            scrollFocusIntoView(v, hasFocus)
            v.background = if (hasFocus) focused else rest()
            if (v is TextView) v.setTextColor(textColor(v, hasFocus))
            if (v is ImageView) {
                val color = ContextCompat.getColor(v.context, if (hasFocus) R.color.ink else R.color.ivory)
                v.imageTintList = ColorStateList.valueOf(color)
            }
            LauncherTheme.animateFocus(v, hasFocus)
        }
    }

    private companion object {
        const val PILL_RADIUS = 200f
    }
}
