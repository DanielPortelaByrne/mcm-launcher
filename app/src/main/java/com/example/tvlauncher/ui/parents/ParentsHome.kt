package com.example.tvlauncher.ui.parents

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import com.example.tvlauncher.R
import com.example.tvlauncher.ui.NavTab
import com.example.tvlauncher.data.parents.FeedImages
import com.example.tvlauncher.data.parents.FileFeedStore
import com.example.tvlauncher.data.parents.HttpFeedFetcher
import com.example.tvlauncher.data.parents.ParentFeed
import com.example.tvlauncher.data.parents.ParentFeedRepository
import com.example.tvlauncher.data.parents.ParentsConfig
import com.example.tvlauncher.data.parents.ParentsFormat
import com.example.tvlauncher.data.parents.ParentsWork
import com.example.tvlauncher.ui.room.InfoSheet

/**
 * The parents' home: the family sections laid into the page under the apps, in the order of what matters
 * to them -- Daniel's life, Amélia's corner, a shared evening, Padraig's music and football, crochet,
 * their granddaughter, old photographs. "Coming up" sits in the hero.
 *
 * Content is one cached feed. The page draws from the cache at once (no network on the way to Home),
 * then a background check replaces it only with a newer, valid feed. Sections without content are not
 * on the page at all; there are no error boxes.
 */
class ParentsHome(private val activity: Activity, sheet: InfoSheet, root: FrameLayout) {
    private val config = ParentsConfig(activity)
    @Volatile private var feedUrl: String? = null
    private val repository = ParentFeedRepository(FileFeedStore(activity), HttpFeedFetcher, { feedUrl })
    private val images = FeedImages(activity)
    private val viewer = PhotoViewer(activity, root, images)
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var closed = false

    private val comingUp = ComingUpCard(activity, sheet)
    private val daniel = DanielLatelySection(activity, images, viewer)
    private val heroPicks = HeroPicks(activity, images)
    private val amelia = AmeliaSection(activity, images)
    private val tonight = TonightAtHomeSection(activity, images, sheet)
    private val listening = ListeningRoomSection(activity, images)
    private val match = MatchProgrammeSection(activity, sheet)
    private val crochet = CrochetSection(activity, images, viewer)
    private val aria = AriaSection(activity, images)
    private val archive = FamilyArchiveSection(activity, images, viewer)
    private val seanti = SeantiSection(activity, images)

    private val ordered get() = listOf(daniel.section, listening.section, amelia.section, match.section, tonight.section, aria.section, crochet.section, archive.section, seanti.section)

    val overlayVisible: Boolean get() = viewer.isVisible

    /** The header's family tabs, and the sections they lead to. */
    private val places get() = mapOf(NavTab.DANIEL to daniel.section, NavTab.PADRAIG to listening.section, NavTab.AMELIA to amelia.section)

    /** True when the tab has somewhere to go (its section has content). */
    fun hasPlace(tab: NavTab): Boolean = places[tab]?.shown == true

    /** The tab whose section the page is showing at [scrollY], or HOME above them all. */
    fun placeAt(scrollY: Int, reach: Int): NavTab {
        var at = NavTab.HOME
        for ((tab, s) in places.entries.sortedBy { it.value.view.top }) {
            if (s.shown && pageTop(s.view) <= scrollY + reach) at = tab
        }
        return at
    }

    /** Pans the page so [tab]'s section sits just under the header, and puts focus on its first thing. */
    fun jumpTo(tab: NavTab, scroll: com.example.tvlauncher.ui.CalmScrollView, headerSpace: Int): Boolean {
        val s = places[tab]?.takeIf { it.shown } ?: return false
        com.example.tvlauncher.design.Motion.scrollVerticalTo(scroll, pageTop(s.view) - headerSpace)
        // Focus after the pan has started, so the focus listener's own scroll does not fight it.
        s.view.postDelayed({ firstFocusable(s.view)?.requestFocus() }, 60)
        return true
    }

    private fun pageTop(v: View): Int {
        var y = 0; var p: View? = v
        while (p != null && p.id != R.id.homeScroll) { y += p.top; p = p.parent as? View }
        return y
    }

    private fun firstFocusable(v: View): View? = v.getFocusables(View.FOCUS_DOWN).firstOrNull { it.isShown }

    init {
        val hero = activity.findViewById<FrameLayout>(R.id.homeHero)
        hero.addView(comingUp.view, FrameLayout.LayoutParams(Kit.dp(activity, 408), -2, Gravity.END or Gravity.CENTER_VERTICAL))
        hero.addView(heroPicks.view, FrameLayout.LayoutParams(Kit.dp(activity, 408), -2, Gravity.END or Gravity.CENTER_VERTICAL))
        // Order of what matters most to them: Daniel's life, then Padraig's music (he looks for it first),
        // then Amélia's programmes, the football, a shared film, Ária, crochet, old photographs.
        val sections = activity.findViewById<LinearLayout>(R.id.discoverSections)
        ordered.forEachIndexed { i, s -> sections.addView(s.view, i, LinearLayout.LayoutParams(-1, -2)) }
        // "More apps" (every other installed app, mostly Amazon's) moves below the family sections, so the
        // first scroll down reaches Daniel's photos rather than a row of app shortcuts.
        activity.findViewById<View>(R.id.featuredSection)?.let { more ->
            (more.parent as? android.view.ViewGroup)?.removeView(more)
            sections.addView(more, ordered.size)
        }
        greet()
        ParentsWork.executor.execute {
            feedUrl = config.feedUrl()
            val cached = repository.load()
            Log.i(TAG, "Cached feed: ${cached?.updatedAt ?: "none"}; feed ${if (feedUrl != null) "configured" else "not configured"}")
            main.post { if (!closed) render(cached) }
            check(force = config.justProvisioned.also { config.justProvisioned = false })
        }
    }

