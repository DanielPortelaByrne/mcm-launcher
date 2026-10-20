package com.example.tvlauncher.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.tvlauncher.R
import com.example.tvlauncher.data.jellyfin.JellyfinApi
import com.example.tvlauncher.data.jellyfin.JellyfinClient
import com.example.tvlauncher.data.jellyfin.JellyfinItem
import com.example.tvlauncher.data.movies.Availability
import com.example.tvlauncher.data.movies.Catalog
import com.example.tvlauncher.data.movies.CatalogRepository
import com.example.tvlauncher.data.movies.Genre
import com.example.tvlauncher.data.movies.Kind
import com.example.tvlauncher.data.movies.ByteSize
import com.example.tvlauncher.data.movies.RequestOutcome
import com.example.tvlauncher.data.movies.SeerrRequest
import com.example.tvlauncher.data.movies.Stage
import com.example.tvlauncher.data.movies.SeerrClient
import com.example.tvlauncher.data.movies.Service
import com.example.tvlauncher.data.movies.ShelfSpec
import com.example.tvlauncher.data.movies.Title
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.SectionHeader
import com.example.tvlauncher.design.Type
import com.example.tvlauncher.ui.room.JellyfinConnectSheet
import com.example.tvlauncher.ui.room.SeerrSignInSheet
import java.util.Locale
import java.util.concurrent.Executors

/**
 * MCM Cinema: the Movies page, laid out like a streaming service but dressed like the rest of the room. A wordmark and
 * tabs sit along the top (Home, Films, Series, Documentaries, My library, and search); each tab is a page of poster
 * rows under a featured title. Services and genres have pages of their own. The first row of Home is the owner's
 * Jellyfin library; everything else is what is trending and popular across the big UK services. A poster opens its
 * title page: Watch now when it is in the library, Queue download when it is not.
 */
