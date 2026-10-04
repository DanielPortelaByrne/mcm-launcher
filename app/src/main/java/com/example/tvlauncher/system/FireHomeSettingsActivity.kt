package com.example.tvlauncher.system

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Remote-accessible controls: press Menu anywhere within MCM. */
class FireHomeSettingsActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 40, 80, 30)
            setBackgroundColor(Color.rgb(28, 31, 30))
        }
        status = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE) }
        layout.addView(status)
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action(); refresh() } })
        }
        button("Turn on MCM Home button") {
            if (FireHomeService.supported() && checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED) {
                FireHomeService.prefs(this).edit().putBoolean("enabled", true).putLong("pause_until", 0).commit()
                FireHomeService.startIfEnabled(this)
            }
        }
        button("Turn off MCM Home button") {
            FireHomeService.prefs(this).edit().putBoolean("enabled", false).commit()
            stopService(Intent(this, FireHomeService::class.java))
        }
        button("Open Amazon Home (pause for one minute)") {
            FireHomeService.prefs(this).edit().putLong("pause_until", System.currentTimeMillis() + 60000).commit()
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        button("Fire TV settings") { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        button("Back to MCM") { finish() }
        setContentView(layout)
        refresh()
    }
    private fun refresh() {
        status.text = when {
            !FireHomeService.supported() -> "Home redirect supports Fire OS 6 and 7 only."
            checkSelfPermission(Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED -> "Home button setup needs the one-time log permission."
            !FireHomeService.enabled(this) -> "Home button: Amazon Home"
            FireHomeService.prefs(this).getLong("pause_until", 0) > System.currentTimeMillis() -> "Home button: paused for Amazon Home"
            else -> "Home button: MCM\nAmazon Home may appear briefly. Menu in MCM opens these controls."
        }
    }
}
