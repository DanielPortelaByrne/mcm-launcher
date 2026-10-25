package com.example.tvlauncher

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import com.example.tvlauncher.design.cinema.PosterCard
import com.example.tvlauncher.ui.AccountMenu
import com.example.tvlauncher.ui.MoviesPanel
import com.example.tvlauncher.ui.WhoPicker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Foreground startup, account input and horizontal-row regression benchmark. No account selection or playback. */
class LauncherPerformanceInstrumentation : Instrumentation() {
    @Volatile private var activity: MainActivity? = null
    @Volatile private var sampling = true
    private val samples = ConcurrentHashMap<String, Int>()
    private var createMs = 0L
    private var resumeMs = 0L
    private var label = "before"

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); label = arguments?.getString("label") ?: "before"; start() }
    override fun callActivityOnCreate(a: Activity, state: Bundle?) {
        val at = SystemClock.elapsedRealtime()
        super.callActivityOnCreate(a, state)
        if (a is MainActivity) createMs = SystemClock.elapsedRealtime() - at
    }
    override fun callActivityOnResume(a: Activity) {
        val at = SystemClock.elapsedRealtime()
        super.callActivityOnResume(a)
        if (a is MainActivity) { resumeMs = SystemClock.elapsedRealtime() - at; activity = a }
    }

    override fun onStart() {
        val output = Bundle()
        val started = SystemClock.elapsedRealtime()
        Thread({
            val main = android.os.Looper.getMainLooper().thread
            while (sampling) {
                val stack = main.stackTrace
                val relevant = stack.firstOrNull { it.className.startsWith("com.example.tvlauncher") }
                if (relevant != null) {
                    val native = stack.firstOrNull { it.isNativeMethod }
                    val key = "${relevant.className}.${relevant.methodName}" + (native?.let { " / ${it.className}.${it.methodName}" } ?: "")
                    samples.merge(key, 1, Int::plus)
                }
                SystemClock.sleep(40)
            }
        }, "launcher-main-sampler").apply { isDaemon = true; start() }
        try {
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitFor(30000, "Launcher did not resume") { activity != null }
            val a = checkNotNull(activity)
            waitFor(20000, "Launcher is not in the foreground") { ui { a.hasWindowFocus() } }
            val readyMs = SystemClock.elapsedRealtime() - started
            val picker = ui { field<WhoPicker>(a, "whoPicker") }
            ui { picker.hide() }
            waitFor(20000, "App shelf did not load") { ui { a.findViewById<ViewGroup>(R.id.shelfContainer).childCount > 1 } }
            val shelfMs = SystemClock.elapsedRealtime() - started
            SystemClock.sleep(500) // Let initial shelf/picker focus posts settle before assigning the measured target.
            val menu = ui { field<AccountMenu>(a, "accountMenu") }
            val keys = JSONArray()
            repeat(3) {
                ui { a.findViewById<View>(R.id.iconAccounts).requestFocus() }
                waitFor(3000, "Profile icon could not receive focus") { ui { a.findViewById<View>(R.id.iconAccounts).hasFocus() } }
                measureKey("avatar-select", KeyEvent.KEYCODE_DPAD_CENTER, keys) { ui { menu.isVisible } }
                measureKey("account-back", KeyEvent.KEYCODE_BACK, keys) { ui { !menu.isVisible } }
                val at = SystemClock.elapsedRealtime()
                ui { picker.show() }
                keys.put(JSONObject().put("action", "show-profile-picker").put("ms", SystemClock.elapsedRealtime() - at))
                ui { descendants(a.findViewById(R.id.whoPicker)).first { it.isFocusable && it.isClickable }.requestFocus() }
                val old = ui { a.currentFocus }
                measureKey("profile-right", KeyEvent.KEYCODE_DPAD_RIGHT, keys) { ui { a.currentFocus !== old } }
                measureKey("profile-back", KeyEvent.KEYCODE_BACK, keys) { ui { !picker.isVisible } }
            }
            sampling = false
            val homeViews = ui { descendants(a.findViewById(R.id.homeScroll)).size }
            val panel = ui { field<MoviesPanel>(a, "moviesPanel") }
            ui { panel.show(); panel.debugGo("letterboxd") }
            waitFor(30000, "Recently watched row did not load") { ui {
                descendants(field<ViewGroup>(panel, "content")).filterIsInstance<PosterCard>().size >= 8
            } }
            val page = ui { field<ScrollView>(panel, "page") }
            val first = ui { descendants(field<ViewGroup>(panel, "content")).filterIsInstance<PosterCard>().first() }
            ui { first.requestFocus() }
            SystemClock.sleep(1000)
            val vertical = ArrayList<Int>()
            var moves = 0
            val observer = android.view.ViewTreeObserver.OnScrollChangedListener { vertical.add(page.scrollY) }
            ui { vertical.add(page.scrollY); page.viewTreeObserver.addOnScrollChangedListener(observer) }
            repeat(2) { direction -> repeat(5) {
                val old = ui { a.currentFocus }
                sendKeyDownUpSync(if (direction == 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
                SystemClock.sleep(350)
                ui { if (a.currentFocus !== old) moves++; vertical.add(page.scrollY) }
            } }
            ui { page.viewTreeObserver.removeOnScrollChangedListener(observer) }
            val range = (vertical.maxOrNull() ?: 0) - (vertical.minOrNull() ?: 0)
            val report = JSONObject().put("label", label).put("activityCreateMs", createMs).put("activityResumeMs", resumeMs)
                .put("foregroundReadyMs", readyMs).put("shelfReadyMs", shelfMs).put("homeViewCount", homeViews)
                .put("keys", keys).put("horizontalMoves", moves).put("rowVerticalRangePx", range)
                .put("rowVerticalPositions", JSONArray(vertical))
                .put("mainThreadSamples", JSONArray(samples.entries.sortedByDescending { it.value }.take(12).map { "${it.value}: ${it.key}" }))
            File(targetContext.filesDir, "launcher-performance-$label.json").writeText(report.toString(2))
            uiAutomation.takeScreenshot()?.let { b -> File(targetContext.filesDir, "launcher-performance-$label.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; b.recycle() }
            check(ui { a.hasWindowFocus() }) { "Launcher lost foreground focus" }
            check(moves >= 8) { "Horizontal input did not move through the row" }
            if (label != "before") {
                check(range <= 2) { "Horizontal row movement scrolled the page by $range pixels" }
                check((0 until keys.length()).all { keys.getJSONObject(it).getLong("ms") < 1000 }) { "Profile input stalled for over a second" }
            }
            output.putString("result", report.toString())
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            File(targetContext.filesDir, "launcher-performance-$label-failure.json").writeText(JSONObject()
                .put("createMs", createMs).put("resumeMs", resumeMs).put("error", e.message)
                .put("samples", JSONArray(samples.entries.sortedByDescending { it.value }.take(15).map { "${it.value}: ${it.key}" })).toString(2))
            output.putString("result", "FAIL: ${e.javaClass.simpleName}: ${e.message}")
            finish(Activity.RESULT_CANCELED, output)
        } finally { sampling = false }
    }

    private fun measureKey(name: String, code: Int, into: JSONArray, ready: () -> Boolean) {
        val start = SystemClock.elapsedRealtime()
        sendKeyDownUpSync(code)
        waitFor(10000, "$name did not respond", ready)
        into.put(JSONObject().put("action", name).put("ms", SystemClock.elapsedRealtime() - start))
        SystemClock.sleep(120)
    }
    private fun waitFor(timeout: Long, message: String, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!ready()) { check(SystemClock.elapsedRealtime() < end) { message }; SystemClock.sleep(25) }
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun <T> field(o: Any, name: String): T {
        @Suppress("UNCHECKED_CAST")
        return o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T
    }
    private fun <T> ui(block: () -> T): T {
        val result = AtomicReference<T>()
        runOnMainSync { result.set(block()) }
        return result.get()
    }
}
