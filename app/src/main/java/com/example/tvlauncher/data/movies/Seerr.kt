package com.example.tvlauncher.data.movies

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "Seerr"

/** Where the request service answers, next to the media server on the Deck. */
const val DEFAULT_SEERR = "http://192.168.1.233:5055"

enum class Availability { NONE, REQUESTED, DOWNLOADING, PARTIAL, AVAILABLE }

enum class RequestOutcome { QUEUED, ALREADY, NEEDS_SIGN_IN, FAILED }

/** The plain-Kotlin half of the Seerr client, so request bodies and answers can be tested. */
object SeerrApi {
    fun signInBody(username: String, password: String): String =
        JSONObject().put("username", username).put("password", password).toString()

    /** Films are asked for outright; for a series every season is asked for. */
    fun requestBody(kind: Kind, tmdbId: Long): String {
        val o = JSONObject().put("mediaType", if (kind == Kind.MOVIE) "movie" else "tv").put("mediaId", tmdbId)
        if (kind == Kind.SHOW) o.put("seasons", "all")
        return o.toString()
    }

    fun path(kind: Kind, tmdbId: Long) = (if (kind == Kind.MOVIE) "/api/v1/movie/" else "/api/v1/tv/") + tmdbId

    /** Seerr's media status numbers: 1 unknown, 2 pending, 3 processing, 4 partly available, 5 available. */
    fun parseAvailability(json: String): Availability = try {
        when (JSONObject(json).optJSONObject("mediaInfo")?.optInt("status", 1)) {
            2 -> Availability.REQUESTED
            3 -> Availability.DOWNLOADING
            4 -> Availability.PARTIAL
            5 -> Availability.AVAILABLE
            else -> Availability.NONE
        }
    } catch (_: Exception) { Availability.NONE }

    fun outcomeFor(httpCode: Int): RequestOutcome = when (httpCode) {
        200, 201, 202 -> RequestOutcome.QUEUED
        409 -> RequestOutcome.ALREADY
        401, 403 -> RequestOutcome.NEEDS_SIGN_IN
        else -> RequestOutcome.FAILED
    }

    /** The session cookie out of a sign-in answer's Set-Cookie headers, as it should be sent back. */
    fun sessionCookie(setCookie: List<String>?): String? =
        setCookie?.firstOrNull { it.startsWith("connect.sid=") }?.substringBefore(';')
}

/**
 * Asks the owner's Seerr to download a title. The TV signs in once with the owner's Jellyfin account, typed on the
 * TV; only the session cookie is kept (private storage), never the password. Every call blocks and none throw.
 */
class SeerrClient(context: Context) {
    private val prefs = context.getSharedPreferences("seerr", Context.MODE_PRIVATE)
    private val base: String get() = prefs.getString("server", null) ?: DEFAULT_SEERR
    val isSignedIn: Boolean get() = !prefs.getString("cookie", null).isNullOrBlank()

    fun signOut() { prefs.edit().remove("cookie").apply() }

    /** Null on success, otherwise a short reason to show the owner. */
    fun signIn(username: String, password: String): String? = try {
        val c = open("POST", "$base/api/v1/auth/jellyfin", withCookie = false)
        c.doOutput = true
        try {
            c.outputStream.use { it.write(SeerrApi.signInBody(username, password).toByteArray()) }
            when (val code = c.responseCode) {
                200 -> SeerrApi.sessionCookie(c.headerFields["Set-Cookie"])?.let { prefs.edit().putString("cookie", it).apply(); null } ?: "Signed in, but no session came back"
                401, 403 -> "Wrong username or password"
                else -> "Couldn't sign in (HTTP $code)"
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "signIn failed: ${e.javaClass.simpleName}"); "Couldn't reach the request service" }

    fun request(kind: Kind, tmdbId: Long): RequestOutcome = try {
        val c = open("POST", "$base/api/v1/request")
        c.doOutput = true
        try {
            c.outputStream.use { it.write(SeerrApi.requestBody(kind, tmdbId).toByteArray()) }
            val code = c.responseCode
            if (code == 401) signOut()
            SeerrApi.outcomeFor(code)
        } finally { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "request failed: ${e.javaClass.simpleName}"); RequestOutcome.FAILED }

    fun availability(kind: Kind, tmdbId: Long): Availability = try {
        val c = open("GET", base + SeerrApi.path(kind, tmdbId))
        try { if (c.responseCode == 200) SeerrApi.parseAvailability(c.inputStream.bufferedReader().use { it.readText() }) else Availability.NONE } finally { c.disconnect() }
    } catch (_: Exception) { Availability.NONE }

    private fun open(method: String, url: String, withCookie: Boolean = true): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 6000; c.readTimeout = 12000
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("Accept", "application/json")
        if (withCookie) prefs.getString("cookie", null)?.let { c.setRequestProperty("Cookie", it) }
        return c
    }
}
