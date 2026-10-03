package com.example.tvlauncher.data.parents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ParentsFormatTest {
    /** Saturday 3 October 2026, 20:00 in Ireland (IST, UTC+1). */
    private val now = ParentsFormat.parse("2026-10-03T19:00:00Z")!!

    private fun event(date: String, end: String? = null) = FamilyEvent("x", date, end, true)

    @Test fun eventWording() {
        assertEquals("today", ParentsFormat.eventWhen(event("2026-10-03"), now))
        assertEquals("tomorrow", ParentsFormat.eventWhen(event("2026-10-04"), now))
        assertEquals("Friday", ParentsFormat.eventWhen(event("2026-10-09"), now))
        assertEquals("in 12 days", ParentsFormat.eventWhen(event("2026-10-15"), now))
        assertEquals("18 Nov", ParentsFormat.eventWhen(event("2026-11-18"), now))
        assertEquals("now", ParentsFormat.eventWhen(event("2026-10-01", "2026-10-05"), now))
        assertNull(ParentsFormat.eventWhen(event("2026-09-30"), now))
    }

    @Test fun upcomingDropsThePastAndSorts() {
        val list = ParentsFormat.upcoming(listOf(event("2026-11-01"), event("2026-09-01"), event("2026-10-05")), now)
        assertEquals(listOf("2026-10-05", "2026-11-01"), list.map { it.date })
    }

    @Test fun lateNightUtcIsAlreadyTomorrowInIreland() {
        // 23:30 UTC on the 3rd is 00:30 on the 4th in Ireland.
        assertEquals("today", ParentsFormat.eventWhen(event("2026-10-04"), ParentsFormat.parse("2026-10-03T23:30:00Z")!!))
    }

    @Test fun kickoffInIrishTime() {
        assertEquals("Sat 10 Oct · 3pm", ParentsFormat.kickoff("2026-10-10T14:00Z", now))
        assertEquals("Tomorrow · 7.45pm", ParentsFormat.kickoff("2026-10-04T18:45Z", now))
    }

    @Test fun matchWords() {
        val m = Match("2026-09-20T11:00Z", "Championship", "Wolves", "West Brom", 1, 0, "post", null)
        assertEquals("Wolves 1–0 West Brom", ParentsFormat.score(m))
        assertEquals("Won", ParentsFormat.outcome(m, "Wolves"))
        assertEquals("Lost", ParentsFormat.outcome(m, "West Brom"))
        assertEquals("4th", ParentsFormat.ordinal(4)); assertEquals("21st", ParentsFormat.ordinal(21)); assertEquals("12th", ParentsFormat.ordinal(12))
        assertEquals("Championship", ParentsFormat.leagueName("2026-27 English League Championship"))
    }

    @Test fun addedWording() {
        assertEquals("today", ParentsFormat.addedWhen("2026-10-03T08:00:00Z", now))
        assertEquals("yesterday", ParentsFormat.addedWhen("2026-10-02T08:00:00Z", now))
        assertEquals("on Thursday", ParentsFormat.addedWhen("2026-10-01T08:00:00Z", now))
        assertEquals("on 12 September", ParentsFormat.addedWhen("2026-09-12T08:00:00Z", now))
    }

    // --- Daniel, lately ---

    private fun photo(id: String, firstSeen: String) = Photo(id, "https://x/$id", "https://x/$id", 4, 3, null, null, firstSeen)

    @Test fun aFreshPhotoLeads() {
        val photos = listOf(photo("new", "2026-10-03T09:00:00Z"), photo("b", "2026-09-20T09:00:00Z"), photo("c", "2026-09-10T09:00:00Z"))
        val a = DanielLatelyArrangement.arrange(photos, now)!!
        assertEquals("new", a.lead.id)
        assertEquals(listOf("b", "c"), a.others.map { it.id })
        assertEquals(1, a.recentCount)
    }

    @Test fun withoutFreshPhotosTheLeadRotatesDailyButIsStableWithinADay() {
        val photos = (1..5).map { photo("p$it", "2026-09-0${it}T09:00:00Z") }
        val today = DanielLatelyArrangement.arrange(photos, now)!!
        val laterToday = DanielLatelyArrangement.arrange(photos, now + 2 * 3600_000L)!!
        val tomorrow = DanielLatelyArrangement.arrange(photos, now + 24 * 3600_000L)!!
        assertSame(today.lead, laterToday.lead)
        assert(today.lead !== tomorrow.lead)
        assertEquals(5, today.all.size)
        assertEquals(today.lead, today.all.first())
    }

    @Test fun olderPhotosFillInWhenNothingIsRecent() {
        val a = DanielLatelyArrangement.arrange(listOf(photo("old", "2026-06-01T09:00:00Z")), now)!!
        assertEquals("old", a.lead.id)
        assertEquals(0, a.recentCount)
        assertNull(DanielLatelyArrangement.arrange(emptyList(), now))
    }

    @Test fun sampleSizeKeepsAtLeastTheDrawnSize() {
        assertEquals(1, FeedImages.sampleSize(1920, 1080, 1920, 1080))
        assertEquals(2, FeedImages.sampleSize(1920, 1080, 720, 405))
        assertEquals(4, FeedImages.sampleSize(4000, 3000, 720, 540))
    }
}
