package com.example.tvlauncher

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.tvlauncher.data.AppEntry
import com.example.tvlauncher.data.AppRepository
import com.example.tvlauncher.data.FavoritesController
import com.example.tvlauncher.data.IconCache
import com.example.tvlauncher.data.SharedPrefsFavoritesStore
import com.example.tvlauncher.design.Avatar
import com.example.tvlauncher.design.LauncherTheme
import com.example.tvlauncher.design.RoundIcon
import com.example.tvlauncher.system.AccountsRepository
import com.example.tvlauncher.system.CapabilityResult
import com.example.tvlauncher.system.TvAccount
import com.example.tvlauncher.system.CapabilityStatus
import com.example.tvlauncher.system.NetworkState
import com.example.tvlauncher.system.NetworkStatusMonitor
import com.example.tvlauncher.system.SystemActions
import com.example.tvlauncher.ui.AccountMenu
import com.example.tvlauncher.ui.AllAppsPanel
import com.example.tvlauncher.ui.ArtModeOverlay
import com.example.tvlauncher.ui.EditAppsPanel
import com.example.tvlauncher.ui.HeaderBar
import com.example.tvlauncher.ui.HomeBackdrop
import com.example.tvlauncher.ui.HomeCollection
import com.example.tvlauncher.ui.NavTab
import com.example.tvlauncher.ui.SearchPanel
import com.example.tvlauncher.ui.WhoPicker
import com.example.tvlauncher.ui.room.HomeRoom
import com.example.tvlauncher.ui.room.InfoSheet
import com.example.tvlauncher.ui.ShelfLayoutBuilder
import com.example.tvlauncher.ui.ShelfTile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TvLauncher"
private const val STANDBY_PICKER_MS = 10 * 60 * 1000L

/** Which context the footer hint reflects. */
private enum class FooterContext { SHELF_APP, SHELF_ALL_APPS, FEATURED_APP, ORGANISE, NONE }

/**
 * The home screen: a full-bleed painting with a real, native TV shell on
 * top of it -- header nav/system icons, a "Your apps" shelf, and the
 * All apps / Edit your apps / Search / Art-mode overlays. See
 * design/LauncherTheme.kt for the shared visual tokens and
 * data/AppRepository.kt for how apps are discovered.
 */
class MainActivity : AppCompatActivity() {

    private val clockHandler = Handler(Looper.getMainLooper())
    private lateinit var clockView: TextView
    private lateinit var shelfContainer: LinearLayout
    private lateinit var featuredContainer: LinearLayout
    private lateinit var footerLeft: TextView
    private lateinit var footerRight: TextView

    private lateinit var repository: AppRepository
    private lateinit var favorites: FavoritesController
    private lateinit var shelfBuilder: ShelfLayoutBuilder
    private lateinit var systemActions: SystemActions
    private lateinit var networkMonitor: NetworkStatusMonitor

    private lateinit var headerBar: HeaderBar
    private lateinit var allAppsPanel: AllAppsPanel
    private lateinit var editAppsPanel: EditAppsPanel
    private lateinit var searchPanel: SearchPanel
    private lateinit var artModeOverlay: ArtModeOverlay
    private lateinit var networkIcon: ImageView
    private lateinit var collection: HomeCollection
    private lateinit var backdrop: HomeBackdrop
    private lateinit var accountsRepo: AccountsRepository
    private lateinit var accountMenu: AccountMenu
    private lateinit var whoPicker: WhoPicker
    private lateinit var infoSheet: InfoSheet
    private lateinit var homeRoom: HomeRoom
    private lateinit var continueRow: com.example.tvlauncher.ui.ContinueRow
    private var screenOffAt = 0L
    private lateinit var accountIcon: ImageView
    private var hasResumedOnce = false


    /** Cached from the last [refreshApps] -- reused by overlays/capabilities without re-querying PackageManager. */
    private var currentApps: List<AppEntry> = emptyList()

    private var lastShelfFocusIndex = 0
    private var organiseIndex: Int? = null
    private var organiseSnapshot: List<String>? = null
    private var awaitingRelease = false
    private var movedWhileHeld = false
    private var artReturnFocus: android.view.View? = null
    private var lastMainFocus: android.view.View? = null

    private val clockTick = object : Runnable {
        override fun run() {
            clockView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            clockHandler.postDelayed(this, 15_000)
        }
    }

    /** Debug builds: logs how long each start-up step took (adb logcat -s Boot). */
    private var bootAt = android.os.SystemClock.uptimeMillis()
    private fun bootMark(step: String) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val now = android.os.SystemClock.uptimeMillis()
        Log.d("Boot", "before $step: ${now - bootAt} ms")
        bootAt = now
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.tvlauncher.system.FireHomeService.startIfEnabled(this)
        bootMark("onCreate")
        bootMark("layout")
        setContentView(R.layout.activity_main)

