package com.example.tvlauncher.ui

import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * The picture pipeline behind [RemoteImages], without Android types so it can be tested: a memory cache of decoded
 * images keyed by address and target width, a disk cache of the downloaded bytes, one download per address however
 * many callers want it, newest requests first, and cancelling whatever a page asked for when it is rebuilt.
 *
 * [fetch] downloads (null when it cannot), [decode] turns bytes into [T] at a target width, [deliver] runs a callback
 * where the caller wants it (the main thread), [sizeOf] weighs a decoded image for the memory cache.
 */
class ImageCache<T : Any>(
    private val dir: File?,
    private val diskCapBytes: Long,
    memoryCapBytes: Int,
    private val fetch: (url: String, headers: Map<String, String>) -> ByteArray?,
    private val decode: (bytes: ByteArray, maxWidth: Int) -> T?,
    private val sizeOf: (T) -> Int,
    private val deliver: (Runnable) -> Unit,
    workers: Int = 3,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val memory = ByteLru<T>(memoryCapBytes.toLong(), sizeOf)

    private class Waiter<T>(val tag: Any?, val done: (T) -> Unit)
    private inner class Job(val key: String, val url: String, val headers: Map<String, String>, val maxWidth: Int) {
        val waiters = ArrayList<Waiter<T>>()
        var running = false
    }

    private val lock = Object()
    private val jobs = HashMap<String, Job>()
    private val queue = ArrayDeque<Job>()          // front = newest: what was asked for last is on screen now
    private var diskBytes = -1L                    // counted on first write
    @Volatile private var closed = false

    init {
        repeat(workers) { i -> Thread({ work() }, "images-$i").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start() }
    }

    /** The decoded image if it is in memory now, without any work. */
    fun cached(url: String, maxWidth: Int): T? = memory.get(key(url, maxWidth))

    /**
     * Calls [done] (via [deliver]) with the image at [maxWidth] (0 = as stored), or never when it cannot be had. A
     * second request for the same image while it loads joins the first. [tag] groups requests for [cancel].
     */
    fun load(url: String, headers: Map<String, String>, maxWidth: Int, tag: Any?, done: (T) -> Unit) {
        val k = key(url, maxWidth)
        memory.get(k)?.let { hit -> deliver(Runnable { done(hit) }); return }
        synchronized(lock) {
            val job = jobs[k]
            if (job != null) {
                job.waiters += Waiter(tag, done)
                // Asked for again: it is wanted now, so it moves to the front if it has not started.
                if (!job.running && queue.remove(job)) queue.addFirst(job)
                return
            }
            val fresh = Job(k, url, headers, maxWidth).also { it.waiters += Waiter(tag, done) }
            jobs[k] = fresh
            queue.addFirst(fresh)
            lock.notify()
        }
    }

    /** Forgets every waiting request made with [tag]; downloads nobody else wants are dropped from the queue. */
    fun cancel(tag: Any) {
        synchronized(lock) {
            val it = queue.iterator()
            while (it.hasNext()) {
                val job = it.next()
                job.waiters.removeAll { w -> w.tag === tag }
                if (job.waiters.isEmpty()) { it.remove(); jobs.remove(job.key) }
            }
            jobs.values.filter { it.running }.forEach { job -> job.waiters.removeAll { w -> w.tag === tag } }
        }
    }

    /** Requests still waiting to start (for tests and diagnostics). */
    val queued: Int get() = synchronized(lock) { queue.size }

    fun close() { closed = true; synchronized(lock) { queue.clear(); jobs.clear(); lock.notifyAll() } }

    private fun work() {
        while (!closed) {
            val job = synchronized(lock) {
                while (queue.isEmpty() && !closed) lock.wait()
                if (closed) return
                queue.removeFirst().also { it.running = true }
            }
            val image = try { produce(job) } catch (_: Throwable) { null }
            val waiters = synchronized(lock) { jobs.remove(job.key); ArrayList(job.waiters) }
            if (image != null) {
                memory.put(job.key, image)
                waiters.forEach { w -> deliver(Runnable { w.done(image) }) }
            }
        }
    }

    /** Disk first; otherwise the network, keeping the bytes on disk for next time. */
    private fun produce(job: Job): T? {
        val file = dir?.let { File(it, sha1(job.url)) }
        if (file != null && file.exists()) {
            val bytes = try { file.readBytes() } catch (_: Exception) { null }
            if (bytes != null) {
                file.setLastModified(clock())
                decode(bytes, job.maxWidth)?.let { return it }
                file.delete()   // unreadable: fetch it again
            }
        }
        val bytes = fetch(job.url, job.headers) ?: return null
        val image = decode(bytes, job.maxWidth) ?: return null
        if (file != null) store(file, bytes)
        return image
    }

    private fun store(file: File, bytes: ByteArray) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file) && !(file.delete() && tmp.renameTo(file))) { tmp.delete(); return }
            synchronized(this) {
                if (diskBytes < 0) diskBytes = file.parentFile?.listFiles()?.sumOf { it.length() } ?: 0L else diskBytes += bytes.size
                if (diskBytes > diskCapBytes) trim()
            }
        } catch (_: Exception) { }
    }

    /** Least recently used files go first, down to 80 % of the cap. */
    private fun trim() {
        val files = dir?.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= diskCapBytes * 8 / 10) break
            val n = f.length()
            if (f.delete()) total -= n
        }
        diskBytes = total
    }

    private fun key(url: String, maxWidth: Int) = "$maxWidth|$url"

    companion object {
        fun sha1(text: String): String = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** A least-recently-used map weighed in bytes. Thread-safe. */
class ByteLru<T : Any>(private val capBytes: Long, private val sizeOf: (T) -> Int) {
    private val map = LinkedHashMap<String, T>(64, 0.75f, true)
    private var bytes = 0L

    @Synchronized fun get(key: String): T? = map[key]

    @Synchronized fun put(key: String, value: T) {
        map.put(key, value)?.let { bytes -= sizeOf(it) }
        bytes += sizeOf(value)
        val it = map.entries.iterator()
        while (bytes > capBytes && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue
            bytes -= sizeOf(e.value); it.remove()
        }
    }

    @get:Synchronized val size: Int get() = map.size
}
