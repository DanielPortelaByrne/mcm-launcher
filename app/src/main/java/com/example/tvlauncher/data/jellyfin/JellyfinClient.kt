package com.example.tvlauncher.data.jellyfin

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val TAG = "Jellyfin"

/** Where the media server answers on the home network. Change it from the connect sheet if the router hands out a new address. */
const val DEFAULT_JELLYFIN_SERVER = "http://192.168.1.233:8096"

/** A Quick Connect attempt in progress: [code] is what to type into an already signed-in Jellyfin. */
data class QuickConnectTicket(val secret: String, val code: String)

/**
 * Talks to the owner's Jellyfin server. Signed in once with Quick Connect (a short code approved from a
 * phone or browser), so no password ever reaches the TV; only the access token is kept, in private storage.
 * Every call here blocks, so callers run it off the main thread, and none of them throw.
 */
class JellyfinClient(context: Context) {
    private val prefs = context.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)

    private val deviceId: String = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }
    private val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    val server: String get() = prefs.getString("server", null) ?: DEFAULT_JELLYFIN_SERVER
    val isConnected: Boolean get() = !prefs.getString("token", null).isNullOrBlank() && !prefs.getString("userId", null).isNullOrBlank()
    private val token: String? get() = prefs.getString("token", null)
    private val userId: String? get() = prefs.getString("userId", null)

    /** What a player needs to fetch a stream: the token travels in a header, never in the address. */
    fun playbackHeaders(): Map<String, String> = token?.let { mapOf("X-Emby-Token" to it) }.orEmpty()

    fun streamUrl(item: JellyfinItem) = JellyfinApi.directStreamUrl(JellyfinApi.cleanBase(server), item)
    fun transcodeUrl(item: JellyfinItem) = JellyfinApi.transcodeUrl(JellyfinApi.cleanBase(server), item, deviceId)

    fun disconnect() { prefs.edit().remove("token").remove("userId").apply() }

    // --- Sign in ---------------------------------------------------------------------------------

    /** Null means the server could not be reached or has Quick Connect switched off; [error] says which. */
    fun startQuickConnect(address: String, error: (String) -> Unit): QuickConnectTicket? {
        val base = JellyfinApi.cleanBase(address)
        val enabled = request("GET", "$base/QuickConnect/Enabled", authed = false)
        if (enabled == null) { error("Couldn't reach Jellyfin at $base"); return null }
        if (!enabled.trim().equals("true", ignoreCase = true)) { error("Quick Connect is switched off on the server"); return null }
        val body = request("POST", "$base/QuickConnect/Initiate", authed = false, json = "{}") ?: run { error("Quick Connect didn't start"); return null }
        return try {
            val o = JSONObject(body)
            prefs.edit().putString("server", base).apply()
            QuickConnectTicket(o.getString("Secret"), o.getString("Code"))
        } catch (_: Exception) { error("Quick Connect gave an unexpected answer"); null }
    }

    /** True once the code has been approved on another device. */
    fun isApproved(ticket: QuickConnectTicket): Boolean {
        val body = request("GET", "${JellyfinApi.cleanBase(server)}/QuickConnect/Connect?secret=${java.net.URLEncoder.encode(ticket.secret, "UTF-8")}", authed = false) ?: return false
        return try { JSONObject(body).optBoolean("Authenticated", false) } catch (_: Exception) { false }
    }

    /** Trades an approved ticket for an access token. */
    fun finishQuickConnect(ticket: QuickConnectTicket): Boolean {
        val body = request("POST", "${JellyfinApi.cleanBase(server)}/Users/AuthenticateWithQuickConnect", authed = false,
            json = JSONObject().put("Secret", ticket.secret).toString()) ?: return false
        return try {
            val o = JSONObject(body)
            val newToken = o.getString("AccessToken")
            val id = o.getJSONObject("User").getString("Id")
            prefs.edit().putString("token", newToken).putString("userId", id).apply()
            true
        } catch (_: Exception) { false }
    }

    // --- Library ---------------------------------------------------------------------------------

    /** The film in the library with this IMDb id, or null when there is none (or Jellyfin cannot be reached). */
    fun findMovieByImdb(imdbId: String): JellyfinItem? {
        val uid = userId ?: return null
        val body = request("GET", JellyfinApi.findByImdbUrl(JellyfinApi.cleanBase(server), uid, imdbId)) ?: return null
        return JellyfinApi.parseItems(body).firstOrNull { it.isMovie }
    }

    // --- Plumbing --------------------------------------------------------------------------------

    private fun request(method: String, url: String, authed: Boolean = true, json: String? = null): String? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 6000
        connection.readTimeout = 10000
        connection.setRequestProperty("Authorization", JellyfinApi.authHeader(deviceId, deviceName, if (authed) token else null))
        connection.setRequestProperty("Accept", "application/json")
        if (json != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json") }
        try {
            if (json != null) connection.outputStream.use { it.write(json.toByteArray()) }
            val code = connection.responseCode
            if (code == 401 && authed) { Log.w(TAG, "Token rejected; signing out"); disconnect(); null }
            else if (code !in 200..299) { Log.w(TAG, "$method ${url.substringBefore('?')} -> HTTP $code"); null }
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "$method ${url.substringBefore('?')} failed: ${e.javaClass.simpleName}"); null }
}
