package com.example.tvlauncher.data

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Test

/** Simple in-memory stand-in for SharedPrefsFavoritesStore. */
private class FakeFavoritesStore : FavoritesStore {
    var order: List<String>? = null
    var excluded: Set<String> = emptySet()
    override fun readOrder(): List<String>? = order
    override fun writeOrder(order: List<String>) { this.order = order }
    override fun readExcluded(): Set<String> = excluded
    override fun writeExcluded(excluded: Set<String>) { this.excluded = excluded }
}

private fun app(pkg: String, label: String = pkg) =
    AppEntry(label = label, packageName = pkg, icon = ColorDrawable(0), banner = null, launchIntent = Intent())

class FavoritesControllerTest {

    @Test
    fun `first run defaults to every discovered app in discovery order`() {
        val apps = listOf(app("a"), app("b"), app("c"))
        val controller = FavoritesController(FakeFavoritesStore(), limit = 8)

        val selection = controller.select(apps)

        assertEquals(listOf("a", "b", "c"), selection.primary.map { it.packageName })
        assertEquals(emptyList<AppEntry>(), selection.overflow)
    }

    @Test
    fun `Your apps holds seven and moving across the line swaps instead of adding`() {
        val apps = (1..9).map { app("pkg$it") }
        val controller = FavoritesController(FakeFavoritesStore())   // the real limit

        assertEquals(PRIMARY_SHELF_LIMIT, controller.select(apps).primary.size)
        controller.move(apps, 6, 1)   // the seventh app moves down past the line
        val after = controller.select(apps)

        assertEquals(7, after.primary.size)
        assertEquals("pkg8", after.primary.last().packageName)
        assertEquals("pkg7", after.overflow.first().packageName)
    }

    @Test
    fun `apps beyond the limit overflow`() {
        val apps = (1..5).map { app("pkg$it") }
        val controller = FavoritesController(FakeFavoritesStore(), limit = 3)

        val selection = controller.select(apps)

        assertEquals(listOf("pkg1", "pkg2", "pkg3"), selection.primary.map { it.packageName })
        assertEquals(listOf("pkg4", "pkg5"), selection.overflow.map { it.packageName })
    }

    @Test
    fun `uninstalled app drops out of a persisted order without crashing`() {
        val store = FakeFavoritesStore().apply { order = listOf("a", "b", "c") }
        val controller = FavoritesController(store, limit = 8)

        // "b" is no longer installed.
        val selection = controller.select(listOf(app("a"), app("c")))

        assertEquals(listOf("a", "c"), selection.primary.map { it.packageName })
    }

    @Test
    fun `newly installed app is appended rather than hidden`() {
        val store = FakeFavoritesStore().apply { order = listOf("a", "b") }
        val controller = FavoritesController(store, limit = 8)

        val selection = controller.select(listOf(app("a"), app("b"), app("new")))

        assertEquals(listOf("a", "b", "new"), selection.primary.map { it.packageName })
    }

    @Test
    fun `reinstalling a favourited app restores its old position`() {
        val store = FakeFavoritesStore().apply { order = listOf("a", "b", "c") }
        val controller = FavoritesController(store, limit = 8)

        // "b" was uninstalled and reinstalled -- a fresh AppRepository query
        // still returns it as just another discovered app, matched by name.
        val order = controller.currentShelfOrder(listOf(app("a"), app("c"), app("b")))

        assertEquals(listOf("a", "b", "c"), order.map { it.packageName })
    }

    @Test
    fun `move swaps adjacent positions and persists`() {
        val store = FakeFavoritesStore()
        val controller = FavoritesController(store, limit = 8)
        val apps = listOf(app("a"), app("b"), app("c"))
        controller.select(apps) // seed persisted order

        val moved = controller.move(apps, index = 0, delta = 1)

        assertEquals(listOf("b", "a", "c"), moved.map { it.packageName })
        assertEquals(listOf("b", "a", "c"), store.order)
    }

    @Test
    fun `move past either edge is a no-op`() {
        val controller = FavoritesController(FakeFavoritesStore(), limit = 8)
        val apps = listOf(app("a"), app("b"))

        val moved = controller.move(apps, index = 0, delta = -1)

        assertEquals(listOf("a", "b"), moved.map { it.packageName })
    }

    @Test
    fun `setIncluded false removes an app from the shelf but keeps it known`() {
        val store = FakeFavoritesStore()
        val controller = FavoritesController(store, limit = 8)
        val apps = listOf(app("a"), app("b"))
        controller.select(apps)

        val result = controller.setIncluded(apps, "a", included = false)

        assertEquals(listOf("b"), result.map { it.packageName })
        assertEquals(setOf("a"), store.excluded)
    }

    @Test
    fun `excluding an app survives a later reconciliation instead of silently reappearing`() {
        // Regression test: currentShelfOrder's "not in persisted order" reconciliation
        // must not treat an explicitly-excluded app as "newly installed" and re-add it.
        val store = FakeFavoritesStore()
        val controller = FavoritesController(store, limit = 8)
        val apps = listOf(app("a"), app("b"), app("c"))
        controller.select(apps)
        controller.setIncluded(apps, "a", included = false)

        // Simulate MainActivity re-reading apps and calling select() again, as it does on every resume.
        val reselected = controller.select(apps)

        assertEquals(listOf("b", "c"), reselected.primary.map { it.packageName })
    }

    @Test
    fun `setIncluded true re-adds a previously excluded app at its original position`() {
        val store = FakeFavoritesStore().apply {
            order = listOf("a", "b")
            excluded = setOf("a")
        }
        val controller = FavoritesController(store, limit = 8)
        val apps = listOf(app("a"), app("b"))

        val result = controller.setIncluded(apps, "a", included = true)

        assertEquals(listOf("a", "b"), result.map { it.packageName })
    }

    @Test
    fun `excludedApps lists exactly what setIncluded excluded`() {
        val store = FakeFavoritesStore()
        val controller = FavoritesController(store, limit = 8)
        val apps = listOf(app("a"), app("b"), app("c"))
        controller.select(apps)

        controller.setIncluded(apps, "b", included = false)

        assertEquals(listOf("b"), controller.excludedApps(apps).map { it.packageName })
        assertEquals(listOf("a", "c"), controller.currentShelfOrder(apps).map { it.packageName })
    }

    @Test
    fun `snapshot and restore round-trips the persisted order`() {
        val store = FakeFavoritesStore()
        val controller = FavoritesController(store, limit = 8)
        val apps = listOf(app("a"), app("b"), app("c"))
        controller.select(apps)

        val snapshot = controller.snapshotOrder(apps)
        controller.move(apps, 0, 1) // now b, a, c
        controller.restoreOrder(snapshot)

        assertEquals(listOf("a", "b", "c"), controller.currentShelfOrder(apps).map { it.packageName })
    }
}
