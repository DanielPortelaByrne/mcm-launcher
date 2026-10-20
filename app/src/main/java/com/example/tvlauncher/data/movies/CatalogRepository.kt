package com.example.tvlauncher.data.movies

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "Catalog"

/** Fetches the Movies page's shelves from JustWatch and keeps six hours of each on disk, so the page opens at once. Blocking. */
class CatalogRepository(context: Context) {
    private val prefs = context.getSharedPreferences("catalog", Context.MODE_PRIVATE)

    /** One shelf: from cache when fresh, otherwise the network, falling back to a stale copy if that fails. */
    fun shelf(spec: ShelfSpec): Shelf {
        val key = "shelf:" + spec.heading
        val cached = prefs.getString(key, null)
        val savedAt = prefs.getLong("$key:at", 0L)
        if (cached != null && System.currentTimeMillis() - savedAt < TTL_MS) return Shelf(spec.heading, Catalog.dedupe(Catalog.parse(cached)))
        val fresh = fetch(Catalog.requestBody(spec))
        if (fresh != null && Catalog.parse(fresh).isNotEmpty()) {
            prefs.edit().putString(key, fresh).putLong("$key:at", System.currentTimeMillis()).apply()
            return Shelf(spec.heading, Catalog.dedupe(Catalog.parse(fresh)))
        }
        return Shelf(spec.heading, if (cached != null) Catalog.dedupe(Catalog.parse(cached)) else emptyList())
    }

    private fun fetch(body: String): String? = try {
        val c = URL("https://apis.justwatch.com/graphql").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "MCMHome/1.1 (personal TV)")
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else { Log.w(TAG, "HTTP ${c.responseCode}"); null }
        } finally { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "fetch failed: ${e.javaClass.simpleName}"); null }

    private companion object { const val TTL_MS = 6L * 60 * 60 * 1000 }
}
