package com.example.tvlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.example.tvlauncher.design.IconStyle
import com.example.tvlauncher.design.RoundIcon
import java.util.Locale

private const val TAG = "TvLauncher"

/**
 * Discovers real, launchable applications on the device. No hardcoded
 * tiles: every entry returned corresponds to an installed app the system
 * can actually start.
 */
class AppRepository(private val context: Context) {

    /**
     * @param ownedOnly hides the TV maker's preloaded utilities (Message Box, MagiConnect,
     * Media Player, Guard...) so only apps the owner actually uses are shown. Live TV is the
     * one exception the launcher still needs, so callers can ask for the full list.
     */
    fun loadLaunchableApps(ownedOnly: Boolean = true): List<AppEntry> {
        val pm = context.packageManager
        val selfPackage = context.packageName

        // The launcher already provides a dedicated settings affordance
        // (the gear icon), so the system settings app is excluded here to
        // avoid showing it twice. Resolved dynamically rather than by a
        // hardcoded package name, since OEMs (e.g. the target TCL set)
        // may ship a different settings package than the emulator's AOSP one.
        val settingsPackage = pm.resolveActivity(Intent("android.settings.SETTINGS"), 0)
            ?.activityInfo?.packageName

        // TV apps commonly declare CATEGORY_LEANBACK_LAUNCHER instead of (or
        // in addition to) CATEGORY_LAUNCHER, so both must be queried to find
        // every launchable app on an Android TV device. Both categories also
        // need an explicit <queries> declaration in the manifest, since only
        // CATEGORY_LAUNCHER gets the default-launcher package-visibility
        // exemption.
        val leanbackIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory("android.intent.category.LEANBACK_LAUNCHER")
        }
        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = pm.queryIntentActivities(leanbackIntent, 0) +
            pm.queryIntentActivities(launcherIntent, 0)

        return resolved
            .filter { it.activityInfo.packageName != selfPackage }
            .filter { it.activityInfo.packageName != settingsPackage }
            .filter { !ownedOnly || isOwned(it.activityInfo.packageName) }
            .mapNotNull { ri ->
                try {
                    val packageName = ri.activityInfo.packageName
                    // Built directly from the resolved ActivityInfo rather than
                    // PackageManager.getLaunchIntentForPackage(), which only
                    // resolves CATEGORY_LAUNCHER and silently drops
                    // LEANBACK_LAUNCHER-only apps.
                    val launch = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        component = ComponentName(packageName, ri.activityInfo.name)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    val icon = IconCache.getOrPut("$packageName#${IconStyle.cacheKey(context)}") {
                        val original = ri.loadIcon(pm)
                        if (IconStyle.isMcm(context)) RoundIcon.mcm(context, original, packageName) else RoundIcon.from(context, original)
                    }
                    AppEntry(
                        label = DISPLAY_NAMES[ri.activityInfo.packageName] ?: ri.loadLabel(pm).toString(),
                        packageName = packageName,
                        icon = icon,
                        banner = null,   // banners are never shown; loading them cost start-up time
                        launchIntent = launch
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping ${ri.activityInfo.packageName}: ${e.message}")
                    null
                }
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    /**
     * Leanback banner artwork (a TV app's real landscape brand art), when
     * the app declares one via android:banner on the <application> tag.
     * Most non-TV-first apps (Play Store, Settings) do not declare one, in
     * which case this returns null and the caller falls back to [icon].
     */
    private fun loadBanner(pm: PackageManager, packageName: String): android.graphics.drawable.Drawable? {
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            if (appInfo.banner != 0) pm.getApplicationBanner(appInfo) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isOwned(packageName: String): Boolean =
        !packageName.startsWith("com.tcl.") && packageName !in HIDDEN_PACKAGES

    private companion object {
        /** Preloaded promo / utility apps that are not the household's own. */
        val HIDDEN_PACKAGES = setOf("com.google.android.play.games")

        /** Sentinel stored in IconCache to remember "checked, no banner" without re-querying. */
        val NULL_BANNER: android.graphics.drawable.Drawable =
            android.graphics.drawable.ColorDrawable(0)
    }
}

/** Apps whose own label is too terse to read on its own ("5"). */
private val DISPLAY_NAMES = mapOf("com.channel5.my5" to "Channel 5")
