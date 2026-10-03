package com.example.tvlauncher.data.parents

import org.json.JSONArray
import org.json.JSONObject

/**
 * The parents' home content: one small JSON document built off the TV (see the private
 * mcm-parents-feed repo) and cached here. The TV never talks to Google Photos, YouTube's pages or
 * ESPN itself; it only renders this, so a provider changing shape is fixed off the TV.
 */
data class ParentFeed(
    val updatedAt: String,
    val danielLately: List<Photo>,
    val familyArchive: List<Photo>,
    val comingUp: List<FamilyEvent>,
    val forAmelia: ForAmelia?,
    val tonight: List<Pick>,
    val listening: Listening?,
    val sport: Sport?,
    val crochet: List<Inspiration>,
    val aria: List<Pick>,
    val localEvents: List<LocalEvent>
) {
    companion object {
        const val VERSION = 1
    }
}

/** How to open something: a URI and the apps that can take it, best first. */
data class Link(val uri: String, val packages: List<String>)

data class Photo(
    val id: String,
    val imageUrl: String,
    val thumbUrl: String,
    val width: Int,
    val height: Int,
    val caption: String?,
    val takenAt: String?,
    val firstSeenAt: String?,
    /** Archive photos only: "Tenerife", and the year it was taken. */
    val title: String? = null,
    val year: Int? = null
) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 4f / 3f
}

data class FamilyEvent(val title: String, val date: String, val endDate: String?, val allDay: Boolean)

/** A thing to watch or play: a film, a live channel, a novela clip, a concert, a podcast episode. */
data class Pick(
    val title: String,
    val subtitle: String?,
    val image: String?,
    val link: Link,
    val kind: String? = null,
    val year: Int? = null,
    val note: String? = null,
    val minutes: Int? = null
)

data class ForAmelia(val live: List<Pick>, val novelas: List<Pick>, val film: Pick?, val duolingoStreak: Int?, val shows: List<Pick> = emptyList())

data class Listening(val records: List<Pick>, val concert: Pick?, val podcasts: List<Pick>)

data class Match(
    val date: String,
    val competition: String?,
    val home: String,
    val away: String,
    val homeScore: Int?,
    val awayScore: Int?,
    val state: String,
    val venue: String?
)

data class Team(val name: String, val live: Match?, val last: Match?, val next: Match?, val position: Int?, val of: Int?, val league: String?)

data class Sport(val wolves: Team?, val ireland: Team?)

data class Inspiration(val id: String, val title: String, val image: String, val credit: String?)

data class LocalEvent(val title: String, val date: String, val venue: String?, val note: String?, val image: String?, val link: Link?)

class InvalidFeedException(message: String) : Exception(message)

/**
 * Parses and checks the feed. Anything the TV could not render (wrong version, a photo without an
 * https image, a link without a URI) rejects the whole document, so the last good feed stays on screen.
 * Optional sections that are missing or null simply come back empty.
 */
object ParentFeedParser {

