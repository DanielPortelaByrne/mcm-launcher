package com.example.tvlauncher.system

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.tvlauncher.MainActivity

/**
 * Makes sure PrivadoVPN is connected to Brazil before +SBT opens. PrivadoVPN is set up on the TV to do
 * this by itself: Auto Connect (connects as soon as the app opens), Best Server "Disabled" (always the
 * last used location, São Paulo), and Split Tunnelling in Tunnel mode with only +SBT, so nothing else in
 * the house goes through Brazil and the free monthly allowance is spent only on +SBT. MCM therefore only
 * has to open PrivadoVPN and wait for the tunnel; it never presses anything (Fire OS lets no app do that).
 *
 * While it waits, a full-screen note in Portuguese covers PrivadoVPN and asks Amélia to leave the remote
 * alone for a moment.
 */
object VpnPilot {
    const val TAG = "BrazilTv"
    private const val VPN_APP = "io.privado.android"
    private const val LIMIT_MS = 30_000L

    private val main = Handler(Looper.getMainLooper())
    var busy = false; private set
    private var note: View? = null

    fun vpnUp(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** Opens PrivadoVPN behind a note, waits for its tunnel, then runs [after]. */
    fun connectThen(context: Context, after: () -> Unit) {
        if (busy) return
        val app = context.applicationContext
        val vpn = app.packageManager.getLaunchIntentForPackage(VPN_APP) ?: return after()
        busy = true
        showNote(app)
        app.startActivity(vpn.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val started = System.currentTimeMillis()
        main.postDelayed(object : Runnable {
            override fun run() {
                val waited = System.currentTimeMillis() - started
                when {
                    vpnUp(app) -> {
                        Log.i(TAG, "Brazil VPN up after $waited ms")
                        com.example.tvlauncher.data.UsageLog.event("tv_do_brasil", seconds = waited / 1000, detail = "VPN connected")
                        after()
                        // Keep the note up a moment, so +SBT draws underneath it rather than PrivadoVPN flashing past.
                        main.postDelayed({ hideNote(app); busy = false }, 2500)
                    }
                    waited > LIMIT_MS -> {
                        Log.w(TAG, "Brazil VPN not up after $waited ms")
                        com.example.tvlauncher.data.UsageLog.event("tv_do_brasil", seconds = waited / 1000, detail = "VPN did not connect; she saw the error")
                        hideNote(app); busy = false
                        Toast.makeText(app, "Não deu para ligar a TV do Brasil agora. Tente de novo daqui a pouco.", Toast.LENGTH_LONG).show()
                        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    }
                    else -> main.postDelayed(this, 500)
                }
            }
        }, 500)
    }

    private fun showNote(app: Context) {
        hideNote(app)
        val d = app.resources.displayMetrics.density
        val card = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.rgb(0x2A, 0x1E, 0x16)) }
        card.addView(TextView(app).apply {
            text = "Só um instantinho, mãe…"; textSize = 40f; setTextColor(Color.rgb(0xF3, 0xE9, 0xCF)); gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD_ITALIC)
        })
        card.addView(TextView(app).apply {
            text = "Seu filho está ligando a TV do Brasil pra você.\nNão aperte nenhum botão do controle — já já começa!"
            textSize = 24f; setTextColor(Color.rgb(0xD9, 0xC7, 0xA3)); gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f)
            setPadding((120 * d).toInt(), (20 * d).toInt(), (120 * d).toInt(), (28 * d).toInt())
        })
        val dot = View(app).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0xC8, 0x6B, 0x3C)) } }
        card.addView(dot, LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt()))
        val pulse = object : Runnable { override fun run() { dot.animate().alpha(if (dot.alpha < 0.5f) 1f else 0.25f).setDuration(700).withEndAction(this).start() } }
        pulse.run()
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(-1, -1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE)
        try { app.getSystemService(WindowManager::class.java).addView(card, params); note = card } catch (e: Exception) { Log.w(TAG, "No note: ${e.javaClass.simpleName}") }
    }

    private fun hideNote(app: Context) {
        note?.let { v -> v.animate().cancel(); try { app.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {} }
        note = null
    }
}
