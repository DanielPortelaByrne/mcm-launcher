package com.example.tvlauncher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Posters and backdrops from the web, for the whole process (Movies and the player share it): decoded pictures in
 * memory keyed by address and width, the downloaded bytes on disk (`cacheDir/images`, 150 MB, least recently used
 * go first), one download per picture however many cards want it, newest requests first, and [cancel] for a page
 * that is rebuilt. [headers] carry a sign-in for images served by the owner's own Jellyfin.
 */
class RemoteImages private constructor(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val core = ImageCache(
        dir = File(context.cacheDir, "images"),
        diskCapBytes = 150L * 1024 * 1024,
        memoryCapBytes = (Runtime.getRuntime().maxMemory() / 6).toInt().coerceAtLeast(8 * 1024 * 1024),
        fetch = ::download,
        decode = ::decodeTo,
        sizeOf = { it.byteCount },
        deliver = { r -> main.post(r) }
    )

    /**
     * Calls [done] on the main thread with the picture, or never if it cannot be had. [maxWidth] (pixels) is the
     * widest it will be shown; it is decoded to that width, not to the size it was stored at. [tag] groups requests
     * that [cancel] can drop together.
     */
    fun load(url: String, headers: Map<String, String> = emptyMap(), maxWidth: Int = 0, tag: Any? = null, done: (Bitmap) -> Unit) {
        // Already decoded: draw it now, in this frame, rather than one post later.
        if (Looper.myLooper() == Looper.getMainLooper()) core.cached(url, maxWidth)?.let { done(it); return }
        core.load(url, headers, maxWidth, tag, done)
    }

    /** Drops every waiting request made with [tag] (a page that has been replaced). */
    fun cancel(tag: Any) = core.cancel(tag)

    /** Kept for callers that used to own an instance; the shared one lives as long as the process. */
    fun close() {}

    companion object {
        @Volatile private var shared: RemoteImages? = null

        fun shared(context: Context): RemoteImages = shared ?: synchronized(this) {
            shared ?: RemoteImages(context.applicationContext).also { shared = it }
        }

        private fun download(url: String, headers: Map<String, String>): ByteArray? = try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            try { if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
        } catch (_: Exception) { null }

        /**
         * Decodes straight to [maxWidth]: a power-of-two subsample first (cheap), then the decoder's own scaling for the
         * rest, so a 1440-pixel picture shown 615 wide is not kept at 720.
         */
        private fun decodeTo(bytes: ByteArray, maxWidth: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return null
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
            if (maxWidth > 0 && bounds.outWidth > maxWidth) {
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                o.inSampleSize = sample
                val sampled = bounds.outWidth / sample
                if (sampled > maxWidth) { o.inScaled = true; o.inDensity = sampled; o.inTargetDensity = maxWidth }
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        }
    }
}
