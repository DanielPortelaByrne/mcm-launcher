package com.example.tvlauncher.ui

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.tvlauncher.R
import com.example.tvlauncher.design.Avatar
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.system.AccountsRepository
import com.example.tvlauncher.system.TvAccount

/**
 * The full-screen "Who's watching?" picker shown when the TV is switched on and from the
 * avatar dropdown's "Switch profile". Google TV's own chooser lives in the stock home,
 * which is disabled while this launcher is the default, so the launcher provides it.
 */
class WhoPicker(
    private val container: FrameLayout,
    private val accounts: AccountsRepository,
    private val onChosen: (TvAccount) -> Unit,
    private val onClosed: () -> Unit
) {
    private val context = container.context
    val isVisible: Boolean get() = container.visibility == View.VISIBLE

    /** Returns false (and shows nothing) when no accounts are known yet. */
    fun show(): Boolean {
        val list = accounts.accounts()
        if (list.isEmpty()) return false
        container.removeAllViews()
        container.clipChildren = false
        container.setBackgroundColor(ContextCompat.getColor(context, R.color.overlay_bg))

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            clipChildren = false
            clipToPadding = false
        }
        column.addView(com.example.tvlauncher.design.Type.text(context, context.getString(R.string.whos_watching), com.example.tvlauncher.design.Type.Style.DISPLAY).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = context.resources.getDimensionPixelSize(R.dimen.space_6) })

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false; clipToPadding = false }
        val current = accounts.current()
        var focusTarget: View? = null
        list.forEach { account ->
            val item = item(account)
            row.addView(item, LinearLayout.LayoutParams(dp(190), -2).apply { marginStart = dp(8); marginEnd = dp(8) })
            if (account.email == current?.email || focusTarget == null) focusTarget = item
        }
        column.addView(row)
        container.addView(column, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        container.visibility = View.VISIBLE
        com.example.tvlauncher.design.Motion.enter(container, column, dp(24).toFloat())
        focusTarget?.let { requestFocusRobust(container, it) }
        return true
    }

    fun hide() {
        if (!isVisible) return
        container.visibility = View.GONE
        container.removeAllViews()
        onClosed()
    }

    private fun item(account: TvAccount): View {
        val size = dp(120)
        val pad = context.resources.getDimensionPixelSize(R.dimen.focus_halo)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            setPadding(0, dp(18), 0, dp(10))
        }
        val ring = FrameLayout(context).apply { setPadding(pad, pad, pad, pad); clipChildren = false; clipToPadding = false }
        ring.addView(ImageView(context).apply { setImageDrawable(Avatar.of(context, account)) }, FrameLayout.LayoutParams(size, size))
        root.addView(ring, LinearLayout.LayoutParams(-2, -2))
        val name = com.example.tvlauncher.design.Type.text(context, account.label, com.example.tvlauncher.design.Type.Style.BODY).apply { gravity = Gravity.CENTER }
        root.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.resources.getDimensionPixelSize(R.dimen.space_3) })

        // The same soft halo as a round app icon.
        val mat = LauncherTheme.iconMat(context).apply { alpha = 0 }
        ring.background = mat
        root.setOnFocusChangeListener { _, hasFocus ->
            LauncherTheme.fadeDrawable(ring, mat, hasFocus)
            LauncherTheme.animateFocus(ring, hasFocus, 1.08f)
        }
        root.setOnClickListener { onChosen(account); hide() }
        return root
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
