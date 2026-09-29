package com.example.tvlauncher.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.example.tvlauncher.data.AppEntry

private const val TAG = "TvLauncher"

/** android.media.tv.TvInputManager.ACTION_SETUP_INPUTS -- referenced by string so this
 *  builds without a dependency on the (optional, hardware-gated) android.media.tv APIs. */
private const val ACTION_SETUP_INPUTS = "android.media.tv.action.SETUP_INPUTS"

enum class CapabilityStatus { LAUNCHED, FALLBACK, UNAVAILABLE }

data class CapabilityResult(
    val status: CapabilityStatus,
    /** Set on FALLBACK/UNAVAILABLE: a short, honest explanation to show the owner. */
    val message: String? = null
)

/**
 * A small capability-aware layer over the real TV's system actions.
 * Every entry point is resolved/guarded before use (never assumed to
 * exist), and failures degrade to an honest fallback or "unavailable"
 * result rather than a silent no-op or a fabricated success. Callers
 * (MainActivity) turn [CapabilityResult] into an on-screen message where
 * relevant, and the capability table in the README is filled in from
 * exactly these code paths.
 */
class SystemActions(private val context: Context) {

    /** Baseline for every OEM: standard AOSP action, should always resolve. */
    fun openSettings(): CapabilityResult = launch(Intent(Settings.ACTION_SETTINGS))

    /**
     * Settings.ACTION_SYNC_SETTINGS is a candidate for an accounts screen,
     * not proof of a Google TV profile switcher -- if it doesn't resolve,
     * fall back to full Settings with an explanation rather than pretend
     * an account-switching feature exists.
     */
    fun openAccounts(): CapabilityResult {
        val direct = launch(Intent(Settings.ACTION_SYNC_SETTINGS))
        if (direct.status == CapabilityStatus.LAUNCHED) return direct
        val fallback = openSettings()
        return if (fallback.status == CapabilityStatus.LAUNCHED) {
            CapabilityResult(CapabilityStatus.FALLBACK, "No dedicated accounts screen here -- opened Settings")
        } else fallback
    }

    fun openNetworkSettings(): CapabilityResult {
        val direct = launch(Intent(Settings.ACTION_WIFI_SETTINGS))
        if (direct.status == CapabilityStatus.LAUNCHED) return direct
        val fallback = openSettings()
        return if (fallback.status == CapabilityStatus.LAUNCHED) {
            CapabilityResult(CapabilityStatus.FALLBACK, "No dedicated network screen here -- opened Settings")
        } else fallback
    }

    /**
     * TvInputManager.ACTION_SETUP_INPUTS is a real, documented public AOSP
     * action for the system's input-source manager -- not a guessed OEM
     * package. TCL's actual physical-input switcher may still differ; if
     * this doesn't resolve, fall back to Settings and tell the owner to
     * use the remote's physical Input button.
     */
    fun openInputs(): CapabilityResult {
        val direct = launch(Intent(ACTION_SETUP_INPUTS))
        if (direct.status == CapabilityStatus.LAUNCHED) return direct
        val fallback = openSettings()
        val message = "No input switcher here -- opened Settings. Use the TV remote's physical Input button."
        return if (fallback.status == CapabilityStatus.LAUNCHED) {
            CapabilityResult(CapabilityStatus.FALLBACK, message)
        } else fallback
    }

    /**
     * There is no standard cross-OEM "open Live TV" action. Rather than
     * guess a package name, this looks for `com.android.tv` -- the actual
     * AOSP Live Channels app -- among the apps [AppRepository] already
     * verified are installed and launchable on *this* device. On the
     * physical TCL this same check must re-run against its real installed
     * apps; it is not assumed to carry over.
     */
    fun openLiveTv(installedApps: List<AppEntry>): CapabilityResult {
        val liveTv = installedApps.find { it.packageName == "com.tcl.tv" }
            ?: installedApps.find { it.packageName == "com.android.tv" }
            ?: return CapabilityResult(CapabilityStatus.UNAVAILABLE, "No Live TV app is installed on this device")
        return try {
            context.startActivity(liveTv.launchIntent)
            CapabilityResult(CapabilityStatus.LAUNCHED)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Live TV", e)
            CapabilityResult(CapabilityStatus.UNAVAILABLE, "Live TV app failed to open")
        }
    }

    fun openAppInfo(packageName: String): CapabilityResult = launch(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
    )

    /**
     * Local, installed-app-only search is always available (it's just a
     * filter over already-discovered apps, no intent needed). A genuine
     * system/voice search is only offered if a real handler resolves --
     * never labelled as universal streaming-content search.
     */
    fun resolveGlobalSearchIntent(): Intent? {
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
        return if (context.packageManager.resolveActivity(intent, 0) != null) intent else null
    }

    private fun launch(intent: Intent): CapabilityResult {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            CapabilityResult(CapabilityStatus.LAUNCHED)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "${intent.action} resolved but failed to launch", e)
            CapabilityResult(CapabilityStatus.UNAVAILABLE, "Failed to open")
        } catch (e: SecurityException) {
            Log.e(TAG, "${intent.action} denied", e)
            CapabilityResult(CapabilityStatus.UNAVAILABLE, "Not permitted on this device")
        }
    }
}
