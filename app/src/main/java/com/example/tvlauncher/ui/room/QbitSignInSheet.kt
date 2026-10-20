package com.example.tvlauncher.ui.room

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.tvlauncher.R
import com.example.tvlauncher.data.movies.QueueItem
import com.example.tvlauncher.data.movies.QueueClient
import kotlin.concurrent.thread

/** One-time qBittorrent sign-in. Only the returned session is stored, never the password. */
class QbitSignInSheet(private val activity: android.app.Activity, private val sheet: InfoSheet) {
    private fun px(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun show(client: QueueClient, item: QueueItem, onSignedIn: () -> Unit) {
        val ivory = ContextCompat.getColor(activity, R.color.ivory)
        val dim = ContextCompat.getColor(activity, R.color.ivory_text_dim)
        val ink = ContextCompat.getColor(activity, R.color.ink)

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(px(34), px(28), px(34), px(26))
            background = GradientDrawable().apply { cornerRadius = px(30).toFloat(); setColor(ContextCompat.getColor(activity, R.color.menu_bg)) }
        }
        fun label(t: String, sp: Float, c: Int, serif: Boolean = false) = TextView(activity).apply {
            text = t; textSize = sp; setTextColor(c); typeface = Typeface.create(if (serif) "serif" else "sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(label("DOWNLOAD CONTROLS", 11f, ContextCompat.getColor(activity, R.color.butter)).apply { letterSpacing = 0.16f })
        card.addView(label("Sign in to qBittorrent", 26f, ivory, serif = true).apply { setPadding(0, px(4), 0, 0) })
        card.addView(label("Use your qBittorrent WebUI account. Your password is used only to sign in and is never stored.", 14f, dim).apply { setPadding(0, px(8), 0, px(16)) })

        fun field(hint: String, password: Boolean) = EditText(activity).apply {
            this.hint = hint; com.example.tvlauncher.design.Type.apply(this, com.example.tvlauncher.design.Type.Style.BODY); setHintTextColor(dim)
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            maxLines = 1; isSingleLine = true
            imeOptions = if (password) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
            setPadding(px(22), 0, px(22), 0)
            background = GradientDrawable().apply { cornerRadius = px(26).toFloat(); setColor(ContextCompat.getColor(activity, R.color.btn_brown_on)) }
            setOnFocusChangeListener { v, focused -> (v.background as GradientDrawable).setColor(if (focused) 0xFF7A5C42.toInt() else ContextCompat.getColor(activity, R.color.btn_brown_on)) }
        }
        val user = field("qBittorrent username", false)
        val password = field("Password", true)
        card.addView(user, LinearLayout.LayoutParams(-1, px(52)))
        card.addView(password, LinearLayout.LayoutParams(-1, px(52)).apply { topMargin = px(10) })

        val status = label("", 13f, ContextCompat.getColor(activity, R.color.butter)).apply { setPadding(0, px(10), 0, 0) }
        card.addView(status)

        fun pill(text: String, accent: Boolean): TextView {
            val radius = px(26).toFloat()
            val rest = GradientDrawable().apply { cornerRadius = radius; setColor(if (accent) ContextCompat.getColor(activity, R.color.terracotta) else ContextCompat.getColor(activity, R.color.btn_brown_on)) }
            val lit = GradientDrawable().apply { cornerRadius = radius; setColor(ivory) }
            return label(text, 16f, ivory).apply {
                gravity = Gravity.CENTER; isFocusable = true; isClickable = true
                typeface = Typeface.create("sans-serif-medium", if (accent) Typeface.BOLD else Typeface.NORMAL)
                background = rest
                setOnFocusChangeListener { v, f -> v.background = if (f) lit else rest; (v as TextView).setTextColor(if (f) ink else ivory) }
            }
        }
        val signIn = pill("Sign in", true)
        val cancel = pill("Cancel", false).apply { setOnClickListener { sheet.hide() } }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        row.addView(signIn, LinearLayout.LayoutParams(px(190), px(52)))
        row.addView(cancel, LinearLayout.LayoutParams(px(130), px(52)).apply { marginStart = px(10) })
        card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(16) })

        fun submit() {
            val u = user.text.toString().trim(); val p = password.text.toString()
            if (u.isEmpty() || p.isEmpty()) { status.text = "Enter your qBittorrent username and password."; return }
            signIn.isEnabled = false; status.text = "Signing in…"
            thread {
                val error = if (client.signIn(item, u, p)) null else "Couldn't sign in. Check the username, password and connection."
                activity.runOnUiThread {
                    if (error == null) { password.text.clear(); sheet.hide(); Toast.makeText(activity, "Signed in", Toast.LENGTH_SHORT).show(); onSignedIn() }
                    else { status.text = error; signIn.isEnabled = true }
                }
            }
        }
        signIn.setOnClickListener { submit() }
        password.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false }

        val wrap = LinearLayout(activity).apply { addView(card, LinearLayout.LayoutParams(px(560), -2)) }
        sheet.showCustom(wrap, user)
    }
}
