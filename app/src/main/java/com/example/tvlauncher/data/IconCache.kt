package com.example.tvlauncher.data

import android.graphics.drawable.Drawable

/**
 * Simple in-memory cache for loaded app icons/banners, keyed by package
 * name. Icon/banner decoding via PackageManager is the most expensive part
 * of a shelf/grid rebuild; this avoids redoing it every time the launcher
 * re-reads the installed-app list (every onResume).
 */
object IconCache {
    // Filled from the app-loading thread and cleared from the main thread, so every access is synchronised.
    private val cache = HashMap<String, Drawable>()

    fun getOrPut(packageName: String, load: () -> Drawable): Drawable =
        synchronized(cache) { cache[packageName] } ?: load().also { synchronized(cache) { cache[packageName] = it } }

    fun invalidate(packageName: String) {
        synchronized(cache) { cache.remove(packageName) }
    }

    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}
