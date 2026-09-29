package com.example.tvlauncher.data.listening

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * What Daniel and Eva are listening to, normalised into one model so the turntable UI never
 * cares which service it came from. Providers plug in through [ListeningSource].
 */
enum class Person(val displayName: String, val key: String) {
    EVA("Eva", "eva"), DANIEL("Daniel", "daniel")
}

enum class MusicProvider(val label: String) {
    SPOTIFY("Spotify"), APPLE_MUSIC("Apple Music"), LASTFM("Last.fm"), DEMO("Demo data");

    companion object {
        fun parse(value: String?): MusicProvider = values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LASTFM
    }
}

enum class ListeningStatus { PLAYING_NOW, PAUSED, RECENTLY_PLAYED, NOTHING_AVAILABLE }

/**
 * [observedAtMs] is epoch milliseconds (java.time.Instant needs API 26; this app supports 24).
 * For PLAYING_NOW, [progressMs] is the position at [observedAtMs].
 */
data class PersonalListeningState(
    val person: Person,
    val provider: MusicProvider,
    val status: ListeningStatus,
    val trackTitle: String?,
    val artistName: String?,
    val albumName: String?,
    val artworkUrl: String?,
    val progressMs: Long?,
    val durationMs: Long?,
    val observedAtMs: Long?
) {
    companion object {
        fun nothing(person: Person, provider: MusicProvider) =
            PersonalListeningState(person, provider, ListeningStatus.NOTHING_AVAILABLE, null, null, null, null, null, null, null)
    }

    /** Playback position now, extrapolated from the observation while playing. */
    fun positionMs(nowMs: Long): Long? {
        val base = progressMs ?: return null
        val moved = if (status == ListeningStatus.PLAYING_NOW && observedAtMs != null) nowMs - observedAtMs else 0L
        return (base + moved).coerceIn(0L, durationMs ?: Long.MAX_VALUE)
    }

    /** One short, human status line for the plaque. */
    fun statusLine(nowMs: Long): String = when (status) {
        ListeningStatus.PLAYING_NOW -> positionMs(nowMs)?.let { "Now spinning · ${clock(it)}" } ?: "Now spinning"
        ListeningStatus.PAUSED -> "Paused"
        ListeningStatus.RECENTLY_PLAYED -> observedAtMs?.let { "Played ${ago(nowMs - it)}" } ?: "Played recently"
        ListeningStatus.NOTHING_AVAILABLE -> "Nothing on the platter"
    }

    private fun clock(ms: Long) = String.format(Locale.UK, "%d:%02d", ms / 60000, (ms / 1000) % 60)

    private fun ago(ms: Long): String {
        val minutes = (ms / 60000).coerceAtLeast(0)
        return when {
            minutes < 2 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }
}

/** Blocking; call off the main thread. Never throws: failures come back as NOTHING_AVAILABLE. */
interface ListeningSource {
    fun fetch(person: Person): PersonalListeningState
    /** True when the data is invented for the demo rather than read from a service. */
    val isDemo: Boolean get() = false
}

/** Reads assets/home/listening.json once. */
class ListeningConfig(context: Context) {
    val json: JSONObject = try {
        JSONObject(context.assets.open("home/listening.json").bufferedReader().use { it.readText() })
    } catch (_: Exception) { JSONObject() }

    val pollSeconds: Int get() = json.optInt("pollSeconds", 30).coerceAtLeast(10)

    fun person(person: Person): JSONObject = json.optJSONObject("people")?.optJSONObject(person.key) ?: JSONObject()
}

/**
 * Chooses a real source per person when a Last.fm user and key are configured (Spotify and
 * Apple Music can both scrobble to Last.fm), otherwise falls back to the demo data.
 */
class ConfiguredListeningSource(context: Context, private val config: ListeningConfig = ListeningConfig(context)) : ListeningSource {
    private val demo = DemoListeningSource(config)

    override val isDemo: Boolean get() = Person.values().any { !usesLastFm(it) }

    private fun usesLastFm(person: Person): Boolean {
        val p = config.person(person)
        return p.optString("lastfmUser").isNotBlank() && p.optString("lastfmApiKey").isNotBlank()
    }

    override fun fetch(person: Person): PersonalListeningState {
        if (!usesLastFm(person)) return demo.fetch(person)
        val p = config.person(person)
        return LastFmListeningSource(person, MusicProvider.parse(p.optString("provider")), p.getString("lastfmUser"), p.getString("lastfmApiKey")).fetch()
    }
}

/** Real data: the latest scrobble from Last.fm's public API. "nowplaying" means it is playing right now. */
class LastFmListeningSource(
    private val person: Person,
    private val provider: MusicProvider,
    private val user: String,
    private val apiKey: String
) {
    fun fetch(): PersonalListeningState = try {
        val url = "https://ws.audioscrobbler.com/2.0/?method=user.getrecenttracks&user=${java.net.URLEncoder.encode(user, "UTF-8")}" +
            "&api_key=$apiKey&format=json&limit=1"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        val body = try {
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
        parse(JSONObject(body))
    } catch (_: Exception) { PersonalListeningState.nothing(person, provider) }

    private fun parse(root: JSONObject): PersonalListeningState {
        val tracks = root.getJSONObject("recenttracks").opt("track")
        val track = when (tracks) {
            is org.json.JSONArray -> if (tracks.length() > 0) tracks.getJSONObject(0) else return PersonalListeningState.nothing(person, provider)
            is JSONObject -> tracks
            else -> return PersonalListeningState.nothing(person, provider)
        }
        val playing = track.optJSONObject("@attr")?.optString("nowplaying") == "true"
        val playedAt = track.optJSONObject("date")?.optLong("uts", 0L)?.takeIf { it > 0 }?.times(1000)
        val now = System.currentTimeMillis()
        val status = when {
            playing -> ListeningStatus.PLAYING_NOW
            playedAt != null && now - playedAt < 3L * 60 * 60 * 1000 -> ListeningStatus.RECENTLY_PLAYED
            else -> return PersonalListeningState.nothing(person, provider)
        }
        val images = track.optJSONArray("image")
        var art: String? = null
        if (images != null) for (i in images.length() - 1 downTo 0) {
            val candidate = images.getJSONObject(i).optString("#text")
            if (candidate.isNotBlank() && !candidate.contains("2a96cbd8b46e442fc41c2b86b821562f")) { art = candidate; break }
        }
        return PersonalListeningState(
            person, provider, status,
            track.optString("name").ifBlank { null },
            track.optJSONObject("artist")?.optString("#text")?.ifBlank { null },
            track.optJSONObject("album")?.optString("#text")?.ifBlank { null },
            art, null, null, if (playing) now else playedAt
        )
    }
}

/**
 * Invented but plausible listening for the demo: each person cycles through their entries in
 * listening.json every [SLOT_MS], so the turntables visibly change state without any account.
 */
class DemoListeningSource(private val config: ListeningConfig) : ListeningSource {
    override val isDemo: Boolean = true

    override fun fetch(person: Person): PersonalListeningState {
        val entries = config.json.optJSONArray("demo")?.let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.filter { it.optString("person").equals(person.key, true) }
        }.orEmpty()
        val provider = if (config.person(person).has("provider")) MusicProvider.parse(config.person(person).optString("provider")) else MusicProvider.DEMO
        if (entries.isEmpty()) return PersonalListeningState.nothing(person, provider)

        val now = System.currentTimeMillis()
        val slot = now / SLOT_MS
        val entry = entries[Math.floorMod(slot + person.ordinal * 2, entries.size.toLong()).toInt()]
        val slotStart = slot * SLOT_MS
        val status = try { ListeningStatus.valueOf(entry.optString("status", "PLAYING_NOW")) } catch (_: Exception) { ListeningStatus.PLAYING_NOW }
        val duration = entry.optLong("durationSec", 200) * 1000
        return PersonalListeningState(
            person, provider, status,
            entry.optString("track").ifBlank { null }, entry.optString("artist").ifBlank { null }, entry.optString("album").ifBlank { null },
            null,
            if (status == ListeningStatus.PLAYING_NOW || status == ListeningStatus.PAUSED) (now - slotStart).coerceAtMost(duration - 1000) else null,
            duration,
            if (status == ListeningStatus.RECENTLY_PLAYED) now - 25 * 60 * 1000 else now
        )
    }

    private companion object { const val SLOT_MS = 90_000L }
}
