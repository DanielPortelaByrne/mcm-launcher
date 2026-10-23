package com.example.tvlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.example.tvlauncher.design.cinema.MemberCard
import com.example.tvlauncher.ui.MoviesPanel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** On-device navigation benchmark; never clears caches, accounts or watch history. */
class LetterboxdPerformanceInstrumentation : Instrumentation() {
    private var label = "run"
    private var smoke = false
    @Volatile private var running: MainActivity? = null
    override fun callActivityOnResume(activity: Activity) {
        super.callActivityOnResume(activity)
        if (activity is MainActivity) running = activity
    }
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        label = arguments?.getString("label") ?: "run"
        smoke = arguments?.getString("smoke") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = SystemClock.elapsedRealtime() + 20000
            while (running == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            val activity = checkNotNull(running) { "Launcher did not resume" }
            while (!ui { activity.hasWindowFocus() } && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            check(ui { activity.hasWindowFocus() }) { "Launcher is obscured by another app or the TV screensaver" }
            val panel = ui { field<MoviesPanel>(activity, "moviesPanel") }
            ui { panel.show() }
            SystemClock.sleep(1500)
            if (smoke) {
                val screens = smokeProfiles(panel)
                result.putString("result", "PASS: $screens")
                finish(Activity.RESULT_OK, result)
                return
            }
            val frameMs = ArrayList<Long>()
            val frameListener = android.view.Window.OnFrameMetricsAvailableListener { _, frame, _ ->
                frameMs.add(frame.getMetric(android.view.FrameMetrics.TOTAL_DURATION) / 1_000_000)
            }
            ui { activity.window.addOnFrameMetricsAvailableListener(frameListener, android.os.Handler(android.os.Looper.getMainLooper())) }
            val runs = JSONArray()
            repeat(3) { iteration ->
                check(ui { activity.hasWindowFocus() }) { "Launcher lost foreground focus; measurement is invalid" }
                ui { panel.debugGo("lb-search") }
                SystemClock.sleep(400)
                val start = SystemClock.elapsedRealtime()
                var membersAt: Long? = null
                var picturesAt: Long? = null
                var rowDataAt: Long? = null
                var rowPicturesAt: Long? = null
                var members = 0
                ui { panel.debugGo("letterboxd") }
                val buildMs = SystemClock.elapsedRealtime() - start
                while (SystemClock.elapsedRealtime() - start < 30000) {
                    val ready = ui {
                        val cards = descendants(field<ViewGroup>(panel, "content")).filterIsInstance<MemberCard>()
                        val posters = descendants(field<ViewGroup>(panel, "content"))
                            .filterIsInstance<com.example.tvlauncher.design.cinema.PosterCard>().take(6)
                        val now = SystemClock.elapsedRealtime() - start
                        if (posters.size == 6 && rowDataAt == null) rowDataAt = now
                        if (posters.size == 6 && posters.all { it.image.drawable != null } && rowPicturesAt == null) rowPicturesAt = now
                        members = cards.size
                        val bound = cards.isNotEmpty() && cards.all { c ->
                            descendants(c).filterIsInstance<android.widget.TextView>().any { it.text.contains("films") }
                        }
                        val pictures = bound && cards.all { c ->
                            c.avatar.image.drawable != null && c.favs.all { it.drawable != null }
                        }
                        bound to pictures
                    }
                    val elapsed = SystemClock.elapsedRealtime() - start
                    if (ready.first && membersAt == null) membersAt = elapsed
                    if (ready.second && picturesAt == null) picturesAt = elapsed
                    if (picturesAt != null && rowPicturesAt != null) break
                    SystemClock.sleep(50)
                }
                val run = JSONObject().put("iteration", iteration + 1).put("buildMs", buildMs)
                    .put("members", members).put("memberDataMs", membersAt ?: -1)
                    .put("memberArtworkMs", picturesAt ?: -1)
                    .put("firstRowDataMs", rowDataAt ?: -1).put("firstRowArtworkMs", rowPicturesAt ?: -1)
                runs.put(run)
                android.util.Log.i("LbBenchmark", run.toString())
                check(picturesAt != null && rowPicturesAt != null) { "Letterboxd content did not finish loading: $run" }
                if (label == "after" && iteration > 0) check(picturesAt < 2500 && rowPicturesAt < 2500) { "Repeat open exceeded the 2.5 second budget: $run" }
                SystemClock.sleep(1000)
            }
            ui { activity.window.removeOnFrameMetricsAvailableListener(frameListener) }
            check(ui { activity.hasWindowFocus() } && frameMs.isNotEmpty()) { "No foreground frames were captured; measurement is invalid" }
            val sortedFrames = frameMs.sorted()
            val report = JSONObject().put("label", label).put("runs", runs)
                .put("frames", sortedFrames.size).put("framesOver100Ms", sortedFrames.count { it > 100 })
                .put("frameP95Ms", sortedFrames.getOrNull((sortedFrames.size * 0.95).toInt()) ?: -1)
            if (label != "before") ui { verifyAvatarRings() }
            ui { descendants(field<ViewGroup>(panel, "content")).filterIsInstance<MemberCard>().firstOrNull()?.requestFocus() }
            SystemClock.sleep(500)
            uiAutomation.takeScreenshot()?.let { bitmap ->
                File(targetContext.filesDir, "letterboxd-benchmark-$label.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            File(targetContext.filesDir, "letterboxd-benchmark-$label.json").writeText(report.toString(2))
            result.putString("result", report.toString())
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("result", "FAIL: ${e.javaClass.simpleName}: ${e.message}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun <T> field(o: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T
    }
    private fun <T> ui(block: () -> T): T {
        val value = AtomicReference<T>()
        runOnMainSync { value.set(block()) }
        return value.get()
    }

    private fun smokeProfiles(panel: MoviesPanel): String {
        val results = ArrayList<String>()
        for (user in listOf("evacawley", "DanielPortelaB")) {
            ui { panel.debugGo("lb-person:$user") }
            for (tab in listOf("Watchlist", "Liked", "Diary")) {
                val start = SystemClock.elapsedRealtime()
                ui {
                    descendants(field<ViewGroup>(panel, "content"))
                        .first { it.isFocusable && it.contentDescription?.toString() == tab }.requestFocus()
                }
                var loaded = false
                while (SystemClock.elapsedRealtime() - start < 30000) {
                    loaded = ui {
                        val views = descendants(field<ViewGroup>(panel, "content"))
                        views.filterIsInstance<com.example.tvlauncher.design.cinema.PosterCard>().any { it.image.drawable != null } &&
                            views.filterIsInstance<android.widget.TextView>().none { it.text.toString() == "Loading…" }
                    }
                    if (loaded) break
                    SystemClock.sleep(50)
                }
                check(loaded) { "$user $tab did not load" }
                results += "$user/$tab=${SystemClock.elapsedRealtime() - start}ms"
            }
        }
        ui { panel.debugGo("letterboxd") }
        return results.joinToString(", ")
    }

    /** Draw through the real padded MemberCard parents; every part of the outer ring must survive clipping. */
    private fun verifyAvatarRings() {
        val card = MemberCard(targetContext)
        val w = com.example.tvlauncher.design.Cinema.px(targetContext, 600)
        val h = com.example.tvlauncher.design.Cinema.px(targetContext, 300)
        card.avatar.set("Test", null)
        card.avatar.ringed = true
        card.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        card.layout(0, 0, w, h)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        card.draw(android.graphics.Canvas(bitmap))
        val bounds = android.graphics.Rect(0, 0, card.avatar.width, card.avatar.height)
        card.offsetDescendantRectToMyCoords(card.avatar, bounds)
        val radius = card.avatar.width / 2f + com.example.tvlauncher.design.Cinema.pxf(targetContext, 10)
        val expected = com.example.tvlauncher.design.Cinema.STRIPE_TERRA
        for (angle in 0 until 360 step 10) {
            val radians = Math.toRadians(angle.toDouble())
            val x = (bounds.exactCenterX() + radius * kotlin.math.cos(radians)).toInt()
            val y = (bounds.exactCenterY() + radius * kotlin.math.sin(radians)).toInt()
            val actual = bitmap.getPixel(x, y)
            val difference = kotlin.math.abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected)) +
                kotlin.math.abs(android.graphics.Color.green(actual) - android.graphics.Color.green(expected)) +
                kotlin.math.abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected))
            check(difference < 60) { "Profile ring clipped at $angle degrees" }
        }
        bitmap.recycle()
    }
}
