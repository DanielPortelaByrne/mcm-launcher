package com.example.tvlauncher.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Posters and backdrops from the web, kept in a memory cache sized by bytes (a 1440-pixel backdrop is several
 * megabytes decoded). [headers] carry a sign-in for images served by the owner's own Jellyfin.
 */
class RemoteImages(private val activity: Activity) {
    private val pool = Executors.newFixedThreadPool(3)
    private val cache = object : android.util.LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtLeast(8 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Calls [done] on the main thread with the picture, or never if it cannot be had. [maxWidth] shrinks big images as they load. */
    fun load(url: String, headers: Map<String, String> = emptyMap(), maxWidth: Int = 0, done: (Bitmap) -> Unit) {
        cache.get(url)?.let { done(it); return }
        pool.execute {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 12000
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                try {
                    if (c.responseCode != 200) null else {
                        val bytes = c.inputStream.use { it.readBytes() }
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        var sample = 1
                        while (maxWidth > 0 && bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                } finally { c.disconnect() }
            } catch (_: Exception) { null }
            if (bmp != null) { cache.put(url, bmp); activity.runOnUiThread { if (!activity.isDestroyed) done(bmp) } }
        }
    }

    fun close() { pool.shutdownNow() }
}
