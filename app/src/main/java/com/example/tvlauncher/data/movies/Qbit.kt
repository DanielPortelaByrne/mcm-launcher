package com.example.tvlauncher.data.movies

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Connection settings are read from the existing download client and kept only in memory. */
class QbitConfig(val baseUrl: String, val username: String, val password: String)

data class TorrentState(val hash: String, val state: String, val priority: Int, val forced: Boolean, val progress: Double)
enum class DownloadAction { NEXT, NOW }
enum class DownloadActionResult { DONE, QUEUE_DISABLED, NOT_FOUND, NOT_CONFIGURED, NEEDS_SIGN_IN, FAILED }

object QbitApi {
    fun validHash(hash: String?): Boolean = hash != null && Regex("(?:[a-fA-F0-9]{40}|[a-fA-F0-9]{64})").matches(hash)
    fun form(vararg fields: Pair<String, String>): String = fields.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }

    /** Match the actual named client; never accidentally send a command to another download client. */
    fun config(json: String, clientName: String?, serverHost: String): QbitConfig? = try {
        val all = JSONArray(json)
        val clients = (0 until all.length()).map { all.getJSONObject(it) }.filter {
            it.optBoolean("enable") && it.optString("implementation").equals("QBittorrent", ignoreCase = true)
        }
        val client = if (!clientName.isNullOrBlank()) clients.singleOrNull { it.optString("name") == clientName }
                     else clients.singleOrNull()
        client?.let {
            val fields = it.getJSONArray("fields")
            val values = (0 until fields.length()).map { fields.getJSONObject(it) }.associate {
                it.getString("name") to if (it.isNull("value")) "" else it.optString("value")
            }
            val host = values["host"].takeUnless { h -> h.isNullOrBlank() || h in listOf("localhost", "127.0.0.1", "::1") } ?: serverHost
            val port = values["port"]?.toIntOrNull() ?: 8080
            val prefix = values["urlBase"].orEmpty().trim('/').let { if (it.isEmpty()) "" else "/$it" }
            QbitConfig(URI(if (values["useSsl"].toBoolean()) "https" else "http", null, host, port, prefix, null, null).toString(),
                values["username"].orEmpty(), values["password"].orEmpty().takeUnless { password -> password.matches(Regex("\\*+")) }.orEmpty())
        }
    } catch (_: Exception) { null }

    fun torrents(json: String): Map<String, TorrentState> = try {
        val all = JSONArray(json)
        (0 until all.length()).map { all.getJSONObject(it) }.mapNotNull {
            val hash = it.optString("hash").lowercase()
            if (!validHash(hash)) null else hash to TorrentState(hash, it.optString("state"), it.optInt("priority", -1),
                it.optBoolean("force_start"), it.optDouble("progress", 0.0))
        }.toMap()
    } catch (_: Exception) { emptyMap() }

    fun apply(item: QueueItem, torrent: TorrentState): QueueItem {
        // Leave import errors visible even after the torrent has finished downloading.
        val status = if (torrent.progress >= 1.0) item.status else when (torrent.state) {
            "queuedDL" -> "queued"
            "pausedDL", "stoppedDL" -> "paused"
            "downloading", "forcedDL" -> "downloading"
            "metaDL", "forcedMetaDL" -> "metadata"
            "stalledDL" -> "stalled"
            "checkingDL", "checkingResumeData" -> "checking"
            "error", "missingFiles" -> "failed"
            else -> item.status
        }
        return item.copy(status = status, position = if (status == "queued") torrent.priority.coerceAtLeast(0) else 0,
            canPrioritize = torrent.progress < 1.0 && status !in setOf("completed", "failed", "checking"), forced = torrent.forced)
    }
}

internal data class QbitReply(val code: Int, val body: String = "", val cookies: List<String> = emptyList())
internal fun interface QbitTransport {
    fun call(method: String, path: String, body: String?, cookie: String?): QbitReply
}

