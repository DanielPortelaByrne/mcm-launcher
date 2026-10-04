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
import com.example.tvlauncher.data.UsageLog

/**
 * Puts the right VPN in front of apps that only play from another country: Brazil for Amélia's Brazilian
 * TV, the UK for Padraig's BBC Sounds. Each person has their own PrivadoVPN account in their own copy of
 * the app (io.privado.android.br is Amélia's, Brazil; io.privado.android.uk is Padraig's, UK -- built by
 * tools/privado-copy). Each copy is set on the TV to its own location, with Split Tunnelling in Tunnel mode
 * carrying only its person's apps. MCM connects and disconnects the copies in the background through
 * their widget service; nothing is pressed on screen (Fire OS lets no app do that).
 *
 * Android runs one VPN at a time, so the other copy is told to let go first. Android does not say which
 * app owns the VPN, so MCM remembers which route it last brought up, and on which network.
 */
object VpnPilot {
    const val TAG = "BrazilTv"

    /** A country an app has to appear to be in, the PrivadoVPN copy that gets it there, and what to say meanwhile. */
    data class Route(val id: String, val vpnApp: String, val title: String, val body: String, val failure: String, val apps: Set<String>)

    val BRASIL = Route(
        id = "brasil", vpnApp = "io.privado.android.br",
        title = "Só um instantinho, mãe…",
        body = "Seu filho está ligando a TV do Brasil pra você.\nNão aperte nenhum botão do controle — já já começa!",
        failure = "Não deu para ligar a TV do Brasil agora. Tente de novo daqui a pouco.",
        apps = setOf("br.com.sbt.mais", "com.globo.globotv", "com.mobilus.recordplay"))   // +SBT, Globoplay, RecordPlus

    val UK = Route(
        id = "uk", vpnApp = "io.privado.android.uk",
        title = "One moment, Dad…",
        body = "Your son is connecting BBC Sounds to the UK for you.\nPlease don't press any buttons — it'll start in a few seconds.",
        failure = "BBC Sounds couldn't connect to the UK just now. Please try again in a minute.",
        apps = setOf("uk.co.bbc.sounds"))

    private val routes = listOf(BRASIL, UK)

    /** The route [pkg] needs, if any, and only when that route's PrivadoVPN copy is installed. */
    fun routeFor(context: Context, pkg: String?): Route? =
        routes.firstOrNull { pkg in it.apps }?.takeIf { context.packageManager.getLaunchIntentForPackage(it.vpnApp) != null ||
            context.packageManager.getLeanbackLaunchIntentForPackage(it.vpnApp) != null }

    private const val LIMIT_MS = 30_000L
    private const val KEPT_MS = 6_000L

    private val main = Handler(Looper.getMainLooper())
    var busy = false; private set
    private var note: View? = null

    fun vpnUp(context: Context): Boolean = vpnNetwork(context) != null

    private fun vpnNetwork(context: Context): String? = vpnNetworks(context).firstOrNull()

    /** Every VPN network Android lists (two, briefly, while one PrivadoVPN copy takes over from the other). */
    private fun vpnNetworks(context: Context): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }.map { it.toString() }
    }

    /** Makes sure [route]'s VPN is the one up (switching PrivadoVPN copies in the background, behind a note), then runs [after]. */
    fun connectThen(context: Context, route: Route, after: () -> Unit) {
        if (busy) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("vpn_pilot", Context.MODE_PRIVATE)
        val current = vpnNetwork(app)
        if (current != null && prefs.getString("route", null) == route.id && prefs.getString("network", null) == current) return after()
        busy = true
        showNote(app, route)
        // Every other copy lets go of the VPN (this also clears a copy still showing "Connected" after Android
        // gave the VPN to another), then this route's copy connects to its own last location.
        routes.filter { it != route }.forEach { command(app, it, CONNECTED = false) }
        main.postDelayed({ command(app, route, CONNECTED = true) }, 1500)
        val started = System.currentTimeMillis()
        main.postDelayed(object : Runnable {
            override fun run() {
                val waited = System.currentTimeMillis() - started
                val all = vpnNetworks(app)
                // A new VPN network is this copy connecting. The old one still standing once the other copies
                // have let go means it was this copy's all along.
                val fresh = all.firstOrNull { it != current }
                val kept = current != null && current in all && waited > KEPT_MS
                when {
                    fresh != null || kept -> {
                        val now = fresh ?: current
                        Log.i(TAG, "${route.id} VPN up after $waited ms" + if (fresh == null) " (already had it)" else "")
                        prefs.edit().putString("route", route.id).putString("network", now).apply()
                        UsageLog.event("vpn", where = route.id, seconds = waited / 1000, detail = "VPN connected")
                        after()
                        // Keep the note up a moment, so the app draws underneath it.
                        main.postDelayed({ hideNote(app); busy = false }, 2500)
                    }
                    waited > LIMIT_MS -> {
                        Log.w(TAG, "${route.id} VPN not up after $waited ms")
                        UsageLog.event("vpn", where = route.id, seconds = waited / 1000, detail = "VPN did not connect; the error was shown")
                        hideNote(app); busy = false
                        Toast.makeText(app, route.failure, Toast.LENGTH_LONG).show()
                    }
                    else -> main.postDelayed(this, 500)
                }
            }
        }, 2000)
    }

    /**
     * Asks [route]'s PrivadoVPN copy to connect or disconnect, the way its home-screen widget does: its
     * WidgetVpnService takes CURRENT_CONNECT_STATE 1 (connect) or 2 (disconnect). The copies open that
     * service to apps signed like MCM only (tools/privado-copy).
     */
    @Suppress("LocalVariableName")
    private fun command(app: Context, route: Route, CONNECTED: Boolean) {
        val intent = Intent().setComponent(android.content.ComponentName(route.vpnApp, "io.privado.android.widget.WidgetVpnService"))
            .putExtra("CURRENT_CONNECT_STATE", if (CONNECTED) 1 else 2)
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
            Log.i(TAG, "${if (CONNECTED) "Connect" else "Disconnect"} ${route.id}")
        } catch (e: Exception) { Log.w(TAG, "${route.id} not reachable: ${e.javaClass.simpleName}") }
    }

    private fun showNote(app: Context, route: Route) {
        hideNote(app)
        val d = app.resources.displayMetrics.density
        val card = LinearLayout(app).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.rgb(0x2A, 0x1E, 0x16)) }
        card.addView(TextView(app).apply {
            text = route.title; textSize = 40f; setTextColor(Color.rgb(0xF3, 0xE9, 0xCF)); gravity = Gravity.CENTER
            typeface = Typeface.create("serif", Typeface.BOLD_ITALIC)
        })
        card.addView(TextView(app).apply {
            text = route.body; textSize = 24f; setTextColor(Color.rgb(0xD9, 0xC7, 0xA3)); gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f)
            setPadding((120 * d).toInt(), (20 * d).toInt(), (120 * d).toInt(), (28 * d).toInt())
        })
        val dot = View(app).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(0xC8, 0x6B, 0x3C)) } }
        card.addView(dot, LinearLayout.LayoutParams((14 * d).toInt(), (14 * d).toInt()))
        val pulse = object : Runnable { override fun run() { dot.animate().alpha(if (dot.alpha < 0.5f) 1f else 0.25f).setDuration(700).withEndAction(this).start() } }
        pulse.run()
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        // Not focusable or touchable: the PrivadoVPN copy underneath stays the active window.
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
