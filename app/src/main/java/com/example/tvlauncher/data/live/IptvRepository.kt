package com.example.tvlauncher.data.live

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "Iptv"

/** The UK list from iptv-org, a public index of channels their broadcasters stream openly. */
const val UK_CHANNEL_LIST = "https://iptv-org.github.io/iptv/countries/uk.m3u"

/** Fetches the free channel list and keeps a day's copy on disk, so the tab opens at once and works offline. Blocking. */
class IptvRepository(context: Context, private val source: String = UK_CHANNEL_LIST) {
    private val file = File(context.cacheDir, "iptv_uk.m3u")

    fun channels(): List<IptvChannel> {
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < DAY_MS
        if (!fresh) download()
        return try { if (file.exists()) M3u.parse(file.readText()) else emptyList() } catch (e: Exception) { Log.w(TAG, "read failed: ${e.javaClass.simpleName}"); emptyList() }
    }

    private fun download() {
        try {
            val c = URL(source).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            try {
                if (c.responseCode == 200) file.writeText(c.inputStream.bufferedReader().use { it.readText() })
                else Log.w(TAG, "list -> HTTP ${c.responseCode}")
            } finally { c.disconnect() }
        } catch (e: Exception) { Log.w(TAG, "download failed: ${e.javaClass.simpleName}") }
    }

    private companion object { const val DAY_MS = 24L * 60 * 60 * 1000 }
}
