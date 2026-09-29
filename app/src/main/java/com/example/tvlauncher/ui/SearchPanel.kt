package com.example.tvlauncher.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tvlauncher.R
import com.example.tvlauncher.data.AppEntry

private const val COLUMNS = 7

/**
 * Local, installed-app-only search. The EditText's own D-pad-OK focus
 * brings up the system on-screen keyboard automatically -- no custom
 * keyboard is built here. Filters as the owner types; never labelled as a
 * universal streaming-content search, only what it actually is.
 */
class SearchPanel(
    private val overlay: View,
    private val input: EditText,
    private val resultsGrid: LinearLayout,
    private val noResults: TextView,
    private val shelfBuilder: ShelfLayoutBuilder,
    private val onLaunch: (AppEntry) -> Unit
) {
    private var allApps: List<AppEntry> = emptyList()

    // Closing on Back is centralised in MainActivity's OnBackPressedCallback.

    init {
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render(s?.toString().orEmpty())
        })
    }

    val isVisible: Boolean get() = overlay.visibility == View.VISIBLE

    fun show(apps: List<AppEntry>) {
        allApps = apps
        overlay.visibility = View.VISIBLE
        com.example.tvlauncher.design.Motion.enter(overlay, null, 0f)
        input.setText("")
        requestFocusRobust(overlay as android.view.ViewGroup, input)
        render("")
        val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        overlay.visibility = View.GONE
        val imm = input.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun render(query: String) {
        val matches = if (query.isBlank()) allApps else allApps.filter { it.label.contains(query, ignoreCase = true) }
        noResults.visibility = if (matches.isEmpty() && query.isNotBlank()) View.VISIBLE else View.GONE
        if (matches.isEmpty() && query.isNotBlank()) {
            noResults.text = noResults.context.getString(R.string.search_no_results, query)
        }
        val tiles = matches.map { entry -> ShelfTile.forApp(entry, onLaunch) }
        shelfBuilder.buildGrid(resultsGrid, tiles, COLUMNS)
    }
}