    fun parse(json: String): ParentFeed {
        val root = try { JSONObject(json) } catch (e: Exception) { throw InvalidFeedException("not JSON") }
        if (root.optInt("version", -1) != ParentFeed.VERSION) throw InvalidFeedException("unsupported version")
        val updatedAt = root.optString("updatedAt").takeIf { it.length >= 10 } ?: throw InvalidFeedException("no updatedAt")
        return ParentFeed(
            updatedAt = updatedAt,
            danielLately = photos(root.optJSONObject("danielLately")?.optJSONArray("photos")),
            familyArchive = photos(root.optJSONObject("familyArchive")?.optJSONArray("photos")),
            comingUp = list(root.optJSONArray("comingUp")) { o ->
                FamilyEvent(o.req("title"), o.req("date").also { if (!DATE.matches(it.take(10))) throw InvalidFeedException("event date") },
                    o.str("endDate"), o.optBoolean("allDay", true))
            },
            forAmelia = root.optJSONObject("forAmelia")?.let { a ->
                ForAmelia(picks(a.optJSONArray("live")), picks(a.optJSONArray("novelas")), a.optJSONObject("film")?.let(::pick),
                    a.optInt("duolingoStreak", -1).takeIf { it > 0 }, picks(a.optJSONArray("shows")))
            },
            tonight = picks(root.optJSONArray("tonight")),
            listening = root.optJSONObject("listening")?.let { l ->
                Listening(picks(l.optJSONArray("records")), l.optJSONObject("concert")?.let(::pick), picks(l.optJSONArray("podcasts")))
            },
            sport = root.optJSONObject("sport")?.let { s -> Sport(s.optJSONObject("wolves")?.let(::team), s.optJSONObject("ireland")?.let(::team)) },
            crochet = list(root.optJSONArray("crochet")) { o -> Inspiration(o.req("id"), o.req("title"), https(o.req("image")), o.str("credit")) },
            aria = picks(root.optJSONArray("aria")),
            localEvents = list(root.optJSONArray("localEvents")) { o ->
                LocalEvent(o.req("title"), o.req("date"), o.str("venue"), o.str("note"), o.str("image")?.let(::https), o.optJSONObject("link")?.let(::link))
            }
        )
    }

    private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

    private fun photos(array: JSONArray?) = list(array) { o ->
        Photo(
            id = o.req("id"), imageUrl = https(o.req("imageUrl")), thumbUrl = https(o.optString("thumbUrl").ifBlank { o.req("imageUrl") }),
            width = o.optInt("width"), height = o.optInt("height"),
            caption = o.str("caption"), takenAt = o.str("takenAt"), firstSeenAt = o.str("firstSeenAt"),
            title = o.str("title"), year = o.optInt("year", 0).takeIf { it > 0 }
        )
    }

    private fun picks(array: JSONArray?) = list(array, ::pick)

    private fun pick(o: JSONObject) = Pick(
        title = o.req("title"),
        subtitle = o.str("subtitle") ?: o.str("show"),
        image = o.str("image")?.let(::https),
        link = link(o.optJSONObject("link") ?: throw InvalidFeedException("link for ${o.optString("title")}")),
        kind = o.str("kind"), year = o.optInt("year", 0).takeIf { it > 0 }, note = o.str("note"),
        minutes = o.optInt("minutes", 0).takeIf { it > 0 }
    )

    private fun link(o: JSONObject): Link {
        val uri = o.optString("uri").takeIf { it.length > 3 } ?: throw InvalidFeedException("link uri")
        val packages = o.optJSONArray("packages")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return Link(uri, packages)
    }

    private fun team(o: JSONObject): Team {
        val table = o.optJSONObject("table")
        return Team(
            name = o.optString("name", ""), live = o.optJSONObject("live")?.let(::match),
            last = o.optJSONObject("last")?.let(::match), next = o.optJSONObject("next")?.let(::match),
            position = table?.optInt("position", 0)?.takeIf { it > 0 }, of = table?.optInt("of", 0)?.takeIf { it > 0 },
            league = table?.str("league")
        )
    }

    private fun match(o: JSONObject) = Match(
        date = o.req("date"), competition = o.str("competition"), home = o.req("home"), away = o.req("away"),
        homeScore = if (o.isNull("homeScore")) null else o.optInt("homeScore"),
        awayScore = if (o.isNull("awayScore")) null else o.optInt("awayScore"),
        state = o.optString("state", "pre"), venue = o.str("venue")
    )

    private fun https(url: String): String = url.takeIf { it.startsWith("https://") } ?: throw InvalidFeedException("insecure image url")

    private fun JSONObject.req(name: String): String = optString(name).takeIf { has(name) && !isNull(name) && it.isNotBlank() }
        ?: throw InvalidFeedException("missing $name")

    private fun JSONObject.str(name: String): String? = if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null

    private fun <T> list(array: JSONArray?, item: (JSONObject) -> T): List<T> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(item) }
}
