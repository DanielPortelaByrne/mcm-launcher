package com.example.tvlauncher.data.stream

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "SmartStream"

/** Pure parsing of Stremio's account API, kept apart from networking so it can be unit tested. */
object StremioApiParser {
    /** The auth token from a `login` response, or the API's error message. */
    fun parseLogin(json: String): Pair<String?, String?> = try {
        val root = JSONObject(json)
        val key = root.optJSONObject("result")?.optString("authKey")?.takeIf { it.isNotBlank() }
        key to root.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null to "Unexpected response from Stremio" }

    /** The account's installed addons that can supply movie streams. */
    fun parseAddons(json: String): List<AddonConfig> = try {
        val addons = JSONObject(json).getJSONObject("result").getJSONArray("addons")
        (0 until addons.length()).mapNotNull { i ->
            val a = addons.getJSONObject(i)
            val manifest = a.optJSONObject("manifest") ?: return@mapNotNull null
            val url = a.optString("transportUrl").removeSuffix("/manifest.json").removeSuffix("/")
            if (!url.startsWith("http") || !suppliesMovieStreams(manifest)) return@mapNotNull null
            AddonConfig(manifest.optString("name").ifBlank { url }, url)
        }
    } catch (_: Exception) { emptyList() }

    private fun suppliesMovieStreams(manifest: JSONObject): Boolean {
        val resources = manifest.optJSONArray("resources") ?: return false
        val topTypes = manifest.optJSONArray("types")?.let { t -> (0 until t.length()).map { t.optString(it) } }
        for (i in 0 until resources.length()) {
            when (val r = resources.opt(i)) {
                "stream" -> return topTypes == null || "movie" in topTypes
                is JSONObject -> if (r.optString("name") == "stream") {
                    val types = r.optJSONArray("types")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: topTypes
                    return types == null || "movie" in types
                }
            }
        }
        return false
    }
}

/**
 * The owner's Stremio account. Signing in once (on the TV, with the remote) lets PLAY use exactly the
 * addons already installed in Stremio, so Stremio stays the single source of streams. Only the auth
 * token and the addon list are kept; the password is never stored.
 */
class StremioAccount(context: Context) {
    private val prefs = context.getSharedPreferences("stremio_account", Context.MODE_PRIVATE)

    val isConnected: Boolean get() = prefs.getString("authKey", null) != null
    val email: String? get() = prefs.getString("email", null)

    /** Blocking. Returns null on success, or a message fit to show the user. */
    fun connect(email: String, password: String): String? {
        val response = post("login", JSONObject().put("type", "Auth").put("email", email.trim()).put("password", password).put("facebook", false))
            ?: return "Couldn't reach Stremio. Check the connection."
        val (key, error) = StremioApiParser.parseLogin(response)
        if (key == null) return error ?: "Sign-in failed."
        prefs.edit().putString("authKey", key).putString("email", email.trim()).apply()
        return if (refresh()) null else "Signed in, but couldn't read your addons."
    }

    /** Re-reads the addon list from the account. True if at least the request worked. */
    fun refresh(): Boolean {
        val key = prefs.getString("authKey", null) ?: return false
        val response = post("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("authKey", key).put("update", true)) ?: return false
        // A rejected token means the sign-in expired: forget it so PLAY asks again instead of failing silently.
        if (JSONObject(response).optJSONObject("error") != null) { Log.w(TAG, "Stremio rejected the saved sign-in"); disconnect(); return false }
        val addons = StremioApiParser.parseAddons(response)
        val arr = JSONArray().also { a -> addons.forEach { a.put(JSONObject().put("name", it.name).put("url", it.baseUrl)) } }
        prefs.edit().putString("addons", arr.toString()).putLong("addonsAt", System.currentTimeMillis()).apply()
        Log.i(TAG, "Imported ${addons.size} stream addons from Stremio")
        return true
    }

    /** The cached addons, refreshed in the background when older than a day. */
    fun addons(): List<AddonConfig> {
        if (!isConnected) return emptyList()
        if (System.currentTimeMillis() - prefs.getLong("addonsAt", 0) > 24 * 3600_000L) try { refresh() } catch (_: Exception) {}
        return try {
            val a = JSONArray(prefs.getString("addons", "[]"))
            (0 until a.length()).map { AddonConfig(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getString("url")) }
        } catch (_: Exception) { emptyList() }
    }

    fun disconnect() { prefs.edit().clear().apply() }

    private fun post(method: String, body: JSONObject): String? = try {
        val c = URL("https://api.strem.io/api/$method").openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 15000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        } finally { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "Stremio API $method failed: ${e.javaClass.simpleName}"); null }
}

/** Stremio's own streaming engine, which runs as a background service on the TV and turns torrent results into a playable stream. */
object StremioEngine {
    const val BASE = "http://127.0.0.1:11470"

    /** Is the engine answering? It is a local request, so this is fast. */
    fun isUp(): Boolean = try {
        val c = URL("$BASE/heartbeat").openConnection() as HttpURLConnection
        c.connectTimeout = 1500; c.readTimeout = 1500
        try { c.responseCode == 200 } finally { c.disconnect() }
    } catch (_: Exception) { false }

    /** `http://127.0.0.1:11470/<hash>/<file>?tr=...`: the engine picks the file when [fileIdx] is unknown. */
    fun streamUrl(infoHash: String, fileIdx: Int?, trackers: List<String>): String =
        "$BASE/$infoHash/${fileIdx ?: -1}" + trackers.take(8).joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }.replaceFirst("&", "?")
}
