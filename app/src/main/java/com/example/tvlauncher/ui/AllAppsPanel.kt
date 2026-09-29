package com.example.tvlauncher.ui

import android.view.View
import android.widget.LinearLayout
import com.example.tvlauncher.R
import com.example.tvlauncher.data.AppEntry
import com.example.tvlauncher.design.RoundIcon

private const val COLUMNS = 7

/**
 * The full app directory: every installed, launchable app (not just shelf
 * overflow), scrollable, deliberately plainer than the curated shelf.
 * "Edit your apps" is a real button beside the title -- the normal-select-only
 * route into [EditAppsPanel], independent of the shelf's long-press reorder.
 * Long-press on any real app tile opens its system App info page (the
 * "app info/settings access" the brief calls for).
 */
class AllAppsPanel(
    private val overlay: View,
    private val grid: LinearLayout,
    private val shelfBuilder: ShelfLayoutBuilder,
    private val onLaunch: (AppEntry) -> Unit,
    private val onOpenEdit: () -> Unit,
    private val onAppInfo: (AppEntry) -> Boolean,
    editButton: View
) {
    init {
        val context = editButton.context
        com.example.tvlauncher.design.LauncherTheme.bindPanelFocus(
            editButton,
            com.example.tvlauncher.design.LauncherTheme.surface(context, R.color.surface_raised, R.dimen.radius_pill),
            com.example.tvlauncher.design.LauncherTheme.surface(context, R.color.focus, R.dimen.radius_pill)
        ) { lit -> (editButton as? android.widget.TextView)?.compoundDrawablesRelative?.firstOrNull()?.setTint(context.getColor(if (lit) R.color.on_focus else R.color.text)) }
        editButton.setOnClickListener { onOpenEdit() }
    }


    // Closing on Back is centralised in MainActivity's OnBackPressedCallback,
    // which also restores header/shelf focus -- not handled here.

    val isVisible: Boolean get() = overlay.visibility == View.VISIBLE

    fun setApps(allApps: List<AppEntry>) {
        val appTiles = allApps.map { entry ->
            ShelfTile.forApp(entry, onLaunch, onLongSelect = { onAppInfo(entry) })
        }
        shelfBuilder.buildGrid(grid, appTiles, COLUMNS)
    }

    fun show() {
        overlay.visibility = View.VISIBLE
        com.example.tvlauncher.design.Motion.enter(overlay, null, 0f)
        (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.let { requestFocusRobust(grid, it) }
    }

    fun hide() {
        overlay.visibility = View.GONE
    }
}
