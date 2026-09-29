package com.example.tvlauncher.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
 * The quick account dropdown under the header avatar: the TV's Google accounts
 * (current one ticked), plus shortcuts to the system profile chooser and account
 * settings -- the same structure as the stock Google TV menu.
 */
class AccountMenu(
    private val container: FrameLayout,
    private val accounts: AccountsRepository,
    private val onPick: (TvAccount) -> Unit,
    private val onSwitchProfile: () -> Unit,
    private val onManage: () -> Unit,
    private val onClosed: () -> Unit
) {
    private val context = container.context
    val isVisible: Boolean get() = container.visibility == View.VISIBLE

    fun toggle() { if (isVisible) hide() else show() }

    fun show() {
        render()
        container.visibility = View.VISIBLE
        com.example.tvlauncher.design.Motion.enter(container, container.getChildAt(0), -dp(14).toFloat())
        val menu = container.getChildAt(0) as? LinearLayout ?: return
        val first = (0 until menu.childCount).map { menu.getChildAt(it) }.firstOrNull { it.isFocusable } ?: return
        requestFocusRobust(menu, first)
    }

    fun hide() {
        if (!isVisible) return
        container.visibility = View.GONE
        onClosed()
    }

    private fun render() {
        container.removeAllViews()
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = context.resources.getDimensionPixelSize(R.dimen.space_2)
            setPadding(pad, pad, pad, pad)
            background = LauncherTheme.surface(context, R.color.surface_solid)
            elevation = dp(16).toFloat()
        }
        container.addView(menu, FrameLayout.LayoutParams(dp(360), FrameLayout.LayoutParams.WRAP_CONTENT))

        val current = accounts.current()
        val list = accounts.accounts()
        if (list.isEmpty()) {
            menu.addView(row(Avatar.unknown(context), context.getString(R.string.accounts_none), null, false) { onManage() })
        }
        list.forEach { account ->
            menu.addView(row(Avatar.of(context, account), account.label, null, account.email == current?.email) {
                onPick(account)
            })
        }
        menu.addView(divider())
        menu.addView(row(null, context.getString(R.string.accounts_switch), null, false, quiet = true) { onSwitchProfile() })
        menu.addView(row(null, context.getString(R.string.accounts_manage), null, false, quiet = true) { onManage() })
    }

    private fun row(avatar: Drawable?, title: String, subtitle: String?, checked: Boolean, quiet: Boolean = false, action: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(16), dp(8))
            isFocusable = true
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(if (subtitle != null) 64 else 56)).apply { topMargin = dp(if (quiet) 6 else 2) }
        }
        if (avatar != null) {
            row.addView(ImageView(context).apply { setImageDrawable(avatar) }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        }
        val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        text.addView(com.example.tvlauncher.design.Type.text(context, title, com.example.tvlauncher.design.Type.Style.BODY).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        if (subtitle != null && subtitle != title) {
            text.addView(com.example.tvlauncher.design.Type.text(context, subtitle, com.example.tvlauncher.design.Type.Style.CAPTION).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        var check: ImageView? = null
        if (checked) {
            check = ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text))
            }
            row.addView(check, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        // One list-row style for accounts and actions alike: nothing at rest, lit ivory on focus.
        LauncherTheme.bindPanelFocus(row, ColorDrawable(Color.TRANSPARENT), LauncherTheme.surface(context, R.color.focus, R.dimen.radius_pill), 1f) { lit ->
            check?.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, if (lit) R.color.on_focus else R.color.text))
        }
        row.setOnClickListener { hide(); action() }
        return row
    }

    private fun divider() = View(context).apply {
        setBackgroundColor(ContextCompat.getColor(context, R.color.hairline))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(14), dp(6), dp(14), dp(6)) }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