class MoviesPanel(
    private val activity: Activity,
    private val overlay: View,
    private val body: ViewGroup,
    private val top: LinearLayout,
    private val page: android.widget.ScrollView,
    private val content: LinearLayout,
    private val detail: FrameLayout,
    private val jellyfin: JellyfinClient,
    private val jellyfinSheet: JellyfinConnectSheet,
    private val seerr: SeerrClient,
    private val seerrSheet: SeerrSignInSheet
) {
    /** Where the page is: one of the tabs, or a service or genre opened from Home, or search. */
    private sealed class Dest {
        object Home : Dest()
        object Films : Dest()
        object Series : Dest()
        object Docs : Dest()
        object Library : Dest()
        object Downloads : Dest()
        object Search : Dest()
        class OnService(val service: Service) : Dest()
        class InGenre(val genre: Genre) : Dest()
    }

    /** A poster on the page: the title, and the library copy of it when there is one. */
    private class Entry(val title: Title, val library: JellyfinItem?, val posterHeaders: Map<String, String>)

    /** A heading with its scrolling row. Hidden when the row turns out to be empty. */
    private class Row(val heading: View?, val scroller: View, val inner: LinearLayout) {
        fun hide() { heading?.visibility = View.GONE; scroller.visibility = View.GONE }
    }

    private val worker = Executors.newFixedThreadPool(3)
    private val images = RemoteImages(activity)
    private val catalog = CatalogRepository(activity)
    private val ui = Handler(Looper.getMainLooper())
    private var generation = 0
    private var current: Dest = Dest.Home
    private val trail = ArrayDeque<Dest>()
    private var returnFocus: View? = null
    private val tabs = LinkedHashMap<TextView, Dest>()
    private var topBuilt = false
    private var searchPending: Runnable? = null

    private lateinit var storageView: TextView
    private lateinit var heroImage: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroStreaming: TextView
    private lateinit var heroBody: TextView
    private lateinit var heroButtons: LinearLayout
    private var heroSet = false

    val isVisible: Boolean get() = overlay.visibility == View.VISIBLE
    private val detailOpen: Boolean get() = detail.visibility == View.VISIBLE

    fun show() {
        overlay.visibility = View.VISIBLE
        com.example.tvlauncher.design.Motion.enter(overlay, null, 0f)
        if (!topBuilt) { buildTop(); topBuilt = true }
        trail.clear(); current = Dest.Home
        styleTabs(); build(); loadStorage()
    }

    fun hide() { closeDetail(restoreFocus = false); overlay.visibility = View.GONE; generation++ }
    fun close() { worker.shutdownNow(); images.close() }

    /** Back closes a title's page, then steps back through where the viewer has been, then lets the panel close. */
    fun back(): Boolean {
        if (detailOpen) { closeDetail(restoreFocus = true); return true }
        if (trail.isNotEmpty()) { current = trail.removeLast(); styleTabs(); build(); return true }
        if (current != Dest.Home) { current = Dest.Home; styleTabs(); build(); return true }
        return false
    }

    private fun go(dest: Dest, remember: Boolean = false) {
        if (remember) trail.addLast(current) else trail.clear()
        current = dest; styleTabs(); build()
    }

    // --- The bar along the top ---------------------------------------------------------------------

    private fun buildTop() {
        val margin = px(R.dimen.page_margin)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(margin, dp(14), margin, dp(6))
        top.addView(BrandMark(activity), LinearLayout.LayoutParams(dp(40), dp(40)))
        val word = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        word.addView(TextView(activity).apply { text = "MCM"; textSize = 11f; letterSpacing = 0.34f; setTextColor(activity.getColor(R.color.butter)); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        word.addView(TextView(activity).apply { text = "Cinema"; textSize = 24f; setTextColor(activity.getColor(R.color.ivory)); typeface = Typeface.create("serif", Typeface.NORMAL) })
        top.addView(word, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12); marginEnd = dp(20) })
        listOf("Home" to Dest.Home, "Films" to Dest.Films, "Series" to Dest.Series, "Documentaries" to Dest.Docs, "My library" to Dest.Library, "Downloads" to Dest.Downloads).forEach { (label, dest) ->
            top.addView(tab(label, dest), LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(3) })
        }
        top.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        storageView = TextView(activity).apply { textSize = 11f; gravity = Gravity.END; setLineSpacing(0f, 1.05f); setTextColor(activity.getColor(R.color.text_faint)); visibility = View.GONE }
        top.addView(storageView, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
        top.addView(tab("Search", Dest.Search), LinearLayout.LayoutParams(-2, dp(40)))
    }

    private fun tab(label: String, dest: Dest): TextView {
        val view = Type.text(activity, label, Type.Style.BODY).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true; maxLines = 1; textSize = 15f
            setPadding(dp(11), 0, dp(11), 0); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        tabs[view] = dest
        view.setOnFocusChangeListener { _, _ -> styleTabs() }
        view.setOnClickListener { go(dest) }
        return view
    }

    private fun styleTabs() = tabs.forEach { (v, d) ->
        val selected = d === current
        val radius = dp(20).toFloat()
        v.background = if (v.hasFocus()) LauncherTheme.tileBackgroundFocused(activity, radius) else if (selected) LauncherTheme.selectedPill(activity, radius) else null
        v.setTextColor(activity.getColor(if (v.hasFocus()) R.color.ink else if (selected) R.color.ivory else R.color.ivory_text_dim))
    }

    // --- Building a page ---------------------------------------------------------------------------

    private fun build() {
        val gen = ++generation
        searchPending?.let { ui.removeCallbacks(it) }
        content.removeAllViews(); page.scrollTo(0, 0); heroSet = false
        when (val d = current) {
            Dest.Home -> { buildHero(); libraryRow(gen, "My library", 18); serviceTiles(); genreTiles(); shelves(Catalog.homePage(), gen) }
            Dest.Films -> { buildHero(); shelves(Catalog.filmsPage(), gen) }
            Dest.Series -> { buildHero(); shelves(Catalog.seriesPage(), gen) }
            Dest.Docs -> { buildHero(); shelves(Catalog.documentariesPage(), gen) }
            Dest.Library -> buildLibrary(gen)
            Dest.Downloads -> buildDownloads(gen)
            Dest.Search -> buildSearch()
            is Dest.OnService -> { pageTitle("On ${d.service.label}"); buildHero(); shelves(Catalog.servicePage(d.service), gen) }
            is Dest.InGenre -> { pageTitle(d.genre.label); buildHero(); shelves(Catalog.genrePage(d.genre), gen) }
        }
    }

    private fun pageTitle(text: String) {
        content.addView(Type.text(activity, text, Type.Style.TITLE).apply { textSize = 30f; setPadding(px(R.dimen.page_margin), dp(6), 0, dp(4)) })
    }

    /** Rows of posters, loaded three at a time and filled as each arrives; an empty one hides itself. */
    private fun shelves(specs: List<ShelfSpec>, gen: Int) {
        val rows = specs.map { spec -> spec to row(spec.heading, null).also { it.inner.addView(note("Loading…")) } }
        rows.forEach { (spec, row) ->
            worker.execute {
                val shelf = catalog.shelf(spec)
                activity.runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    val entries = shelf.titles.map { entry(it) }
                    if (entries.isEmpty()) { row.hide(); return@runOnUiThread }
                    row.inner.removeAllViews()
                    val related = entries.map { it.title }
                    entries.forEachIndexed { i, e ->
                        val ranked = spec.ranked && i < 10
                        row.inner.addView(if (ranked) rankCard(i + 1, e, related) else card(e, related), cardParams(i, wide = ranked))
                    }
                    if (!heroSet) entries.firstOrNull { it.title.backdropUrl != null }?.let { setHero(it, related) }
                }
            }
        }
    }

    private fun entry(t: Title): Entry =
        Entry(t, if (jellyfin.isConnected) jellyfin.find(t.imdbId, t.tmdbId, t.kind == Kind.SHOW) else null, emptyMap())

    private fun libraryTitle(item: JellyfinItem): Title {
        val base = JellyfinApi.cleanBase(jellyfin.server)
        return Title(
            key = "jf:" + item.id, kind = if (item.isSeries) Kind.SHOW else Kind.MOVIE, name = item.name, year = item.year,
            overview = item.overview, runtimeMin = item.runtimeMin, genres = item.genres, age = item.officialRating, imdbScore = null,
            posterUrl = JellyfinApi.posterUrl(base, item.id), backdropUrl = if (item.hasBackdrop) jellyfin.backdropUrl(item.id) else null,
            imdbId = item.imdbId, tmdbId = item.tmdbId
        )
    }

    private fun libraryEntries(items: List<JellyfinItem>): List<Entry> = items.map { Entry(libraryTitle(it), it, jellyfin.playbackHeaders()) }

    /** A row of the owner's own titles, or the offer to connect Jellyfin. */
    private fun libraryRow(gen: Int, heading: String, limit: Int) {
        val row = row(heading, "On your Jellyfin")
        if (!jellyfin.isConnected) {
            row.inner.addView(actionCard("Connect Jellyfin", "Sign in to see your own films and series") { jellyfinSheet.show(jellyfin) { if (isVisible) build() } })
            return
        }
        row.inner.addView(note("Loading…"))
        worker.execute {
            val items = jellyfin.library().take(limit)
            activity.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                row.inner.removeAllViews()
                if (items.isEmpty()) { row.inner.addView(note("Nothing in your library yet, or Jellyfin couldn't be reached")); return@runOnUiThread }
                val entries = libraryEntries(items)
                entries.forEachIndexed { i, e -> row.inner.addView(card(e, entries.map { it.title }), cardParams(i)) }
            }
        }
    }

    // --- My library --------------------------------------------------------------------------------

    private fun buildLibrary(gen: Int) {
        val margin = px(R.dimen.page_margin)
        if (!jellyfin.isConnected) {
            pageTitle("My library")
            content.addView(actionCard("Connect Jellyfin", "Sign in to see your own films and series") { jellyfinSheet.show(jellyfin) { if (isVisible) build() } }, LinearLayout.LayoutParams(dp(380), dp(64)).apply { marginStart = margin; topMargin = dp(10) })
            return
        }
        buildHero()
        val holder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        content.addView(holder, LinearLayout.LayoutParams(-1, -2))
        holder.addView(note("Loading your library…").apply { setPadding(margin, dp(10), 0, dp(10)) })
        worker.execute {
            val items = jellyfin.library()
            activity.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                holder.removeAllViews()
                if (items.isEmpty()) { holder.addView(note("Nothing in your library yet, or Jellyfin couldn't be reached").apply { setPadding(margin, dp(10), 0, dp(10)) }); return@runOnUiThread }
                val all = libraryEntries(items)
                all.firstOrNull { it.title.backdropUrl != null }?.let { setHero(it, all.map { e -> e.title }) }
                fun add(heading: String, list: List<Entry>) {
                    if (list.isEmpty()) return
                    val r = row(heading, "${list.size}", into = holder)
                    val related = list.map { it.title }
                    list.forEachIndexed { i, e -> r.inner.addView(card(e, related), cardParams(i)) }
                }
                add("Recently added", all.take(20))
                add("Films", all.filter { it.title.kind == Kind.MOVIE })
                add("Series", all.filter { it.title.kind == Kind.SHOW })
                all.flatMap { e -> e.title.genres.map { g -> g to e } }.groupBy({ it.first }, { it.second })
                    .filterValues { it.size >= 2 }.entries.sortedByDescending { it.value.size }.take(6).forEach { (g, list) -> add(g, list) }
            }
        }
    }

    // --- Downloads ---------------------------------------------------------------------------------

    private val knownTitles = HashMap<Long, Pair<String, String?>>()   // keyed by TMDB id, doubled, plus one for a series

    private fun titleKey(r: SeerrRequest) = r.tmdbId * 2 + if (r.kind == Kind.SHOW) 1 else 0

    private fun buildDownloads(gen: Int) {
        val margin = px(R.dimen.page_margin)
        pageTitle("Downloads")
        content.addView(Type.text(activity, "What you have asked for, and how far along it is", Type.Style.CAPTION).apply { setPadding(margin, 0, 0, dp(6)) })
        if (!seerr.isSignedIn) {
            content.addView(actionCard("Sign in to see your downloads", "Use your Jellyfin account") { seerrSheet.show(seerr, jellyfin) { if (isVisible) build() } },
                LinearLayout.LayoutParams(dp(380), dp(64)).apply { marginStart = margin; topMargin = dp(10) })
            return
        }
        val holder = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        content.addView(holder, LinearLayout.LayoutParams(-1, -2))
        holder.addView(note("Loading…").apply { setPadding(margin, dp(10), 0, 0) })
        var firstLoad = true
        fun refresh() {
            worker.execute {
                val requests = seerr.requests()
                requests.forEach { r -> if (!knownTitles.containsKey(titleKey(r))) seerr.titleInfo(r.kind, r.tmdbId)?.let { knownTitles[titleKey(r)] = it } }
                activity.runOnUiThread {
                    if (gen != generation) return@runOnUiThread
                    val focused = (0 until holder.childCount).firstOrNull { holder.getChildAt(it).hasFocus() }
                    holder.removeAllViews()
                    if (requests.isEmpty()) holder.addView(note("Nothing requested yet. Open any title and press Queue download.").apply { setPadding(margin, dp(10), 0, 0) })
                    requests.forEachIndexed { i, r ->
                        holder.addView(downloadRow(r), LinearLayout.LayoutParams(-1, dp(100)).apply { setMargins(margin, if (i > 0) dp(8) else dp(6), margin, 0) })
                    }
                    if (firstLoad) { firstLoad = false; holder.getChildAt(0)?.let { requestFocusRobust(overlay as ViewGroup, it) } }
                    else focused?.let { holder.getChildAt(it)?.requestFocus() }
                    loadStorage()
                    // Progress moves, so look again every little while for as long as this page is the one on screen.
                    ui.postDelayed({ if (gen == generation && current == Dest.Downloads && !detailOpen) refresh() }, 15_000)
                }
            }
        }
        refresh()
    }

    /** One request: poster, title, what is happening, and a bar for how far it has got. */
    private fun downloadRow(r: SeerrRequest): View {
        val info = knownTitles[titleKey(r)]
        val radius = dp(14).toFloat()
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isFocusable = true; isClickable = true; setPadding(dp(12), dp(8), dp(16), dp(8)) }
        val posterBox = FrameLayout(activity).apply {
            clipToOutline = true; setBackgroundColor(0xFF3A2C1E.toInt())
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(6).toFloat()) } }
        }
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        posterBox.addView(image, FrameLayout.LayoutParams(-1, -1))
        info?.second?.let { u -> images.load(u) { b -> image.setImageBitmap(b) } }
        row.addView(posterBox, LinearLayout.LayoutParams(dp(56), dp(84)))

        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        column.addView(Type.text(activity, info?.first ?: "Title ${r.tmdbId}", Type.Style.BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        column.addView(Type.text(activity, statusLine(r), Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 13f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        val colour = activity.getColor(when (r.stage) { Stage.READY -> R.color.deep_teal; Stage.DECLINED, Stage.FAILED -> R.color.walnut; else -> R.color.terracotta })
        column.addView(ProgressBar(activity, r.progress(), colour), LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) })
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
        r.progress()?.let { p ->
            row.addView(TextView(activity).apply { text = "${Math.round(p * 100)}%"; textSize = 24f; typeface = Typeface.create("serif", Typeface.BOLD); setTextColor(activity.getColor(R.color.ivory)); gravity = Gravity.END }, LinearLayout.LayoutParams(dp(76), -2))
        }
        LauncherTheme.bindPanelFocus(row, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit -> scrollFocusIntoView(row, lit) }
        return row
    }

    private fun statusLine(r: SeerrRequest): String = when (r.stage) {
        Stage.WAITING_APPROVAL -> "Waiting for approval"
        Stage.SEARCHING -> "Looking for a release · nothing downloading yet"
        Stage.DOWNLOADING -> {
            val done = ByteSize.format(r.totalBytes - r.leftBytes) + " of " + ByteSize.format(r.totalBytes)
            val left = r.etaMs()?.let { (it - System.currentTimeMillis()) / 60_000L }
            "Downloading · $done" + when { left == null -> ""; left < 1 -> " · almost done"; left < 120 -> " · about $left min left"; else -> " · about ${left / 60} h left" }
        }
        Stage.READY -> "Ready to watch in your library"
        Stage.DECLINED -> "Declined"
        Stage.FAILED -> "Failed · the download could not be completed"
    }

    /** A thin track with a fill; with no progress to show it is just the track, never a made-up figure. */
    private class ProgressBar(context: Context, private val progress: Float?, private val colour: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = Color.argb(110, 121, 86, 56); canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)   // walnut: shows on dark and on the lit row
            val p = progress ?: return
            if (p > 0f) { paint.color = colour; canvas.drawRoundRect(RectF(0f, 0f, maxOf(height.toFloat(), width * p), height.toFloat()), r, r, paint) }
        }
    }

    // --- Storage -----------------------------------------------------------------------------------

    /** "10.8 GB in library / 601 GB free", small, beside Search. Each half appears only if it could be read. */
    private fun loadStorage() {
        worker.execute {
            val used = jellyfin.takeIf { it.isConnected }?.libraryBytes()
            val free = seerr.takeIf { it.isSignedIn }?.freeSpaceBytes()
            val lines = listOfNotNull(used?.let { "${ByteSize.format(it)} in library" }, free?.let { "${ByteSize.format(it)} free" })
            activity.runOnUiThread { storageView.text = lines.joinToString("\n"); storageView.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE }
        }
    }

    // --- Search ------------------------------------------------------------------------------------

    private fun buildSearch() {
        val margin = px(R.dimen.page_margin)
        val input = EditText(activity).apply {
            hint = "Search films, series and documentaries"; Type.apply(this, Type.Style.BODY); setHintTextColor(activity.getColor(R.color.text_faint))
            inputType = InputType.TYPE_CLASS_TEXT; maxLines = 1; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(22), 0, dp(22), 0)
            background = LauncherTheme.surface(activity, R.color.surface_raised, R.dimen.radius_pill)
            setOnFocusChangeListener { v, f -> v.background = LauncherTheme.surface(activity, if (f) R.color.surface_selected else R.color.surface_raised, R.dimen.radius_pill) }
        }
        content.addView(input, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(margin, dp(10), margin, dp(8)) })
        val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        content.addView(results, LinearLayout.LayoutParams(-1, -2))
        fun prompt(text: String) { results.removeAllViews(); results.addView(note(text).apply { setPadding(margin, dp(10), 0, 0) }) }
        prompt("Type a title, an actor or a theme")

        fun run(query: String) {
            val g = ++generation
            if (query.length < 2) { prompt("Type a title, an actor or a theme"); return }
            prompt("Searching…")
            worker.execute {
                val mine = if (jellyfin.isConnected) jellyfin.library().filter { it.name.contains(query, ignoreCase = true) } else emptyList()
                val found = Catalog.searchSpecs(query).map { catalog.shelf(it) }
                activity.runOnUiThread {
                    if (g != generation) return@runOnUiThread
                    results.removeAllViews()
                    var any = false
                    fun add(heading: String, entries: List<Entry>) {
                        if (entries.isEmpty()) return
                        any = true
                        val r = row(heading, "${entries.size}", into = results)
                        val related = entries.map { it.title }
                        entries.forEachIndexed { i, e -> r.inner.addView(card(e, related), cardParams(i)) }
                    }
                    add("In your library", libraryEntries(mine))
                    found.forEach { s -> add(s.heading, s.titles.map { entry(it) }) }
                    if (!any) prompt("Nothing found for “$query”")
                }
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchPending?.let { ui.removeCallbacks(it) }
                val q = s?.toString()?.trim().orEmpty()
                searchPending = Runnable { run(q) }.also { ui.postDelayed(it, 500) }
            }
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { searchPending?.let { ui.removeCallbacks(it) }; run(input.text.toString().trim()); true } else false
        }
        input.post { input.requestFocus(); (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(input, 0) }
    }

    // --- Featured title ----------------------------------------------------------------------------

    private fun buildHero() {
        val hero = FrameLayout(activity).apply { setBackgroundColor(0xFF1D140D.toInt()) }
        heroImage = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero.addView(heroImage, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2140E09.toInt(), 0xB3140E09.toInt(), 0x00140E09)) }, FrameLayout.LayoutParams(-1, -1))
        hero.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00140E09, 0xFF140E09.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        // Three painted stripes on the hero's lower edge: the room's own mid-century signature.
        hero.addView(Stripes(activity), FrameLayout.LayoutParams(dp(90), dp(8), Gravity.BOTTOM or Gravity.START).apply { leftMargin = px(R.dimen.page_margin); bottomMargin = dp(4) })
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(px(R.dimen.page_margin), 0, 0, dp(22)) }
        column.addView(Type.text(activity, "FEATURED", Type.Style.EYEBROW))
        heroTitle = Type.text(activity, "", Type.Style.TITLE).apply { textSize = 34f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heroMeta = Type.text(activity, "", Type.Style.CAPTION).apply { maxLines = 1 }
        heroStreaming = Type.text(activity, "", Type.Style.CAPTION).apply { maxLines = 1; setTextColor(activity.getColor(R.color.butter)) }
        heroBody = Type.text(activity, "", Type.Style.CAPTION).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f) }
        column.addView(heroTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        column.addView(heroMeta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        column.addView(heroStreaming, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        column.addView(heroBody, LinearLayout.LayoutParams(dp(520), -2).apply { topMargin = dp(6) })
        heroButtons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; visibility = View.INVISIBLE }
        column.addView(heroButtons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        hero.addView(column, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, LinearLayout.LayoutParams(-1, dp(280)))
    }

    private fun setHero(e: Entry, related: List<Title>) {
        heroSet = true
        heroTitle.text = e.title.name; heroMeta.text = e.title.metaLine()
        heroStreaming.text = e.title.streamingLine().orEmpty(); heroStreaming.visibility = if (heroStreaming.text.isEmpty()) View.GONE else View.VISIBLE
        heroBody.text = e.title.overview.orEmpty()
        heroButtons.removeAllViews()
        e.library?.let { lib -> heroButtons.addView(pill("Watch now", accent = true).apply { setOnClickListener { watch(lib, e.title) } }, LinearLayout.LayoutParams(dp(170), dp(44)).apply { marginEnd = dp(10) }) }
        heroButtons.addView(pill("More info", accent = false).apply { setOnClickListener { openDetail(e, related) } }, LinearLayout.LayoutParams(dp(150), dp(44)))
        heroButtons.visibility = View.VISIBLE
        e.title.backdropUrl?.let { u -> images.load(u, maxWidth = 1280) { b -> heroImage.setImageBitmap(b); heroImage.animate().alpha(1f).setDuration(350).start() } }
        if (overlay.findFocus() == null && current != Dest.Search) requestFocusRobust(overlay as ViewGroup, heroButtons.getChildAt(0))
    }

    // --- Title page --------------------------------------------------------------------------------

    private fun openDetail(e: Entry, related: List<Title>) {
        returnFocus = overlay.findFocus()
        detail.removeAllViews(); detail.visibility = View.VISIBLE
        body.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val t = e.title

        val backdrop = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        detail.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        detail.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFA140E09.toInt(), 0xD9140E09.toInt(), 0x66140E09)) }, FrameLayout.LayoutParams(-1, -1))
        detail.addView(View(activity).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00140E09, 0xF2140E09.toInt())) }, FrameLayout.LayoutParams(-1, -1))
        (t.backdropUrl ?: e.library?.takeIf { it.hasBackdrop }?.let { jellyfin.backdropUrl(it.id) })?.let { u ->
            images.load(u, maxWidth = 1280) { b -> backdrop.setImageBitmap(b); backdrop.animate().alpha(1f).setDuration(300).start() }
        }

        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(px(R.dimen.page_margin), dp(30), px(R.dimen.page_margin), dp(8)) }
        val topRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val text = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        text.addView(Type.text(activity, (if (t.kind == Kind.SHOW) "SERIES" else "FILM") + if (e.library != null) "  ·  IN YOUR LIBRARY" else "", Type.Style.EYEBROW))
        text.addView(Type.text(activity, t.name, Type.Style.TITLE).apply { textSize = 34f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        text.addView(Type.text(activity, listOfNotNull(t.metaLine().ifBlank { null }, t.imdbScore?.let { "IMDb ${"%.1f".format(Locale.UK, it)}" }).joinToString("  ·  "), Type.Style.CAPTION).apply { maxLines = 1 }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        t.streamingLine()?.let { s -> text.addView(Type.text(activity, s, Type.Style.CAPTION).apply { maxLines = 1; setTextColor(activity.getColor(R.color.butter)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) }) }
        text.addView(Type.text(activity, t.overview ?: e.library?.overview ?: "No description yet.", Type.Style.BODY).apply { maxLines = 5; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(dp(520), -2).apply { topMargin = dp(10) })

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        val primary = pill("Checking…", accent = true)
        val backButton = pill("Back", accent = false).apply { setOnClickListener { closeDetail(restoreFocus = true) } }
        buttons.addView(primary, LinearLayout.LayoutParams(dp(230), dp(52)))
        buttons.addView(backButton, LinearLayout.LayoutParams(dp(120), dp(52)).apply { marginStart = dp(10) })
        text.addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        topRow.addView(text, LinearLayout.LayoutParams(0, -2, 1f))

        val posterBox = FrameLayout(activity).apply {
            clipToOutline = true; setBackgroundColor(0xFF3A2C1E.toInt())
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat()) } }
        }
        val posterView = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        posterBox.addView(posterView, FrameLayout.LayoutParams(-1, -1))
        t.posterUrl?.let { u -> images.load(u, e.posterHeaders) { b -> posterView.setImageBitmap(b) } }
        topRow.addView(posterBox, LinearLayout.LayoutParams(dp(150), dp(225)).apply { marginStart = dp(24) })
        column.addView(topRow, LinearLayout.LayoutParams(-1, -2))

        val more = related.filter { it.key != t.key }.take(14)
        if (more.isNotEmpty()) {
            column.addView(SectionHeader.build(activity, "More like this", null).apply { setPadding(0, dp(12), 0, dp(4)) })
            val r = row(null, null, into = column, inset = false)
            more.map { entry(it) }.forEachIndexed { i, m -> r.inner.addView(card(m, more, small = true, nested = false), cardParams(i, small = true)) }
        }
        detail.addView(column, FrameLayout.LayoutParams(-1, -1))
        setupPrimary(primary, e)
        requestFocusRobust(detail, primary)
    }

    private fun closeDetail(restoreFocus: Boolean) {
        if (!detailOpen) return
        detail.visibility = View.GONE; detail.removeAllViews()
        body.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (restoreFocus) returnFocus?.takeIf { it.isAttachedToWindow }?.let { f -> f.post { f.requestFocus() } }
    }

    /** Watch now for what is in the library; for the rest, queue it, or say where its request has got to. */
    private fun setupPrimary(button: TextView, e: Entry) {
        fun set(label: String, enabled: Boolean, action: (() -> Unit)?) {
            button.text = label; button.isEnabled = enabled; button.alpha = if (enabled) 1f else 0.6f
            button.setOnClickListener { action?.invoke() }
        }
        val lib = e.library
        if (lib != null) { set("Watch now", true) { watch(lib, e.title) }; return }
        val tmdb = e.title.tmdbId?.toLongOrNull()
        if (tmdb == null) { set("Can't be requested", false, null); return }
        fun queue() {
            if (!seerr.isSignedIn) { seerrSheet.show(seerr, jellyfin) { queue() }; return }
            set("Queuing…", false, null)
            worker.execute {
                val outcome = seerr.request(e.title.kind, tmdb)
                activity.runOnUiThread {
                    when (outcome) {
                        RequestOutcome.QUEUED, RequestOutcome.ALREADY -> { set("Requested ✓", false, null); Toast.makeText(activity, "${e.title.name} is queued for download", Toast.LENGTH_LONG).show() }
                        RequestOutcome.NEEDS_SIGN_IN -> { set("Queue download", true) { queue() }; seerrSheet.show(seerr, jellyfin) { queue() } }
                        RequestOutcome.FAILED -> { set("Queue download", true) { queue() }; Toast.makeText(activity, "Couldn't queue it right now", Toast.LENGTH_LONG).show() }
                    }
                }
            }
        }
        if (!seerr.isSignedIn) { set("Queue download", true) { queue() }; return }
        set("Checking…", false, null)
        worker.execute {
            val availability = seerr.availability(e.title.kind, tmdb)
            activity.runOnUiThread {
                if (!detailOpen) return@runOnUiThread
                when (availability) {
                    Availability.REQUESTED -> set("Requested ✓", false, null)
                    Availability.DOWNLOADING -> set("Downloading…", false, null)
                    Availability.PARTIAL -> set("Partly downloaded", false, null)
                    Availability.AVAILABLE -> set("Downloaded, syncing", false, null)
                    Availability.NONE -> set("Queue download", true) { queue() }
                }
            }
        }
    }

    private fun watch(item: JellyfinItem, title: Title) {
        if (!item.isSeries) { JellyfinPlayback.play(activity, jellyfin, item, title.name, title.imdbId ?: "jf:${item.id}"); return }
        Toast.makeText(activity, "Finding the next episode…", Toast.LENGTH_SHORT).show()
        worker.execute {
            val ep = jellyfin.episodeToPlay(item.id)
            activity.runOnUiThread {
                if (ep == null) Toast.makeText(activity, "No episodes found for ${title.name}", Toast.LENGTH_LONG).show()
                else JellyfinPlayback.play(activity, jellyfin, ep, listOfNotNull(title.name, ep.episodeLabel()).joinToString(" · "), "jf:${ep.id}")
            }
        }
    }

    // --- Category tiles ----------------------------------------------------------------------------

    private val tileColours = listOf(R.color.terracotta, R.color.deep_teal, R.color.butter, R.color.olive, R.color.cobalt, R.color.walnut)

    private fun serviceTiles() {
        val r = row("Browse by service", null)
        Catalog.SERVICES.forEachIndexed { i, s ->
            r.inner.addView(tile(s.label, i, wide = true) { go(Dest.OnService(s), remember = true) }, LinearLayout.LayoutParams(dp(176), dp(92)).apply { if (i > 0) marginStart = dp(12) })
        }
    }

    private fun genreTiles() {
        val r = row("Browse by genre", null)
        Catalog.GENRES.forEachIndexed { i, g ->
            r.inner.addView(tile(g.label, i + 2, wide = false) { go(Dest.InGenre(g), remember = true) }, LinearLayout.LayoutParams(dp(132), dp(72)).apply { if (i > 0) marginStart = dp(12) })
        }
    }

    /** A painted tile: a flat colour from the room's palette with a sun or stripes in it, and the name in serif. */
    private fun tile(label: String, seed: Int, wide: Boolean, onSelect: () -> Unit): View {
        val radius = dp(16).toFloat()
        val colour = activity.getColor(tileColours[seed % tileColours.size])
        val light = colour == activity.getColor(R.color.butter)
        val tile = FrameLayout(activity).apply { isFocusable = true; isClickable = true; contentDescription = label }
        tile.addView(TextView(activity).apply {
            text = label; textSize = if (wide) 20f else 17f; gravity = Gravity.BOTTOM or Gravity.START; maxLines = 2
            typeface = Typeface.create("serif", Typeface.BOLD); setTextColor(activity.getColor(if (light) R.color.ink else R.color.ivory))
            setPadding(dp(14), dp(8), dp(10), dp(10))
        }, FrameLayout.LayoutParams(-1, -1))
        LauncherTheme.bindPanelFocus(tile, MotifDrawable(colour, radius, seed), LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit -> scrollFocusIntoView(tile, lit) }
        tile.setOnClickListener { onSelect() }
        return tile
    }

    // --- Pieces ------------------------------------------------------------------------------------

    /** A heading and an empty scrolling row beneath it. */
    private fun row(heading: String?, caption: String?, into: LinearLayout = content, inset: Boolean = true): Row {
        val margin = if (inset) px(R.dimen.page_margin) else 0
        val head = heading?.let { h ->
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(margin, dp(16), margin, dp(4))
                addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.terracotta)) }, LinearLayout.LayoutParams(dp(4), dp(22)).apply { marginEnd = dp(10) })
                addView(Type.text(activity, h, Type.Style.HEADING))
                if (caption != null) addView(Type.text(activity, caption, Type.Style.CAPTION), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
            }
        }
        head?.let { into.addView(it) }
        val inner = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(6), 0, dp(6)) }
        val scroller = CalmHorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(margin, 0, margin, 0)
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(px(R.dimen.edge_fade))
            addView(inner)
        }
        into.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        return Row(head, scroller, inner)
    }

    private fun cardParams(index: Int, small: Boolean = false, wide: Boolean = false) =
        LinearLayout.LayoutParams(dp(if (wide) 196 else if (small) 108 else 138), -2).apply { if (index > 0) marginStart = dp(10) }

    private fun poster(e: Entry): FrameLayout {
        val frame = FrameLayout(activity).apply {
            clipToOutline = true; setBackgroundColor(0xFF3A2C1E.toInt())
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat()) } }
        }
        frame.addView(TextView(activity).apply {
            text = e.title.name.filter { it.isLetterOrDigit() || it == ' ' }.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercaseChar().toString() }
            gravity = Gravity.CENTER; textSize = 26f; setTextColor(0x99F3E9CF.toInt())
        }, FrameLayout.LayoutParams(-1, -1))
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        if (e.library != null) frame.addView(TextView(activity).apply {
            text = "IN LIBRARY"; textSize = 9f; letterSpacing = 0.08f; setTextColor(0xFFF3E9CF.toInt()); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(dp(6), dp(2), dp(6), dp(2)); background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(activity.getColor(R.color.terracotta)) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(5), 0, 0, dp(5)) })
        e.title.posterUrl?.let { u -> images.load(u, e.posterHeaders, maxWidth = 332) { b: Bitmap -> image.setImageBitmap(b) } }
        return frame
    }

    /** A poster with its name beneath. Lights ivory on focus; a small badge says when it is already in the library. */
    private fun card(e: Entry, related: List<Title>, small: Boolean = false, nested: Boolean = true): View {
        val radius = dp(12).toFloat()
        val w = if (small) 100 else 130
        val h = if (small) 150 else 195
        val tile = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(4), dp(6)); contentDescription = e.title.name }
        tile.addView(poster(e), LinearLayout.LayoutParams(dp(w), dp(h)))
        tile.addView(Type.text(activity, e.title.name, Type.Style.BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 12f }, LinearLayout.LayoutParams(dp(w), -2).apply { topMargin = dp(5) })
        val rest = GradientDrawable().apply { cornerRadius = radius; setColor(0) }
        LauncherTheme.bindPanelFocus(tile, rest, LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit -> scrollFocusIntoView(tile, lit) }
        tile.setOnClickListener { if (nested) openDetail(e, related) else { closeDetail(restoreFocus = false); openDetail(e, related) } }
        return tile
    }

    /** A "Top 10" card: a big serif numeral standing beside the poster. */
    private fun rankCard(rank: Int, e: Entry, related: List<Title>): View {
        val radius = dp(12).toFloat()
        val tile = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; clipChildren = false; clipToPadding = false; setPadding(dp(4), dp(4), dp(4), dp(6)); contentDescription = "$rank. ${e.title.name}" }
        val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        line.addView(TextView(activity).apply {
            text = rank.toString(); textSize = if (rank >= 10) 56f else 80f; gravity = Gravity.BOTTOM or Gravity.END; includeFontPadding = false
            typeface = Typeface.create("serif", Typeface.BOLD); setTextColor(activity.getColor(R.color.butter)); setPadding(0, 0, dp(4), dp(2))
        }, LinearLayout.LayoutParams(dp(if (rank >= 10) 78 else 58), dp(195)))
        line.addView(poster(e), LinearLayout.LayoutParams(dp(130), dp(195)))
        tile.addView(line, LinearLayout.LayoutParams(-2, -2))
        tile.addView(Type.text(activity, e.title.name, Type.Style.BODY).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; textSize = 12f }, LinearLayout.LayoutParams(dp(130), -2).apply { topMargin = dp(5); gravity = Gravity.END })
        val rest = GradientDrawable().apply { cornerRadius = radius; setColor(0) }
        LauncherTheme.bindPanelFocus(tile, rest, LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit -> scrollFocusIntoView(tile, lit) }
        tile.setOnClickListener { openDetail(e, related) }
        return tile
    }

    private fun actionCard(title: String, subtitle: String, onSelect: () -> Unit): View {
        val radius = dp(14).toFloat()
        val tile = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; isFocusable = true; isClickable = true; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(10)) }
        tile.addView(Type.text(activity, title, Type.Style.BODY))
        tile.addView(Type.text(activity, subtitle, Type.Style.CAPTION).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        LauncherTheme.bindPanelFocus(tile, LauncherTheme.tileBackground(activity, radius), LauncherTheme.tileBackgroundFocused(activity, radius), 1f) { lit -> scrollFocusIntoView(tile, lit) }
        tile.setOnClickListener { onSelect() }
        tile.layoutParams = LinearLayout.LayoutParams(dp(360), dp(64))
        return tile
    }

    private fun pill(label: String, accent: Boolean): TextView {
        val rest = LauncherTheme.surface(activity, if (accent) R.color.action else R.color.surface_selected, R.dimen.radius_pill)
        val lit = LauncherTheme.surface(activity, R.color.focus, R.dimen.radius_pill)
        return Type.text(activity, label, Type.Style.BODY).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true; maxLines = 1
            background = android.graphics.drawable.TransitionDrawable(arrayOf(rest, lit)).apply { isCrossFadeEnabled = true }
            setOnFocusChangeListener { v, focused ->
                val d = v.background as android.graphics.drawable.TransitionDrawable
                if (focused) d.startTransition(120) else d.reverseTransition(120)
                (v as TextView).setTextColor(activity.getColor(if (focused) R.color.ink else R.color.ivory))
            }
        }
    }

    private fun note(text: String) = Type.text(activity, text, Type.Style.CAPTION).apply { setPadding(dp(4), dp(10), 0, dp(10)) }

    private fun dp(v: Int) = Math.round(v * activity.resources.displayMetrics.density)
    private fun px(res: Int) = activity.resources.getDimensionPixelSize(res)

    // --- The page's own marks ----------------------------------------------------------------------

    /** The wordmark's emblem: a low sun over two painted stripes, after the room's paintings. */
    private class BrandMark(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val s = minOf(width, height).toFloat()
            paint.color = context.getColor(R.color.butter); canvas.drawCircle(s * 0.5f, s * 0.38f, s * 0.27f, paint)
            paint.color = context.getColor(R.color.terracotta); canvas.drawRoundRect(RectF(s * 0.06f, s * 0.70f, s * 0.94f, s * 0.82f), s * 0.06f, s * 0.06f, paint)
            paint.color = context.getColor(R.color.deep_teal); canvas.drawRoundRect(RectF(s * 0.06f, s * 0.86f, s * 0.94f, s * 0.98f), s * 0.06f, s * 0.06f, paint)
        }
    }

    /** Three short stripes in the palette, the hero's signature. */
    private class Stripes(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val w = width / 3f
            listOf(R.color.terracotta, R.color.butter, R.color.deep_teal).forEachIndexed { i, c ->
                paint.color = context.getColor(c); canvas.drawRect(i * w, 0f, (i + 1) * w - 3f, height.toFloat(), paint)
            }
        }
    }

    /** A tile's painted face: a flat colour with a pale sun, a pair of stripes or a half moon, varying with [seed]. */
    private class MotifDrawable(private val colour: Int, private val radius: Float, private val seed: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat()
            val clip = Path().apply { addRoundRect(RectF(b), radius, radius, Path.Direction.CW) }
            canvas.save(); canvas.clipPath(clip)
            paint.color = colour; canvas.drawRect(RectF(b), paint)
            paint.color = Color.argb(46, 243, 233, 207)
            when (seed % 3) {
                0 -> canvas.drawCircle(b.left + w * 0.84f, b.top + h * 0.28f, h * 0.46f, paint)
                1 -> { canvas.drawRect(b.left + w * 0.62f, b.top.toFloat(), b.left + w * 0.72f, b.bottom.toFloat(), paint); canvas.drawRect(b.left + w * 0.78f, b.top.toFloat(), b.left + w * 0.84f, b.bottom.toFloat(), paint) }
                else -> canvas.drawCircle(b.left + w * 0.12f, b.top + h * 1.0f, h * 0.55f, paint)
            }
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
