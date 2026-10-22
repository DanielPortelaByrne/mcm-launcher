package com.example.tvlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.tvlauncher.data.jellyfin.JellyfinApi
import com.example.tvlauncher.data.jellyfin.JellyfinClient
import com.example.tvlauncher.data.jellyfin.JellyfinItem
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

/**
 * Explicit on-device smoke test, packaged only in the separate test APK.
 * Uses the TV's existing session in memory, restores watched/resume data and
 * local Continue watching, and never prints session headers or payloads.
 * Run only on an idle TV; plays the final second of two library episodes.
 */
@UnstableApi
class AutoplayPlaybackInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val output = Bundle()
        try {
            verify()
            output.putString("result", "PASS: autoplay, countdown card, Back cancellation, and state restoration")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("result", "FAIL: ${error.javaClass.simpleName}: ${error.message?.take(180)}")
            finish(Activity.RESULT_CANCELED, output)
        }
    }

    private fun verify() {
        val client = JellyfinClient(targetContext)
        check(client.isConnected) { "TV is not signed in" }
        val series = client.library().firstOrNull { it.isSeries && it.name == "Rick and Morty" }
            ?: error("Test series is unavailable")
        val episodes = client.episodes(series.id)
        val first = episodes.firstOrNull { it.season == 1 && it.episode == 1 } ?: error("Test episode is unavailable")
        val second = JellyfinApi.followingEpisode(first, episodes) ?: error("Second test episode is unavailable")
        check(JellyfinApi.followingEpisode(second, episodes) != null) { "Third episode is needed to test cancellation" }
        val prefs = targetContext.getSharedPreferences("jellyfin", Context.MODE_PRIVATE)
        val uid = prefs.getString("userId", null) ?: error("No signed-in user")
        val base = JellyfinApi.cleanBase(client.server)
        val headers = client.playbackHeaders()
        fun data(item: JellyfinItem, body: JSONObject? = null): JSONObject {
            val c = URL("$base/UserItems/${item.id}/UserData?userId=$uid").openConnection() as HttpURLConnection
            c.requestMethod = if (body == null) "GET" else "POST"
            c.connectTimeout = 5000; c.readTimeout = 5000
            headers.forEach { (key, value) -> c.setRequestProperty(key, value) }
            try {
                if (body != null) {
                    c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                check(c.responseCode in 200..299) { "User-state API returned HTTP ${c.responseCode}" }
                return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } finally { c.disconnect() }
        }
        val originalData = listOf(first, second).associateWith { data(it) }
        val localPrefs = listOf("resume_positions", "continue_watching").associateWith {
            targetContext.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap()
        }
        val artDir = File(targetContext.filesDir, "resume_art")
        val artwork = artDir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes() }
        var activity: PlayerActivity? = null
        try {
            val source = JSONObject().put("provider", "Jellyfin").put("url", client.streamUrl(first))
                .put("headers", JSONObject(headers)).put("res", "UNKNOWN").put("codec", "UNKNOWN")
                .put("src", "UNKNOWN").put("label", "Original file")
            val report = JellyfinReport(base, first.id, first.sourceId, 0)
            activity = startActivitySync(Intent(targetContext, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("items", JSONArray().put(source).toString()).putExtra("title", "Autoplay verification")
                .putExtra("imdb", "jf:${first.id}").putExtra("jf", report.toJson())) as PlayerActivity
            val running = activity
            waitUntil(35_000, "Initial episode did not start or next episode was not prepared") {
                ui { field<Boolean>(running, "started") && field<Any?>(running, "nextPlan") != null }
            }
            val player = ui { field<Player>(running, "player") }
            ui { player.seekTo((player.duration - 800).coerceAtLeast(0)); player.play() }
            waitUntil(15_000, "End card did not appear") { ui { field<View>(running, "nextCard").visibility == View.VISIBLE } }
            val endAt = SystemClock.elapsedRealtime()
            check(ui { field<View>(field<View>(running, "nextCard"), "primary").hasFocus() }) { "Play next did not receive remote focus" }
            SystemClock.sleep(650)
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.filesDir, "autoplay-card.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            waitUntil(12_000, "Next episode did not start") {
                ui { field<JellyfinReport>(running, "jf").itemId == second.id && field<Boolean>(running, "started") }
            }
            val transitionMs = SystemClock.elapsedRealtime() - endAt
            check(transitionMs >= 2_500) { "Autoplay skipped its countdown" }
            check(transitionMs < 9_000) { "Autoplay transition took too long" }
            check(ui { field<Player>(running, "player") === player }) { "Autoplay replaced the player" }
            waitUntil(10_000, "Jellyfin did not record completion of the previous episode") { data(first).optBoolean("Played") }
            waitUntil(20_000, "Following episode did not prepare") { ui { field<Any?>(running, "nextPlan") != null } }
            ui { player.seekTo(player.duration - 800); player.play() }
            waitUntil(15_000, "Second end card did not appear") { ui { field<View>(running, "nextCard").visibility == View.VISIBLE } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            SystemClock.sleep(5_000)
            check(ui { field<JellyfinReport>(running, "jf").itemId == second.id }) { "Back did not cancel autoplay" }
            check(ui { field<View>(running, "nextCard").visibility == View.GONE }) { "Back did not dismiss card" }
            check(!running.isFinishing) { "Back exited instead of cancelling the countdown" }
            // Replaying and leaving during a fresh countdown must never start a hidden episode.
            ui { player.seekTo(player.duration - 1_500); player.play() }
            waitUntil(15_000, "Replay end card did not appear") { ui { field<View>(running, "nextCard").visibility == View.VISIBLE } }
            sendKeyDownUpSync(KeyEvent.KEYCODE_HOME)
            SystemClock.sleep(5_000)
            check(ui { field<JellyfinReport>(running, "jf").itemId == second.id }) { "Background countdown started hidden playback" }
            val result = JSONObject().put("transitionMs", transitionMs).put("backCancelled", true).put("backgroundCancelled", true)
            File(targetContext.filesDir, "autoplay-verification-result.json").writeText(result.toString())
        } finally {
            activity?.let { running ->
                ui { running.finish() }
                waitUntil(10_000, "Player did not close") { running.isDestroyed }
                val lazyReporter = field<Lazy<*>>(running, "reporter\$delegate")
                if (lazyReporter.isInitialized()) (lazyReporter.value as ExecutorService).awaitTermination(25, TimeUnit.SECONDS)
            }
            // Restore only after the final queued Stopped report has drained.
            originalData.forEach { (item, original) ->
                if (!original.has("LastPlayedDate")) original.put("LastPlayedDate", JSONObject.NULL)
                data(item, original)
                val restored = data(item)
                for (key in listOf("Played", "PlaybackPositionTicks", "PlayCount", "IsFavorite")) {
                    check(restored.opt(key) == original.opt(key)) { "Could not restore $key" }
                }
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
            artDir.mkdirs()
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
            SystemClock.sleep(25)
        }
    }
}