/** An isolated cookie session for this server; passwords, cookies and responses are never logged or saved. */
internal class QbitClient(
    private val config: QbitConfig, private val transport: QbitTransport = http(config),
    initialCookie: String? = null, private val onSession: (String) -> Unit = {}
) {
    private var cookie: String? = initialCookie
    private var rejectedCredentials = false

    @Synchronized
    fun states(): Map<String, TorrentState>? {
        val reply = call("GET", "/api/v2/torrents/info")
        return if (reply.code == 200 && reply.body.trimStart().startsWith("[")) QbitApi.torrents(reply.body) else null
    }

    @Synchronized
    fun perform(hash: String, action: DownloadAction): DownloadActionResult {
        if (!QbitApi.validHash(hash)) return DownloadActionResult.NOT_FOUND
        val selected = hash.lowercase()
        val check = call("GET", "/api/v2/torrents/info?" + QbitApi.form("hashes" to selected))
        if (check.code in setOf(401, 403)) return DownloadActionResult.NEEDS_SIGN_IN
        if (check.code != 200) return DownloadActionResult.FAILED
        val torrent = QbitApi.torrents(check.body)[selected] ?: return DownloadActionResult.NOT_FOUND
        if (torrent.progress >= 1.0) return DownloadActionResult.NOT_FOUND
        val hashes = QbitApi.form("hashes" to selected)
        if (action == DownloadAction.NOW) {
            return if (call("POST", "/api/v2/torrents/setForceStart", "$hashes&value=true").code == 200)
                DownloadActionResult.DONE else DownloadActionResult.FAILED
        }
        val moved = call("POST", "/api/v2/torrents/topPrio", hashes)
        if (moved.code == 409) return DownloadActionResult.QUEUE_DISABLED
        if (moved.code != 200) return DownloadActionResult.FAILED
        if (torrent.forced && call("POST", "/api/v2/torrents/setForceStart", "$hashes&value=false").code != 200)
            return DownloadActionResult.FAILED
        var resumed = call("POST", "/api/v2/torrents/start", hashes)
        if (resumed.code in setOf(404, 405)) resumed = call("POST", "/api/v2/torrents/resume", hashes)
        return if (resumed.code == 200) DownloadActionResult.DONE else DownloadActionResult.FAILED
    }

    /** User-entered password goes only to the login endpoint; only its session cookie is retained. */
    @Synchronized
    fun signIn(username: String, password: String): Boolean {
        val login = try { transport.call("POST", "/api/v2/auth/login", QbitApi.form("username" to username, "password" to password), null) }
                    catch (_: Exception) { QbitReply(0) }
        if (login.code != 200 || login.body.trim() != "Ok.") return false
        val session = login.cookies.map { it.substringBefore(';').trim() }.firstOrNull { it.startsWith("SID=") } ?: return false
        cookie = session
        rejectedCredentials = false
        onSession(session)
        return true
    }

    private fun call(method: String, path: String, body: String? = null): QbitReply {
        fun send() = try { transport.call(method, path, body, cookie) } catch (_: Exception) { QbitReply(0) }
        var reply = send()
        if (reply.code in setOf(401, 403)) {
            if (rejectedCredentials || config.password.isEmpty()) return reply // masked settings need one-time sign-in
            val login = try { transport.call("POST", "/api/v2/auth/login",
                QbitApi.form("username" to config.username, "password" to config.password), null) } catch (_: Exception) { QbitReply(0) }
            if (login.code != 200 || login.body.trim() != "Ok.") {
                if (login.code in setOf(200, 401, 403)) rejectedCredentials = true
                return reply
            }
            cookie = login.cookies.map { it.substringBefore(';').trim() }.firstOrNull { it.startsWith("SID=") }
            if (cookie == null) return reply
            onSession(cookie!!)
            reply = send()
        }
        return reply
    }

    companion object {
        private fun http(config: QbitConfig) = QbitTransport { method, path, body, cookie ->
            val connection = URL(config.baseUrl + path).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 6000; connection.readTimeout = 15000
            connection.setRequestProperty("Referer", config.baseUrl + "/")
            cookie?.let { connection.setRequestProperty("Cookie", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            try {
                if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val text = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else ""
                QbitReply(code, text, connection.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value })
            } finally { connection.disconnect() }
        }
    }
}
