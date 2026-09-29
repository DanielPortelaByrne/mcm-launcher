package com.example.tvlauncher.data

import android.content.Context

/**
 * How many apps "Your apps" holds. Seven apps plus the All apps tile fill the row exactly between the
 * page margins. Everything past seven is "More apps", so adding an app to Your apps is always a swap.
 */
const val PRIMARY_SHELF_LIMIT = 7

data class AppSelection(
    val primary: List<AppEntry>,
    val overflow: List<AppEntry>
)

/**
 * Persists every discovered app's display order (both shelf and excluded)
 * plus which package names are explicitly excluded. Kept as an interface
 * so [FavoritesController] is testable without a real Android [Context].
 */
interface FavoritesStore {
    fun readOrder(): List<String>?
    fun writeOrder(order: List<String>)
    fun readExcluded(): Set<String>
    fun writeExcluded(excluded: Set<String>)
}

class SharedPrefsFavoritesStore(context: Context) : FavoritesStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun readOrder(): List<String>? =
        prefs.getString(KEY_ORDER, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }

    override fun writeOrder(order: List<String>) {
        prefs.edit().putString(KEY_ORDER, order.joinToString(SEPARATOR)).apply()
    }

    override fun readExcluded(): Set<String> =
        prefs.getString(KEY_EXCLUDED, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    override fun writeExcluded(excluded: Set<String>) {
        prefs.edit().putString(KEY_EXCLUDED, excluded.joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val PREFS_NAME = "favorites"
        const val KEY_ORDER = "shelf_order"
        const val KEY_EXCLUDED = "excluded_packages"
        const val SEPARATOR = ""
    }
}

/**
 * Owns which discovered apps sit on the "Your apps" shelf, and in what
 * order. Backed by [store] so the owner's choices survive a launcher
 * restart, an app update, or a reboot.
 *
 * The persisted "order" always covers *every* discovered app, included or
 * not -- exclusion is tracked separately. That separation matters: an app
 * absent from the shelf must stay absent on the next read even though it's
 * still installed, which is different from a genuinely newly-installed
 * app that should default to visible. Conflating the two (deriving
 * "excluded" from "just not in the persisted shelf list") would make
 * excluding an app indistinguishable from never having seen it, so the
 * very next reconciliation would silently re-add it.
 *
 * - First run (nothing persisted yet): every discovered app, alphabetically
 *   -- a sensible default with no setup required.
 * - Every later run: the persisted order, reconciled against what's
 *   actually installed right now. An uninstalled app quietly drops out
 *   (no crash, no dead tile); a newly installed app is appended so it's
 *   never invisible; reinstalling a previously-favourited app restores its
 *   old position because packages are matched by name, not list index.
 */
class FavoritesController(
    private val store: FavoritesStore,
    private val limit: Int = PRIMARY_SHELF_LIMIT
) {

    /** Every discovered app (shelf + excluded), in persisted order, reconciled with what's installed now. */
    private fun fullOrder(allApps: List<AppEntry>): List<AppEntry> {
        val byPackage = allApps.associateBy { it.packageName }
        val persisted = store.readOrder() ?: return allApps
        val kept = persisted.mapNotNull { byPackage[it] }
        val known = kept.map { it.packageName }.toSet()
        val newlyInstalled = allApps.filter { it.packageName !in known }
        return kept + newlyInstalled
    }

    /** The shelf list (pre-overflow-cut): [fullOrder] minus explicitly excluded apps. */
    fun currentShelfOrder(allApps: List<AppEntry>): List<AppEntry> {
        val excluded = store.readExcluded()
        return fullOrder(allApps).filter { it.packageName !in excluded }
    }

    /** Everything NOT on the shelf, in the same persisted order -- used by the "Edit your apps" checklist. */
    fun excludedApps(allApps: List<AppEntry>): List<AppEntry> {
        val excluded = store.readExcluded()
        return fullOrder(allApps).filter { it.packageName in excluded }
    }

    /** [currentShelfOrder] split into what the shelf row shows vs. overflow, persisting any reconciliation. */
    fun select(allApps: List<AppEntry>): AppSelection {
        persistIfChanged(allApps)
        val ordered = currentShelfOrder(allApps)
        return if (ordered.size <= limit) {
            AppSelection(primary = ordered, overflow = emptyList())
        } else {
            AppSelection(primary = ordered.take(limit), overflow = ordered.drop(limit))
        }
    }

    /** Moves the shelf item at [index] by [delta] positions. Clamped: a no-op past either edge. */
    fun move(allApps: List<AppEntry>, index: Int, delta: Int): List<AppEntry> {
        val ordered = currentShelfOrder(allApps).toMutableList()
        val target = index + delta
        if (index !in ordered.indices || target !in ordered.indices) return ordered
        val item = ordered.removeAt(index)
        ordered.add(target, item)
        store.writeOrder((ordered + excludedApps(allApps)).map { it.packageName })
        return ordered
    }

    /** Adds/removes [packageName] from the shelf (the "Edit your apps" checklist action). */
    fun setIncluded(allApps: List<AppEntry>, packageName: String, included: Boolean): List<AppEntry> {
        val excluded = store.readExcluded().toMutableSet()
        if (included) excluded.remove(packageName) else excluded.add(packageName)
        store.writeExcluded(excluded)
        store.writeOrder(fullOrder(allApps).map { it.packageName }) // lock in reconciliation now
        return currentShelfOrder(allApps)
    }

    /** Snapshot of the full persisted order (shelf + excluded), for organise-mode cancel. */
    fun snapshotOrder(allApps: List<AppEntry>): List<String> = fullOrder(allApps).map { it.packageName }

    /** Restores a previously-[snapshotOrder]'d order verbatim (organise-mode Back/cancel). */
    fun restoreOrder(snapshot: List<String>) = store.writeOrder(snapshot)

    private fun persistIfChanged(allApps: List<AppEntry>) {
        val next = fullOrder(allApps).map { it.packageName }
        if (store.readOrder() != next) store.writeOrder(next)
    }
}
