package com.example.tvlauncher.data.parents

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Words and choices for the parents' home, kept free of Android views so they are unit tested.
 * Dates are read in Ireland's time zone. (No java.time: the Fire TV Stick runs Android 7.1, API 25.)
 */
object ParentsFormat {
    val IRELAND: TimeZone = TimeZone.getTimeZone("Europe/Dublin")
    private val UK = Locale.UK

    private val ISO = Regex("(\\d{4})-(\\d{2})-(\\d{2})(?:T(\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?)?")

    /** Milliseconds for an ISO date/time. Date-only and zone-less values are read as Irish local time. */
    fun parse(iso: String?): Long? {
        val m = ISO.find(iso ?: return null) ?: return null
        val g = m.groupValues
        val zone = when {
            g[7] == "Z" -> TimeZone.getTimeZone("UTC")
            g[7].isNotEmpty() -> TimeZone.getTimeZone("GMT" + g[7].let { if (it.contains(':')) it else it.substring(0, 3) + ":" + it.substring(3) })
            else -> IRELAND
        }
        return Calendar.getInstance(zone).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].ifEmpty { "0" }.toInt(), g[5].ifEmpty { "0" }.toInt(), g[6].ifEmpty { "0" }.toInt())
        }.timeInMillis
    }

    /** Whole days from [now]'s date to [then]'s date, in Ireland. */
    fun daysBetween(now: Long, then: Long): Int = (dayNumber(then) - dayNumber(now)).toInt()

    private fun dayNumber(ms: Long): Long {
        val c = Calendar.getInstance(IRELAND).apply { timeInMillis = ms }
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)) }
        return utc.timeInMillis / 86_400_000L
    }

    private fun fmt(pattern: String, ms: Long, locale: Locale = UK) =
        java.text.SimpleDateFormat(pattern, locale).apply { timeZone = IRELAND }.format(java.util.Date(ms))

    /** "today", "yesterday", "on Thursday", "on 12 September". For when a photo arrived. */
    fun addedWhen(iso: String?, now: Long): String? {
        val t = parse(iso) ?: return null
        return when (val d = -daysBetween(now, t)) {
            0 -> "today"
            1 -> "yesterday"
            in 2..6 -> "on " + fmt("EEEE", t)
            else -> "on " + fmt("d MMMM", t)
        }
    }

    /** "Wednesday 1 October" for a photo's own date. */
    fun longDate(iso: String?): String? = parse(iso)?.let { fmt("EEEE d MMMM", it) }

    /**
     * When a family event is, the way you'd say it: "today", "tomorrow", "Saturday" (this week),
     * "in 12 days" (this month), "18 Oct" (later). Null once it has passed.
     */
    fun eventWhen(event: FamilyEvent, now: Long): String? {
        val start = parse(event.date) ?: return null
        val end = parse(event.endDate) ?: start
        val days = daysBetween(now, start)
        return when {
            days < 0 && daysBetween(now, end) >= 0 -> "now"
            days < 0 -> null
            days == 0 -> "today"
            days == 1 -> "tomorrow"
            days < 7 -> fmt("EEEE", start)
            days < 31 -> "in $days days"
            else -> fmt("d MMM", start)
        }
    }

    /** Upcoming events only, soonest first. */
    fun upcoming(events: List<FamilyEvent>, now: Long): List<FamilyEvent> =
        events.filter { eventWhen(it, now) != null }.sortedBy { parse(it.date) }

    /** "Sat 10 Oct · 3pm", "Today · 7.45pm" for a kick-off. */
    fun kickoff(iso: String, now: Long): String {
        val t = parse(iso) ?: return ""
        val c = Calendar.getInstance(IRELAND).apply { timeInMillis = t }
        val time = (if (c.get(Calendar.HOUR) == 0) "12" else c.get(Calendar.HOUR).toString()) +
            (if (c.get(Calendar.MINUTE) == 0) "" else "." + "%02d".format(c.get(Calendar.MINUTE))) +
            (if (c.get(Calendar.AM_PM) == Calendar.AM) "am" else "pm")
        val day = when (daysBetween(now, t)) { 0 -> "Today"; 1 -> "Tomorrow"; else -> fmt("EEE d MMM", t) }
        return "$day · $time"
    }

    /** "Wolves 1–0 West Brom". */
    fun score(m: Match): String = "${m.home} ${m.homeScore ?: 0}–${m.awayScore ?: 0} ${m.away}"

    /** "Won", "Drew", "Lost" from [team]'s side. */
    fun outcome(m: Match, team: String): String? {
        val us = when (team) { m.home -> m.homeScore; m.away -> m.awayScore; else -> return null } ?: return null
        val them = (if (team == m.home) m.awayScore else m.homeScore) ?: return null
        return when { us > them -> "Won"; us < them -> "Lost"; else -> "Drew" }
    }

    fun ordinal(n: Int): String = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"
        else -> "th"
    }

    /** "Championship" from "2026-27 English League Championship". */
    fun leagueName(raw: String?): String? = raw?.replace(Regex("^\\d{4}(-\\d{2,4})?\\s+"), "")?.replace("English League ", "")?.replace("English ", "")

    /** Day number in Ireland, for choices that change once a day. */
    fun dayIndex(now: Long): Int = dayNumber(now).toInt()

    /** "Boa noite" / "Good evening" style greetings, by the hour in Ireland. */
    fun partOfDay(now: Long): Int = Calendar.getInstance(IRELAND).apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
}

/**
 * Which photos Daniel, lately shows, and in what order. The feed is newest first. Something added in
 * the last two days leads (news); otherwise the lead print changes once a day among the eight newest, so
 * the shelf feels alive between visits without ever moving while it is watched.
 */
object DanielLatelyArrangement {
    const val FRESH_DAYS = 2
    const val RECENT_DAYS = 7

    data class Arranged(val lead: Photo, val others: List<Photo>, val all: List<Photo>, val recentCount: Int)

    fun arrange(photos: List<Photo>, now: Long, prints: Int = 4): Arranged? {
        if (photos.isEmpty()) return null
        val age = { p: Photo -> ParentsFormat.parse(p.firstSeenAt)?.let { -ParentsFormat.daysBetween(now, it) } ?: Int.MAX_VALUE }
        val newest = photos.first()
        val lead = if (age(newest) <= FRESH_DAYS) newest else {
            val pool = photos.take(8)
            pool[Math.floorMod(ParentsFormat.dayIndex(now), pool.size)]
        }
        val others = photos.filter { it !== lead }.take(prints - 1)
        return Arranged(lead, others, listOf(lead) + photos.filter { it !== lead }, photos.count { age(it) <= RECENT_DAYS })
    }
}
