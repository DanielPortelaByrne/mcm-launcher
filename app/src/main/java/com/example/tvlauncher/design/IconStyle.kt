package com.example.tvlauncher.design

import android.content.Context

/** Whether app icons are drawn as abstract MCM versions (default) or in each app's own brand colours. */
object IconStyle {
    private const val PREFS = "icon_style"
    private const val KEY = "mcm_default_v2"

    fun isMcm(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun toggle(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, !isMcm(context)).apply()
    }

    fun cacheKey(context: Context): String = if (isMcm(context)) "mcm" else "round"

    fun label(context: Context): String = if (isMcm(context)) "Icons: MCM" else "Icons: Original"
}