    fun resume() {
        resumed = true
        greet()
        render(repository.cached)
        ParentsWork.executor.execute {
            feedUrl = config.feedUrl() ?: feedUrl
            check(force = config.justProvisioned.also { config.justProvisioned = false })
        }
        main.removeCallbacks(periodic); main.postDelayed(periodic, PERIOD_MS)
    }

    fun pause() { resumed = false; main.removeCallbacks(periodic) }

    /** Back: closes the photo viewer if it is up. */
    fun onBack(): Boolean { if (!viewer.isVisible) return false; viewer.hide(); return true }

    fun onHome() { if (viewer.isVisible) viewer.hide() }

    fun close() { closed = true; main.removeCallbacksAndMessages(null); viewer.close(); images.close() }

    private val periodic = object : Runnable {
        override fun run() {
            if (!resumed || closed) return
            ParentsWork.executor.execute { check(force = false) }
            main.postDelayed(this, PERIOD_MS)
        }
    }

    /** Worker thread. */
    private fun check(force: Boolean) {
        val outcome = repository.refresh(force = force, minIntervalMs = MIN_CHECK_MS)
        if (outcome != ParentFeedRepository.Outcome.SKIPPED) Log.i(TAG, "Feed check: $outcome (feed of ${repository.cached?.updatedAt ?: "none"})")
        if (outcome == ParentFeedRepository.Outcome.UPDATED) main.post { if (!closed) render(repository.cached) }
    }

    private fun render(feed: ParentFeed?) {
        val now = System.currentTimeMillis()
        comingUp.bind(feed?.comingUp.orEmpty(), now)
        heroPicks.bind(if (comingUp.view.visibility == View.VISIBLE || feed == null) emptyList() else heroCards(feed, now))
        daniel.bind(feed?.danielLately, feedUrl != null && feed != null, now)
        amelia.bind(feed?.forAmelia)
        tonight.bind(feed?.tonight.orEmpty())
        listening.bind(feed?.listening)
        match.bind(feed?.sport, now)
        crochet.bind(feed?.crochet.orEmpty())
        aria.bind(feed?.aria.orEmpty())
        archive.bind(feed?.familyArchive.orEmpty(), now)
        seanti.bind(feed?.localEvents.orEmpty(), now)
        if (feed != null) keepImages(feed)
        onRendered?.invoke()
    }

    /** Called after each render, so the header can show only the family tabs that lead somewhere. */
    var onRendered: (() -> Unit)? = null

    /**
     * Amélia's card is her own programme (the newest Domingo Legal), or Brazilian TV live; Padraig's is a
     * record for the day, turning over daily through his shelf.
     */
    private fun heroCards(feed: ParentFeed, now: Long): List<HeroPicks.Card> {
        val forHer = feed.forAmelia?.shows?.firstOrNull()?.let { HeroPicks.Card("Para Amélia", it, it.title, it.subtitle, false) }
            ?: feed.forAmelia?.live?.firstOrNull()?.let { HeroPicks.Card("Para Amélia", it, "${it.title} ao vivo", "Agora, do Brasil", false) }
        val records = feed.listening?.records.orEmpty()
        val forHim = records.takeIf { it.isNotEmpty() }?.let { it[Math.floorMod(ParentsFormat.dayIndex(now), it.size)] }
            ?.let { HeroPicks.Card("For Padraig", it, it.title, "Put on a record · Spotify", true) }
        return listOfNotNull(forHer, forHim)
    }

    private var keptFor: String? = null

    /** Downloads the photos the page and viewer will want (for offline), and forgets ones no longer in the feed. */
    private fun keepImages(feed: ParentFeed) {
        if (keptFor == feed.updatedAt) return
        keptFor = feed.updatedAt
        val photos = feed.danielLately.take(12)
        images.prefetch(photos.map { it.thumbUrl } + photos.take(4).map { it.imageUrl })
        val wanted = feed.danielLately.flatMap { listOf(it.thumbUrl, it.imageUrl) } + feed.familyArchive.flatMap { listOf(it.thumbUrl, it.imageUrl) } +
            listOfNotNull(feed.forAmelia?.film?.image) + feed.forAmelia?.live.orEmpty().mapNotNull { it.image } + feed.forAmelia?.novelas.orEmpty().mapNotNull { it.image } +
            feed.tonight.mapNotNull { it.image } + feed.listening?.records.orEmpty().mapNotNull { it.image } + listOfNotNull(feed.listening?.concert?.image) +
            feed.crochet.map { it.image } + feed.aria.mapNotNull { it.image } + feed.localEvents.mapNotNull { it.image }
        images.prune(wanted)
    }

    /** The hero's eyebrow is today's date; the greeting itself is fixed in strings.xml. */
    private fun greet() {
        val now = System.currentTimeMillis()
        activity.findViewById<TextView>(R.id.heroEyebrow)?.text =
            java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.UK).apply { timeZone = ParentsFormat.IRELAND }.format(java.util.Date(now))
    }

    private companion object {
        const val TAG = "ParentFeed"
        const val PERIOD_MS = 30 * 60_000L
        const val MIN_CHECK_MS = 5 * 60_000L
    }
}
