package com.example.tvlauncher.system

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.example.tvlauncher.ui.PictureOverlay

private const val TAG = "RemoteKeys"

/**
 * Gives the remote's Settings button its behaviour back, in every app. With Google TV's home
 * disabled (see README, *Default launcher*) nothing handles that key any more, so this
 * accessibility service filters it system-wide:
 *
 *  - a short press opens Settings,
 *  - holding it slides in [PictureOverlay] over whatever is playing, without pausing it.
 *
 * If TCL's picture library can't be reached, holding it opens TCL's own picture panel instead.
 * Enabled once over ADB (see README, *Settings button*).
 */
class RemoteKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val control by lazy { PictureControl(this) }
    private val overlay by lazy {
        PictureOverlay(this, control, onMorePicture = ::openTclPicturePanel, onAllSettings = ::openSettings)
    }
    private var settingsDown = false
    private var longPressFired = false
    private val debug by lazy { (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }

    private val longPress = Runnable {
        longPressFired = true
        showPicture()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (debug && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            Log.d(TAG, "key ${KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode})")
        }
        if (event.keyCode in SETTINGS_KEYS) return onSettingsKey(event)
        APP_KEYS[event.keyCode]?.let { packages ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                overlay.hide()
                openFirstInstalled(packages)
            }
            return true
        }
        return overlay.isShowing && overlay.onKey(event)
    }

    private fun onSettingsKey(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                settingsDown = true
                longPressFired = false
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                // An UP without our DOWN (the service was enabled mid-press) is ignored.
                if (settingsDown && !longPressFired) {
                    if (overlay.isShowing) overlay.hide() else openSettings()
                }
                settingsDown = false
            }
        }
        return true
    }

    private fun showPicture() {
        if (overlay.isShowing) return
        if (control.available) overlay.show() else openTclPicturePanel()
    }

    private fun openSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "Settings failed to open", e)
        }
    }

    /** Opens the first of [packages] that is installed, via its TV (leanback) launch activity. */
    private fun openFirstInstalled(packages: List<String>) {
        for (pkg in packages) {
            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: packageManager.getLaunchIntentForPackage(pkg) ?: continue
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                Log.e(TAG, "$pkg failed to open", e)
            }
        }
    }

    /** TCL's own picture panel (`ShowWindowService`, Type=picture), which also floats over the current app. */
    private fun openTclPicturePanel() {
        val intent = Intent("com.tcl.settings.SHOW_WINDOW")
            .setComponent(ComponentName("com.android.tv.settings", "com.tcl.settings.ShowWindowService"))
            .putExtra("Type", "picture")
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "TCL picture panel failed to open", e)
            openSettings()
        }
    }

    /** Debug builds only: `adb shell am broadcast -a com.example.tvlauncher.DEBUG_PICTURE` opens the panel (ADB-injected keys bypass this service). */
    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.getStringExtra("key")) {
                null -> showPicture()
                else -> overlay.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.keyCodeFromString(intent.getStringExtra("key")!!)))
                    .also { overlay.onKey(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.keyCodeFromString(intent.getStringExtra("key")!!))) }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (debug) {
            androidx.core.content.ContextCompat.registerReceiver(
                this, debugReceiver, android.content.IntentFilter("com.example.tvlauncher.DEBUG_PICTURE"),
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() { overlay.hide() }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        overlay.hide()
        if (debug) try { unregisterReceiver(debugReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private companion object {
        const val LONG_PRESS_MS = 550L
        // The TCL RC833 remote's gear button sends NOTIFICATION, not SETTINGS.
        val SETTINGS_KEYS = setOf(KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_NOTIFICATION)
        // Shortcut buttons -> apps (first installed wins). This service sees them before TCL's shortcut
        // manager, so consuming them here also stops TCL's own mapping. With Google TV's home disabled the
        // YouTube and Netflix buttons went dead; the media button opened TCL's Media Center.
        val APP_KEYS = mapOf(
            KeyEvent.KEYCODE_BUTTON_3 to listOf("org.smarttube.stable", "com.google.android.youtube.tv"), // YouTube
            4062 to listOf("com.netflix.ninja"),                                                         // Netflix (TCL key code)
            4057 to listOf("com.stremio.one")                                                            // Media (TCL key code)
        )
    }
}
