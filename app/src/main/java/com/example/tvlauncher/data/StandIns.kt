package com.example.tvlauncher.data

import android.content.pm.PackageManager

/**
 * Apps that stand in for another: SmartTube plays YouTube without the ads, so where both are installed
 * it wears YouTube's name and icon, the real YouTube is hidden from the shelves, and links meant for
 * YouTube open in SmartTube. Nobody has to learn a new app; YouTube just behaves better.
 */
object StandIns {
    /** Stand-in package -> the app it replaces. */
    val REPLACES = mapOf("org.smarttube.stable" to "com.amazon.firetv.youtube")

    private fun installed(pm: PackageManager, pkg: String): Boolean =
        try { pm.getApplicationInfo(pkg, 0); true } catch (_: PackageManager.NameNotFoundException) { false }

    /** Apps hidden because their stand-in is installed. */
    fun hidden(pm: PackageManager): Set<String> =
        REPLACES.filter { (standIn, original) -> installed(pm, standIn) && installed(pm, original) }.values.toSet()

    /** [packages] with each replaced app preceded by its installed stand-in, so links prefer it. */
    fun preferring(pm: PackageManager, packages: List<String>): List<String> =
        packages.flatMap { pkg ->
            val standIn = REPLACES.entries.firstOrNull { it.value == pkg }?.key?.takeIf { installed(pm, it) }
            listOfNotNull(standIn, pkg)
        }.distinct()
}
