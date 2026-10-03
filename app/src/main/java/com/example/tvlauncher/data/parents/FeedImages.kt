package com.example.tvlauncher.data.parents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Images for the parents' home. Each address is downloaded once into private storage (so photos still
 * show offline and after a reboot), decoded off the main thread at no more than the size it is drawn,
 * and kept in a small memory cache. The feed only ever points at resized renditions, never originals.
 */
class FeedImages(context: Context) {
    private val dir = File(context.filesDir, "parents/img").apply { mkdirs() }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "parents-images").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val memory = object : LruCache<String, Bitmap>(MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    @Volatile private var closed = false

    /**
     * Calls [onLoaded] on the main thread with the picture, or null if it cannot be had (the caller keeps
     * its placeholder). [maxW]/[maxH] are the drawn size in pixels; photos decode as RGB_565 to halve memory.
     */
    fun load(url: String, maxW: Int, maxH: Int, opaque: Boolean = true, onLoaded: (Bitmap?) -> Unit) {
        val key = "$url@${maxW}x$maxH"
        memory.get(key)?.let { onLoaded(it); return }
        worker.execute {
            if (closed) return@execute
            val bitmap = try { decode(fileFor(url), maxW, maxH, opaque) } catch (e: Throwable) {
                Log.w("ParentFeed", "Image unavailable from ${java.net.URL(url).host}: ${e.javaClass.simpleName} ${e.message.orEmpty().take(60)}"); null
            }
            if (bitmap != null) memory.put(key, bitmap)
            main.post { if (!closed) onLoaded(bitmap) }
        }
    }

    /** Downloads what the feed will show soon, without decoding, so it is there offline. */
    fun prefetch(urls: Collection<String>) = worker.execute { urls.forEach { if (!closed) runCatching { fileFor(it) } } }

    /** Deletes stored images the feed no longer mentions, and keeps the folder under its size cap. */
    fun prune(keep: Collection<String>) = worker.execute {
        val wanted = keep.map(::name).toSet()
        val files = dir.listFiles().orEmpty()
        files.filter { it.name !in wanted && System.currentTimeMillis() - it.lastModified() > GRACE_MS }.forEach { it.delete() }
        var total = dir.listFiles().orEmpty().sumOf { it.length() }
        dir.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach { if (total > DISK_BYTES) { total -= it.length(); it.delete() } }
    }

    fun close() { closed = true; worker.shutdownNow(); memory.evictAll() }

    private fun fileFor(url: String): File {
        val file = File(dir, name(url))
        if (file.exists() && file.length() > 0) { file.setLastModified(System.currentTimeMillis()); return file }
        val tmp = File(dir, file.name + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "MCMHome/1.1 (parents' TV)")
        try {
            check(c.responseCode == 200) { "HTTP ${c.responseCode}" }
            val length = c.contentLength.toLong()
            check(length < MAX_FILE_BYTES) { "image too large" }
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally { c.disconnect() }
        check(tmp.length() in 1 until MAX_FILE_BYTES && tmp.renameTo(file)) { "download incomplete" }
        return file
    }

    private fun name(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MEMORY_BYTES = 12 * 1024 * 1024
        private const val DISK_BYTES = 60L * 1024 * 1024
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
        private const val GRACE_MS = 3 * 24 * 3600_000L

        /** Largest power-of-two step that still leaves the picture at least the drawn size. */
        fun sampleSize(width: Int, height: Int, maxW: Int, maxH: Int): Int {
            var sample = 1
            while (width / (sample * 2) >= maxW && height / (sample * 2) >= maxH) sample *= 2
            return sample
        }

        fun decode(file: File, maxW: Int, maxH: Int, opaque: Boolean): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0) { file.delete(); return null }   // not an image: forget it so it is fetched again
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxW, maxH)
                inPreferredConfig = if (opaque) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeFile(file.path, opts)
        }
    }
}
