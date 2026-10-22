package com.example.tvlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.tvlauncher.data.jellyfin.JellyfinApi
import com.example.tvlauncher.data.jellyfin.JellyfinClient
import com.example.tvlauncher.data.jellyfin.JellyfinReport
import com.example.tvlauncher.ui.PlayerActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit upstairs audio smoke test. Restores watch state and local history; never logs credentials. */
@UnstableApi
class AudioPlaybackInstrumentation : Instrumentation() {
    private lateinit var options: Bundle
    override fun onCreate(arguments: Bundle?) { options = arguments ?: Bundle(); super.onCreate(arguments); start() }

    override fun onStart() {
        val output = Bundle()
        try {
            output.putString("result", verify())
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("result", "FAIL: ${error.javaClass.simpleName}: ${error.message?.take(160)}")
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun verify(): String {
        val id = options.getString("itemId") ?: error("itemId is required")
        val expectConversion = options.getString("expectConversion") != "false"
        val client = JellyfinClient(targetContext)
        val item = client.item(id) ?: error("Item unavailable")
        val prefs = targetContext.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
        val uid = prefs.getString("userId", null) ?: error("No signed-in user")
        val base = JellyfinApi.cleanBase(client.server)
        val headers = client.playbackHeaders()
        fun data(body: JSONObject? = null): JSONObject {
            val connection = URL("$base/UserItems/$id/UserData?userId=$uid").openConnection() as HttpURLConnection
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                check(connection.responseCode in 200..299) { "User-state API HTTP ${connection.responseCode}" }
                return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally { connection.disconnect() }
        }
        val originalData = data()
        val localPrefs = listOf("resume_positions", "continue_watching", "playback_history", "network_estimate").associateWith {
            targetContext.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap()
        }
        val artDir = File(targetContext.filesDir, "resume_art")
        val artwork = artDir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
        var activity: PlayerActivity? = null
        try {
            fun source(url: String) = JSONObject().put("provider", "Jellyfin").put("url", url)
                .put("headers", JSONObject(headers)).put("res", "UNKNOWN").put("codec", "UNKNOWN")
                .put("src", "UNKNOWN").put("label", "Audio verification")
            val sources = JSONArray().put(source(client.streamUrl(item))).put(source(client.transcodeUrl(item)))
            val report = JellyfinReport(base, id, item.sourceId, item.resumeMs)
            activity = startActivitySync(Intent(targetContext, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("items", sources.toString()).putExtra("title", "Audio verification")
                .putExtra("imdb", "jf:$id").putExtra("jf", report.toJson())) as PlayerActivity
            val running = activity
            waitUntil(45_000, "No stereo audio and moving picture") {
                ui {
                    val player = field<ExoPlayer>(running, "player")
                    field<Boolean>(running, "started") && player.isPlaying &&
                        player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO) && player.audioFormat?.channelCount == 2
                }
            }
            val firstPosition = ui { field<Player>(running, "player").currentPosition }
            SystemClock.sleep(6000)
            val result = ui {
                val player = field<ExoPlayer>(running, "player")
                check(player.isPlaying && player.currentPosition > firstPosition + 3000) { "Playback stalled" }
                val converted = field<Int>(running, "index") > 0
                check(converted == expectConversion) { "Unexpected conversion decision" }
                JSONObject().put("result", "PASS").put("mime", player.audioFormat?.sampleMimeType)
                    .put("channels", player.audioFormat?.channelCount).put("convertedAudio", converted)
                    .put("pictureRendered", field<Boolean>(running, "started"))
                    .put("advancedMs", player.currentPosition - firstPosition)
            }
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.filesDir, "audio-verification.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            File(targetContext.filesDir, "audio-verification-result.json").writeText(result.toString())
            return result.toString()
        } finally {
            activity?.let { running ->
                ui { running.finish() }
                waitUntil(10000, "Player did not close") { running.isDestroyed }
                val lazyReporter = field<Lazy<*>>(running, "reporter\$delegate")
                if (lazyReporter.isInitialized()) (lazyReporter.value as ExecutorService).awaitTermination(25, TimeUnit.SECONDS)
            }
            if (!originalData.has("LastPlayedDate")) originalData.put("LastPlayedDate", JSONObject.NULL)
            data(originalData)
            val restored = data()
            for (key in listOf("Played", "PlaybackPositionTicks", "PlayCount", "IsFavorite")) {
                check(restored.opt(key) == originalData.opt(key)) { "Could not restore $key" }
            }
            localPrefs.forEach { (name, values) ->
                val edit = targetContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Long -> edit.putLong(key, value)
                    is Int -> edit.putInt(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Float -> edit.putFloat(key, value)
                } }
                edit.commit()
            }
            artDir.listFiles().orEmpty().filter { it.name !in artwork }.forEach { it.delete() }
            artwork.forEach { (name, bytes) -> File(artDir, name).writeBytes(bytes) }
        }
    }

    private fun <T> field(target: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) as T
    }

    private fun <T> ui(block: () -> T): T {
        val result = AtomicReference<T>()
        runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun waitUntil(timeoutMs: Long, message: String, ready: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (!ready()) {
            check(SystemClock.elapsedRealtime() < until) { message }
            SystemClock.sleep(50)
        }
    }
}
