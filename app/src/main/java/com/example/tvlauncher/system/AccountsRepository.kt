package com.example.tvlauncher.system

import android.Manifest
import android.accounts.AccountManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

private const val TAG = "TvLauncher"
private const val GOOGLE = "com.google"

data class TvAccount(val email: String, val name: String? = null, val photo: String? = null) {
    /** Friendly name when known, otherwise the part of the address before the '@'. */
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: email.substringBefore('@')
    val initial: String get() = label.firstOrNull { it.isLetter() }?.uppercase() ?: "?"
}

/**
 * The TV's Google accounts. Google TV keeps its own "active profile" private to
 * the stock launcher, so the launcher remembers which account the owner last
 * picked here and falls back to the first account.
 */
class AccountsRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.GET_ACCOUNTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Android 8+ hides Google accounts from apps until the owner picks them in the
     * system account picker, so this is the union of what the system currently
     * exposes and what has been picked here before (kept only while still on the TV).
     */
    fun accounts(): List<TvAccount> {
        val remembered = configured() + prefs.getStringSet("known", emptySet()).orEmpty().map { entry ->
            TvAccount(entry.substringBefore('|'), entry.substringAfter('|', "").ifBlank { null })
        }
        val names = remembered.associate { it.email to it.name }
        val visible = visibleAccounts().map { TvAccount(it.email, names[it.email]) }
        val photos = configured().associate { it.email to it.photo }
        return (remembered + visible).distinctBy { it.email }.sortedBy { it.email }.map { it.copy(photo = photos[it.email]) }
    }

    /**
     * Accounts listed in assets/home/accounts.json. Android hides Google accounts from apps and the
     * system picker no longer works without the stock Google TV home, so the list is kept here.
     */
    private fun configured(): List<TvAccount> = try {
        val array = org.json.JSONArray(context.assets.open("home/accounts.json").bufferedReader().use { it.readText() })
        (0 until array.length()).map { val o = array.getJSONObject(it); TvAccount(o.getString("email"), o.optString("name").ifBlank { null }, o.optString("photo").ifBlank { null }) }
    } catch (_: Exception) { emptyList() }

    private fun visibleAccounts(): List<TvAccount> {
        if (!hasPermission()) return emptyList()
        return try {
            AccountManager.get(context).getAccountsByType(GOOGLE).map { TvAccount(it.name) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read accounts: ${e.message}")
            emptyList()
        }
    }

    /** Records an account picked in the system picker so it stays in the dropdown. */
    fun remember(account: TvAccount) {
        val known = prefs.getStringSet("known", emptySet()).orEmpty().filterNot { it.substringBefore('|') == account.email }.toMutableSet()
        known += if (account.name.isNullOrBlank()) account.email else "${account.email}|${account.name}"
        prefs.edit().putStringSet("known", known).apply()
    }

    fun current(): TvAccount? {
        val all = accounts()
        val saved = prefs.getString("current", null)
        return all.firstOrNull { it.email == saved } ?: all.firstOrNull()
    }

    fun setCurrent(account: TvAccount) {
        val known = accounts().firstOrNull { it.email == account.email }
        remember(if (account.name == null && known != null) known else account)
        prefs.edit().putString("current", account.email).apply()
    }

    /** Boot count of the last time the "Who's watching?" picker was shown. */
    var pickerBoot: Int
        get() = prefs.getInt("pickerBoot", -1)
        set(value) { prefs.edit().putInt("pickerBoot", value).apply() }

    var askedForPermission: Boolean
        get() = prefs.getBoolean("asked", false)
        set(value) { prefs.edit().putBoolean("asked", value).apply() }
}