        repository = AppRepository(this)
        favorites = FavoritesController(SharedPrefsFavoritesStore(this))
        shelfBuilder = ShelfLayoutBuilder(this)
        systemActions = SystemActions(this)
        networkMonitor = NetworkStatusMonitor(this) { state -> runOnUiThread { updateNetworkIcon(state) } }

        clockView = findViewById(R.id.clock)
        shelfContainer = findViewById(R.id.shelfContainer)
        featuredContainer = findViewById(R.id.featuredContainer)
        footerLeft = findViewById(R.id.footerLeft)
        footerRight = findViewById(R.id.footerRight)
        networkIcon = findViewById(R.id.iconNetwork)

        bootMark("services")
        headerBar = HeaderBar(
            navTabs = mapOf(
                NavTab.HOME to findViewById(R.id.navHome),
                NavTab.APPS to findViewById(R.id.navApps),
                NavTab.LIVE_TV to findViewById(R.id.navLiveTv),
                NavTab.ART to findViewById(R.id.navArt)
            ),
            iconViews = listOf(
                findViewById(R.id.iconSearch),
                findViewById(R.id.iconInputs),
                networkIcon,
                findViewById(R.id.iconSettings)
            ),
            onSelectTab = ::onNavTabSelected
        )

        accountsRepo = AccountsRepository(this)
        accountIcon = findViewById(R.id.iconAccounts)
        accountMenu = AccountMenu(
            container = findViewById(R.id.accountMenu),
            accounts = accountsRepo,
            onPick = { account -> accountsRepo.setCurrent(account); refreshAccountIcon() },
            onSwitchProfile = { showWhoPicker() },
            onManage = { handleCapability(systemActions.openAccounts()) },
            onClosed = { accountIcon.requestFocus() }
        )
        whoPicker = WhoPicker(
            container = findViewById(R.id.whoPicker),
            accounts = accountsRepo,
            onChosen = { account -> accountsRepo.setCurrent(account); refreshAccountIcon() },
            onClosed = { restoreShelfFocus() }
        )
        // The avatar lights like every other round header control: a solid ivory disc behind the photo.
        val avatarDisc = LauncherTheme.circleBackgroundFocused(this)
        accountIcon.setOnFocusChangeListener { v, hasFocus ->
            com.example.tvlauncher.ui.scrollFocusIntoView(v, hasFocus)
            v.background = if (hasFocus) avatarDisc else null
            LauncherTheme.animateFocus(v, hasFocus, 1.1f)
        }
        // Remember the last thing focused on the page itself, so closing any overlay returns exactly there.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            if (focused != null && !anyOverlayVisible()) {
                lastMainFocus = focused
                if (::backdrop.isInitialized) backdrop.setMood(com.example.tvlauncher.design.SectionTheme.moodOf(focused) ?: com.example.tvlauncher.design.SectionTheme.Mood.NEUTRAL)
            }
            // Runs before the new item's own focus listener, which sets the hint again if it has one.
            if (organiseIndex == null) com.example.tvlauncher.ui.PageHint.show(null)
        }
        // Panels and sheets own the screen: while one is up the page is hidden and the painting blurred,
        // so nothing ghosts through behind it. Checked on every layout, because overlays open from many places.
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener { syncObscured() }
        accountIcon.setOnClickListener {
            findViewById<android.widget.ScrollView>(R.id.homeScroll).scrollTo(0, 0)
            accountMenu.toggle()
        }

        bootMark("header+accounts")
        allAppsPanel = AllAppsPanel(
            overlay = findViewById(R.id.allAppsOverlay),
            grid = findViewById(R.id.allAppsGrid),
            shelfBuilder = shelfBuilder,
            onLaunch = ::launchApp,
            onOpenEdit = {
                allAppsPanel.hide()
                editAppsPanel.show(currentApps)
            },
            onAppInfo = { entry -> handleCapability(systemActions.openAppInfo(entry.packageName)); true },
            editButton = findViewById(R.id.allAppsEdit)
        )

        editAppsPanel = EditAppsPanel(
            overlay = findViewById(R.id.editAppsOverlay),
            list = findViewById(R.id.editAppsList),
            favorites = favorites,
            onChanged = { /* Shelf is rebuilt when the editor closes. */ }
        )

        searchPanel = SearchPanel(
            overlay = findViewById(R.id.searchOverlay),
            input = findViewById<EditText>(R.id.searchInput),
            resultsGrid = findViewById(R.id.searchResultsGrid),
            noResults = findViewById(R.id.searchNoResults),
            shelfBuilder = shelfBuilder,
            onLaunch = ::launchApp
        )

        artModeOverlay = ArtModeOverlay(findViewById(R.id.artModeOverlay)) {
            headerBar.setSelected(NavTab.HOME)
            backdrop.showCurrent()
            collection.homeReturned()
            if (artReturnFocus?.isAttachedToWindow == true) artReturnFocus?.requestFocus() else restoreShelfFocus()
        }
        infoSheet = InfoSheet(findViewById(R.id.infoSheet))
        bootMark("panels")
        collection = HomeCollection(this, infoSheet, artModeOverlay, ::enterArtMode,
            { handleCapability(systemActions.openSettings()) }, { editAppsPanel.show(currentApps) },
            { IconCache.clear(); currentApps = emptyList(); refreshApps() })
        androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        continueRow = com.example.tvlauncher.ui.ContinueRow(
            findViewById(R.id.continueSection), findViewById(R.id.continueContainer),
            com.example.tvlauncher.data.ContinueWatching(this), ::resumeItem
        )
        bootMark("collection")
        homeRoom = HomeRoom(this, infoSheet)
        bootMark("room")
        backdrop = HomeBackdrop(findViewById(R.id.homePainting), artModeOverlay.library)
        // The painting covers the whole window once it is up, so the window's own ink ground is just overdraw.
        backdrop.onFirstPainting = { window.setBackgroundDrawable(null) }
        backdrop.showCurrent()
        val homeScroll = findViewById<com.example.tvlauncher.ui.CalmScrollView>(R.id.homeScroll)
        pageVeil = backdrop.attachVeil(homeScroll)
        homeScroll.onScrolled = { y ->
            backdrop.onPageScrolled()
            com.example.tvlauncher.ui.SpatialNavigation.update(homeScroll.getChildAt(0) as android.view.ViewGroup, y, homeScroll.height)
        }
        com.example.tvlauncher.design.SectionTheme.tag(findViewById(R.id.continueSection), com.example.tvlauncher.design.SectionTheme.Mood.FILM)

        findViewById<ImageView>(R.id.iconSearch).setOnClickListener { searchPanel.show(currentApps) }
        findViewById<ImageView>(R.id.iconInputs).setOnClickListener { handleCapability(systemActions.openInputs()) }
        networkIcon.setOnClickListener { handleCapability(systemActions.openNetworkSettings()) }
        findViewById<ImageView>(R.id.iconSettings).setOnClickListener { handleCapability(systemActions.openSettings()) }
        com.example.tvlauncher.ui.PageHint.attach(findViewById(R.id.pageHint))
        placeHint()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    infoSheet.isVisible -> infoSheet.hide()
                    whoPicker.isVisible -> whoPicker.hide()
                    accountMenu.isVisible -> accountMenu.hide()
                    artModeOverlay.isVisible -> { artModeOverlay.hide(); headerBar.setSelected(NavTab.HOME); collection.homeReturned(); restoreShelfFocus() }
                    searchPanel.isVisible -> closeOverlayAndRestore { searchPanel.hide() }
                    editAppsPanel.isVisible -> closeOverlayAndRestore {
                        editAppsPanel.hide()
                        renderShelf(lastShelfFocusIndex)
                    }
                    allAppsPanel.isVisible -> closeOverlayAndRestore { allAppsPanel.hide() }
                    organiseIndex != null -> cancelOrganise()
                    else -> {
                        com.example.tvlauncher.design.Motion.scrollVerticalTo(findViewById(R.id.homeScroll), 0)
                        findViewById<TextView>(R.id.navHome).requestFocus()
                    }
                }
            }
        })
    }

    /**
     * The hint sits where the header has nothing to say: vertically centred on the header, ending a gap
     * short of its right-hand icons. Measured once the header is laid out (the page is at the top then).
     */
    private fun placeHint() {
        val hint = findViewById<TextView>(R.id.pageHint)
        val header = findViewById<android.view.View>(R.id.header)
        val icons = findViewById<android.view.View>(R.id.iconInputs)
        header.post {
            val h = IntArray(2).also { header.getLocationInWindow(it) }
            val i = IntArray(2).also { icons.getLocationInWindow(it) }
            val root = hint.parent as android.view.View
            hint.measure(android.view.View.MeasureSpec.UNSPECIFIED, android.view.View.MeasureSpec.UNSPECIFIED)
            com.example.tvlauncher.ui.PageHint.tipAt = android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
                topMargin = h[1] + (header.height - hint.measuredHeight) / 2
                marginEnd = root.width - i[0] + resources.getDimensionPixelSize(R.dimen.space_4)
            }
            com.example.tvlauncher.ui.PageHint.tipLowAt = android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
                marginEnd = resources.getDimensionPixelSize(R.dimen.page_margin)
                bottomMargin = resources.getDimensionPixelSize(R.dimen.space_2)
            }
            val page = findViewById<android.widget.ScrollView>(R.id.homeScroll)
            com.example.tvlauncher.ui.PageHint.headerShown = { page.scrollY < header.bottom / 2 }
            com.example.tvlauncher.ui.PageHint.modeAt = android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.END).apply {
                marginEnd = resources.getDimensionPixelSize(R.dimen.page_margin)
                bottomMargin = resources.getDimensionPixelSize(R.dimen.space_4)
            }
        }
    }

    /** Shows the "Who's watching?" picker once per boot, and again after the TV has been in standby a while. */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAt = android.os.SystemClock.elapsedRealtime()
                Intent.ACTION_SCREEN_ON -> if (screenOffAt != 0L && android.os.SystemClock.elapsedRealtime() - screenOffAt > STANDBY_PICKER_MS) {
                    window.decorView.post { showWhoPicker() }
                }
            }
        }
    }

    private fun bootCount(): Int = android.provider.Settings.Global.getInt(contentResolver, "boot_count", 0)

    private fun showWhoPicker() {
        if (whoPicker.show()) accountsRepo.pickerBoot = bootCount()
        else handleCapability(systemActions.openAccounts())
    }

    override fun onResume() {
        super.onResume()
        bootMark("onResume")
        clockHandler.post(clockTick)
        networkMonitor.start()
        refreshApps()
        bootMark("refreshApps")
        collection.homeReturned()
        artModeOverlay.resume()
        if (!hasResumedOnce && accountsRepo.pickerBoot != bootCount() && accountsRepo.accounts().size > 1) {
            if (whoPicker.show()) accountsRepo.pickerBoot = bootCount()
        }
        if (hasResumedOnce && !artModeOverlay.isVisible) backdrop.advance()
        hasResumedOnce = true
        bootMark("homeReturned+picker")
        homeRoom.refresh()
        homeRoom.start()
        ensureWatcherBound()
        ensureRemoteKeysEnabled()
        bootMark("room+watcher")
        continueRow.refresh(currentApps)
        backdrop.start()
        refreshAccountIcon()
        if (!accountsRepo.hasPermission() && !accountsRepo.askedForPermission) {
            accountsRepo.askedForPermission = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.GET_ACCOUNTS), 41)
        }
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTick)
        networkMonitor.stop()
        artModeOverlay.pause()
        backdrop.stop()
        homeRoom.stop()
    }

    override fun onDestroy() {
        appLoader.shutdownNow()
        collection.close()
        backdrop.close()
        continueRow.close()
        homeRoom.close()
        unregisterReceiver(screenReceiver)
        artModeOverlay.pause()
        super.onDestroy()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (artModeOverlay.isVisible) artModeOverlay.hide()
        allAppsPanel.hide(); editAppsPanel.hide(); searchPanel.hide(); accountMenu.hide()
        headerBar.setSelected(NavTab.HOME)
        collection.homeReturned()
        findViewById<TextView>(R.id.navHome).requestFocus()
        com.example.tvlauncher.design.Motion.scrollVerticalTo(findViewById(R.id.homeScroll), 0)
    }

    /**
     * Defensive fallback: the shelf's own post{requestFocus()} can
     * occasionally lose a race with the window's own default-focus
     * assignment when the window regains focus. Re-assert focus onto the
     * last-known shelf item whenever the window becomes focused and
     * nothing inside the shelf currently holds it (and no overlay is up).
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !anyOverlayVisible() && currentFocus == null && shelfContainer.childCount > 0) {
            shelfContainer.getChildAt(lastShelfFocusIndex.coerceIn(0, shelfContainer.childCount - 1))?.requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU && com.example.tvlauncher.system.FireHomeService.supported()) {
            if (event.action == KeyEvent.ACTION_UP) startActivity(Intent(this, com.example.tvlauncher.system.FireHomeSettingsActivity::class.java))
            return true
        }
        // While an app is held for rearranging, the remote belongs to the drag no matter where focus is.
        if (organiseIndex != null && onOrganiseKey(event.keyCode, event)) return true
        val direction = when(event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> android.view.View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> android.view.View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> android.view.View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> android.view.View.FOCUS_RIGHT
            else -> 0
        }
        if (event.action == KeyEvent.ACTION_DOWN && direction != 0 && ::allAppsPanel.isInitialized) {
            val overlayId = when {
                infoSheet.isVisible && !infoSheet.isClosing -> R.id.infoSheet
                whoPicker.isVisible -> R.id.whoPicker
                accountMenu.isVisible -> R.id.accountMenu
                searchPanel.isVisible -> R.id.searchOverlay
                editAppsPanel.isVisible -> R.id.editAppsOverlay
                allAppsPanel.isVisible -> R.id.allAppsOverlay
                else -> 0
            }
            if (overlayId != 0) {
                val overlay = findViewById<android.view.ViewGroup>(overlayId)
                val focused = currentFocus
                val editingText = focused is EditText && (direction == android.view.View.FOCUS_LEFT || direction == android.view.View.FOCUS_RIGHT)
                val scrolling = focused is android.widget.ScrollView &&
                    ((direction == android.view.View.FOCUS_DOWN && focused.canScrollVertically(1)) || (direction == android.view.View.FOCUS_UP && focused.canScrollVertically(-1)))
                if (!editingText && !scrolling) {
                    if (focused == null || overlay.findFocus() == null) {
                        overlay.requestFocus()           // focus strayed behind the overlay: bring it back
                    } else {
                        // Only ever move to something inside the overlay; at its edges the key is simply absorbed.
                        android.view.FocusFinder.getInstance().findNextFocus(overlay, focused, direction)?.requestFocus(direction)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun anyOverlayVisible(): Boolean =
        allAppsPanel.isVisible || editAppsPanel.isVisible || searchPanel.isVisible || artModeOverlay.isVisible || accountMenu.isVisible || whoPicker.isVisible || (infoSheet.isVisible && !infoSheet.isClosing)

    private var obscured = false
    private var pageVeil: android.view.View? = null

    private fun syncObscured() {
        if (!::backdrop.isInitialized) return
        val cover = allAppsPanel.isVisible || editAppsPanel.isVisible || searchPanel.isVisible || whoPicker.isVisible || (infoSheet.isVisible && !infoSheet.isClosing)
        if (cover == obscured) return
        obscured = cover
        // Alpha, not visibility: the page stays focusable, so closing a sheet can hand focus straight back to it.
        // The page also steps back a little as a layer arrives on top of it, and comes forward again after.
        val page = findViewById<android.view.View>(R.id.homeScroll)
        val on = com.example.tvlauncher.design.Motion.animationsOn(page)
        page.pivotX = page.width / 2f; page.pivotY = page.height / 2f
        val scale = if (cover) com.example.tvlauncher.design.Motion.PAGE_RECEDE else 1f
        val ms = if (!on) 0 else if (cover) com.example.tvlauncher.design.Motion.FADE_MS else com.example.tvlauncher.design.Motion.SHEET_OUT_MS + 60
        page.animate().alpha(if (cover) 0f else 1f).scaleX(scale).scaleY(scale)
            .setDuration(ms).setInterpolator(com.example.tvlauncher.design.Motion.SETTLE).start()
        // The edge veil is painting laid over the page, so it goes wherever the page goes.
        pageVeil?.animate()?.alpha(if (cover) 0f else 1f)?.setDuration(ms)?.setInterpolator(com.example.tvlauncher.design.Motion.SETTLE)?.start()
        backdrop.setBlurred(cover)
    }

    // --- App discovery / shelf --------------------------------------------

    /** Re-reads installed apps and rebuilds the shelf. Cheap; called on every HOME return. */
    private val appLoader = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Reads installed apps off the main thread (icons take ~1.3 s on a cold start), then rebuilds the shelf. */
    private fun refreshApps() {
        appLoader.execute {
            val apps = repository.loadLaunchableApps()
            runOnUiThread { if (!isDestroyed) applyApps(apps) }
        }
    }

    private fun applyApps(apps: List<AppEntry>) {
        Log.i(TAG, "Loaded ${apps.size} launchable apps")
        if (currentApps.isNotEmpty() && apps.map { it.packageName } == currentApps.map { it.packageName }) return
        val initial = currentApps.isEmpty()
        currentApps = apps
        renderShelf(lastShelfFocusIndex)
        if (initial) findViewById<TextView>(R.id.navHome).post { findViewById<TextView>(R.id.navHome).requestFocus() }
        if (allAppsPanel.isVisible) allAppsPanel.setApps(apps)
        continueRow.refresh(currentApps)
    }

    private fun renderShelf(focusIndex: Int) {
        val selection = favorites.select(currentApps)

        val featuredApps = selection.overflow
        val featuredTiles = featuredApps.map { entry ->
            ShelfTile.forApp(
                entry = entry,
                onLaunch = ::launchApp,
                onLongSelect = { promoteToShelf(entry); true },
                onFocus = { focused -> if (focused) updateFooter(FooterContext.FEATURED_APP) }
            )
        }
        findViewById<android.widget.LinearLayout>(R.id.featuredSection).visibility =
            if (featuredTiles.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        shelfBuilder.buildFeatureRow(featuredContainer, featuredTiles)

        val appTiles = selection.primary.mapIndexed { index, entry ->
            ShelfTile.forApp(
                entry = entry,
                onLaunch = ::launchApp,
                onLongSelect = { enterOrganiseMode(index); true },
                onFocus = { focused -> if (focused) onShelfTileFocused(index, isAllAppsTile = false) }
            )
        }
        val allAppsIndex = appTiles.size
        val allAppsTile = ShelfTile(
            label = getString(R.string.all_apps_title),
            artwork = RoundIcon.glyph(this, R.drawable.ic_grid_dots, R.color.disc_walnut),
            onSelect = {
                allAppsPanel.setApps(currentApps)
                allAppsPanel.show()
                headerBar.setSelected(NavTab.APPS)
            },
            onFocus = { focused -> if (focused) onShelfTileFocused(allAppsIndex, isAllAppsTile = true) }
        )

        shelfBuilder.buildRow(shelfContainer, appTiles + allAppsTile, focusIndex)
    }

    private fun onShelfTileFocused(index: Int, isAllAppsTile: Boolean) {
        lastShelfFocusIndex = index
        if (organiseIndex == null) {
            updateFooter(if (isAllAppsTile) FooterContext.SHELF_ALL_APPS else FooterContext.SHELF_APP)
        }
    }

    private fun restoreShelfFocus() {
        shelfContainer.post {
            shelfContainer.getChildAt(lastShelfFocusIndex.coerceIn(0, (shelfContainer.childCount - 1).coerceAtLeast(0)))
                ?.requestFocus()
        }
    }

    /**
     * On this TV, after the launcher restarts, Android can leave the media watcher enabled but not delivering
     * sessions, so nothing is added to Continue watching. Ask it to rebind whenever that is the case.
     */
    private fun ensureWatcherBound() {
        try {
            val name = android.content.ComponentName(this, com.example.tvlauncher.system.MediaWatcherService::class.java)
            val enabled = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")?.contains(name.flattenToString()) == true
            if (enabled && !com.example.tvlauncher.system.MediaWatcherService.connected) {
                Log.i(TAG, "Media watcher not connected; asking Android to rebind it")
                android.service.notification.NotificationListenerService.requestRebind(name)
            }
        } catch (e: Exception) { Log.w(TAG, "Could not check the media watcher: ${e.javaClass.simpleName}") }
    }

    /**
     * The remote's Settings / YouTube / Netflix / Media buttons live in RemoteKeyService. At boot this TV
     * can drop it from the enabled accessibility services, which leaves every mapped button dead. Home
     * checks on each visit and switches it back on (needs WRITE_SECURE_SETTINGS, granted once over ADB).
     */
    private fun ensureRemoteKeysEnabled() {
        try {
            val name = android.content.ComponentName(this, com.example.tvlauncher.system.RemoteKeyService::class.java).flattenToString()
            val resolver = contentResolver
            val current = android.provider.Settings.Secure.getString(resolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (current.split(':').any { it.equals(name, ignoreCase = true) }) return
            val updated = if (current.isBlank()) name else "$current:$name"
            android.provider.Settings.Secure.putString(resolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            android.provider.Settings.Secure.putInt(resolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Remote-button service was off; switched it back on")
        } catch (e: SecurityException) {
            Log.w(TAG, "Remote-button service is off and WRITE_SECURE_SETTINGS isn't granted (see README)")
        }
    }

    /** Opens the app's own deep link for a part-watched title; the app decides where to pick up. */
    private fun resumeItem(item: com.example.tvlauncher.data.ResumeItem) {
        item.payload?.let { if (com.example.tvlauncher.ui.PlayerActivity.resume(this, it)) return }
        // A video or film we know the id of opens directly (YouTube apps at the saved time; Stremio on the film's page).
        com.example.tvlauncher.data.ResumeLinks.target(item)?.let { link ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link.uri)).setPackage(link.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) { Log.w(TAG, "Deep link failed for ${item.title}: ${e.javaClass.simpleName}") }
        }
        seekIfStillOpen(item)
        val intent = item.launchIntent() ?: currentApps.firstOrNull { it.packageName == item.packageName }?.launchIntent
        if (intent == null) { Toast.makeText(this, "Couldn't open ${item.title}", Toast.LENGTH_SHORT).show(); return }
        try { startActivity(intent) } catch (e: Exception) {
            Log.e(TAG, "Resume failed for ${item.title}", e)
            currentApps.firstOrNull { it.packageName == item.packageName }?.let { launchApp(it) }
                ?: Toast.makeText(this, "Couldn't open ${item.title}", Toast.LENGTH_SHORT).show()
        }
    }

    /** If the app still has its playback session open in the background, ask it to jump back to where you were. */
    private fun seekIfStillOpen(item: com.example.tvlauncher.data.ResumeItem) {
        try {
            val manager = getSystemService(MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
            val controller = manager.getActiveSessions(android.content.ComponentName(this, com.example.tvlauncher.system.MediaWatcherService::class.java))
                .firstOrNull { it.packageName == item.packageName } ?: return
            val title = controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
            if (title == item.title) { controller.transportControls.seekTo(item.positionMs); controller.transportControls.play() }
        } catch (_: Exception) { /* notification access not granted, or no session: just open the app */ }
    }

    private fun launchApp(entry: AppEntry) {
        Log.i(TAG, "Launching ${entry.packageName}")
        try {
            startActivity(entry.launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ${entry.packageName}", e)
            Toast.makeText(this, "Couldn't open ${entry.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // --- Header navigation ---------------------------------------------------

    private fun onNavTabSelected(tab: NavTab) {
        when (tab) {
            NavTab.HOME -> {
                if (allAppsPanel.isVisible) allAppsPanel.hide()
                if (editAppsPanel.isVisible) editAppsPanel.hide()
                if (searchPanel.isVisible) searchPanel.hide()
                headerBar.setSelected(NavTab.HOME)
                collection.homeReturned()
                findViewById<TextView>(R.id.navHome).requestFocus()
                com.example.tvlauncher.design.Motion.scrollVerticalTo(findViewById(R.id.homeScroll), 0)
            }
            NavTab.APPS -> {
                headerBar.setSelected(NavTab.APPS)
                allAppsPanel.setApps(currentApps)
                allAppsPanel.show()
            }
            NavTab.LIVE_TV -> handleCapability(systemActions.openLiveTv(repository.loadLaunchableApps(ownedOnly = false)))
            NavTab.ART -> enterArtMode()
        }
    }

    private fun enterArtMode() {
        artReturnFocus = currentFocus
        headerBar.setSelected(NavTab.ART)
        artModeOverlay.show()
    }

    private fun closeOverlayAndRestore(hide: () -> Unit) {
        // Read the way back first: hiding the overlay drops its focus onto the window's first focusable
        // (the avatar), and the focus tracker would record that as the page's last focus.
        val back = lastMainFocus
        hide()
        headerBar.setSelected(NavTab.HOME)
        collection.homeReturned()
        if (back != null && back.isAttachedToWindow && back.isShown && back.isFocusable) back.post { back.requestFocus() } else restoreShelfFocus()
    }

    // --- Organise mode (long-press OK reorder) --------------------------------

    /**
     * Hold OK on a "More apps" card: lift it into the last slot of "Your apps" and start the drag, so
     * Left/Right place it. The shelf holds [PRIMARY_SHELF_LIMIT] apps, so whichever app was last on the
     * shelf moves down to "More apps". Back puts everything back.
     */
    private fun promoteToShelf(entry: AppEntry) {
        val snapshot = favorites.snapshotOrder(currentApps)
        val index = favorites.currentShelfOrder(currentApps).indexOfFirst { it.packageName == entry.packageName }
        if (index < 0) return
        val target = com.example.tvlauncher.data.PRIMARY_SHELF_LIMIT - 1
        if (index > target) favorites.move(currentApps, index, target - index)
        val slot = favorites.select(currentApps).primary.indexOfFirst { it.packageName == entry.packageName }
        if (slot < 0) return
        enterOrganiseMode(slot)
        shelfContainer.postDelayed({ if (organiseIndex == slot) shelfContainer.getChildAt(slot)?.requestFocus() }, 250)
        organiseSnapshot = snapshot   // Back restores the order from before the app was lifted
    }

    private fun enterOrganiseMode(index: Int) {
        organiseIndex = index
        organiseSnapshot = favorites.snapshotOrder(currentApps)
        // The OK key that triggered this is still down. Its release (and any auto-repeat) must not drop the app.
        awaitingRelease = true
        movedWhileHeld = false
        updateFooter(FooterContext.ORGANISE)
        setRearrangeHint(true)
        renderShelf(index)
        pickUp(index)
    }

    private fun pickUp(index: Int) {
        val child = shelfContainer.getChildAt(index) ?: return
        shelfBuilder.markPicked(child)
        child.requestFocus()
    }

    /**
     * Drag mode. Left/Right slide the held app along the shelf. OK drops it: on a fresh press, or on
     * release if it was moved while OK was still held. Auto-repeats of a held OK never drop it.
     */
    private fun onOrganiseKey(keyCode: Int, event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    if (awaitingRelease) movedWhileHeld = true
                    moveOrganise(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true   // stay on the shelf while dragging
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    if (awaitingRelease) {
                        awaitingRelease = false
                        if (movedWhileHeld) commitOrganise()
                    } else commitOrganise()
                }
                true
            }
            KeyEvent.KEYCODE_BACK -> { if (down) cancelOrganise(); true }
            else -> false
        }
    }

    /** Slides the held app one place, gliding its neighbour into the gap instead of rebuilding the row. */
    private fun moveOrganise(delta: Int) {
        val index = organiseIndex ?: return
        val maxIndex = favorites.select(currentApps).primary.size - 1
        val target = index + delta
        if (target !in 0..maxIndex) return
        val held = shelfContainer.getChildAt(index) ?: return
        val other = shelfContainer.getChildAt(target) ?: return
        val step = (other.left - held.left).toFloat()

        favorites.move(currentApps, index, delta)
        organiseIndex = target

        shelfContainer.removeView(other)
        shelfContainer.addView(other, index)          // the neighbour takes the held app's old slot
        other.translationX = step                      // ...but starts from where it was
        other.animate().translationX(0f).setDuration(250).setInterpolator(com.example.tvlauncher.design.Motion.SWOOSH).start()
        held.translationX = -step
        held.animate().translationX(0f).setDuration(250).setInterpolator(com.example.tvlauncher.design.Motion.SWOOSH).start()
        held.requestFocus()
        com.example.tvlauncher.ui.scrollFocusIntoView(held, true)
    }

    private fun setRearrangeHint(dragging: Boolean) {
        if (dragging) com.example.tvlauncher.ui.PageHint.showMode(getString(R.string.hint_dragging))
        else com.example.tvlauncher.ui.PageHint.show(getString(R.string.hint_rearrange))
    }

    private fun commitOrganise() {
        val index = organiseIndex ?: 0
        organiseIndex = null
        organiseSnapshot = null
        setRearrangeHint(false)
        updateFooter(FooterContext.SHELF_APP)
        renderShelf(index)
    }

    private fun cancelOrganise() {
        organiseSnapshot?.let { favorites.restoreOrder(it) }
        val index = organiseIndex ?: 0
        organiseIndex = null
        organiseSnapshot = null
        setRearrangeHint(false)
        updateFooter(FooterContext.SHELF_APP)
        renderShelf(index)
    }

    // --- Footer / capability plumbing ----------------------------------------

    /** The page hint for whatever just took focus: only hold actions and other non-obvious keys. */
    private fun updateFooter(context: FooterContext) {
        if (context == FooterContext.ORGANISE) { com.example.tvlauncher.ui.PageHint.showMode(getString(R.string.hint_dragging)); return }
        com.example.tvlauncher.ui.PageHint.show(when (context) {
            FooterContext.SHELF_APP -> getString(R.string.hint_rearrange)
            FooterContext.FEATURED_APP -> getString(R.string.hint_add_to_shelf)
            FooterContext.ORGANISE -> getString(R.string.hint_dragging)
            FooterContext.SHELF_ALL_APPS, FooterContext.NONE -> null
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAccountIcon()
    }

    private fun refreshAccountIcon() {
        val account = accountsRepo.current()
        accountIcon.imageTintList = null
        accountIcon.setImageDrawable(
            if (account != null) Avatar.of(this, account) else Avatar.unknown(this)
        )
        accountIcon.contentDescription = account?.let { "${getString(R.string.accounts)}: ${it.email}" } ?: getString(R.string.accounts)
    }

    private fun handleCapability(result: CapabilityResult) {
        if (result.status != CapabilityStatus.LAUNCHED && result.message != null) {
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateNetworkIcon(state: NetworkState) {
        val iconRes = when (state) {
            NetworkState.WIFI, NetworkState.OTHER_ONLINE -> R.drawable.ic_wifi
            NetworkState.ETHERNET -> R.drawable.ic_ethernet
            NetworkState.OFFLINE -> R.drawable.ic_wifi_off
        }
        networkIcon.setImageResource(iconRes)
    }
}
