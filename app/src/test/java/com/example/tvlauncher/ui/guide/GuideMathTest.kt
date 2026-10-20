package com.example.tvlauncher.ui.guide

import com.example.tvlauncher.data.jellyfin.JellyfinApi
import org.junit.Assert.*
import org.junit.Test

class GuideMathTest {
    private val min = 60_000L
    private val t0 = 1_792_500_000_000L - (1_792_500_000_000L % (30 * min))   // a half-hour boundary
    private val end = t0 + 360 * min

    @Test fun window_opens_on_the_half_hour_just_gone() {
        assertEquals(t0, GuideMath.windowStart(t0 + 17 * min))
        assertEquals(t0, GuideMath.windowStart(t0))
        assertEquals(t0 + 30 * min, GuideMath.windowStart(t0 + 30 * min + 1))
    }

    @Test fun places_a_programme_by_its_minutes() {
        val s = GuideMath.span(t0 + 30 * min, t0 + 90 * min, t0, end, 8f)!!
        assertEquals(240, s.offsetDp); assertEquals(480, s.widthDp)
        assertFalse(s.cutOnLeft); assertFalse(s.cutOnRight)
    }

    @Test fun clips_to_the_window_and_says_so() {
        val left = GuideMath.span(t0 - 20 * min, t0 + 10 * min, t0, end, 8f)!!
        assertEquals(0, left.offsetDp); assertEquals(80, left.widthDp); assertTrue(left.cutOnLeft)
        val right = GuideMath.span(end - 10 * min, end + 50 * min, t0, end, 8f)!!
        assertEquals(80, right.widthDp); assertTrue(right.cutOnRight)
    }

    @Test fun programmes_outside_the_window_are_dropped() {
        assertNull(GuideMath.span(t0 - 60 * min, t0, t0, end, 8f))       // ends exactly as the window opens
        assertNull(GuideMath.span(end, end + 30 * min, t0, end, 8f))     // starts exactly as it closes
        assertNull(GuideMath.span(t0 + 10 * min, t0 + 10 * min, t0, end, 8f))   // zero length
    }

    @Test fun progress_and_on_now() {
        assertEquals(0.5f, GuideMath.progress(t0, t0 + 20 * min, t0 + 10 * min), 0.001f)
        assertEquals(0f, GuideMath.progress(t0 + 10 * min, t0 + 20 * min, t0), 0f)
        assertEquals(1f, GuideMath.progress(t0, t0 + 10 * min, t0 + 99 * min), 0f)
        assertTrue(GuideMath.isOnNow(t0, t0 + 10 * min, t0))
        assertFalse(GuideMath.isOnNow(t0, t0 + 10 * min, t0 + 10 * min))
    }

    @Test fun one_tick_per_half_hour() {
        val ticks = GuideMath.ticks(t0, end)
        assertEquals(12, ticks.size); assertEquals(t0, ticks.first()); assertEquals(end - 30 * min, ticks.last())
    }

    @Test fun reads_programmes_in_start_order() {
        val json = """{"Items":[
          {"Id":"b","ChannelId":"c1","Name":"Second","StartDate":"2026-10-20T13:42:26.0000000Z","EndDate":"2026-10-20T13:52:48.0000000Z","ProductionYear":1933},
          {"Id":"a","ChannelId":"c1","Name":"First","Overview":"Vintage.","StartDate":"2026-10-20T13:35:22.0000000Z","EndDate":"2026-10-20T13:42:26.0000000Z"},
          {"Id":"x","Name":"No channel","StartDate":"2026-10-20T13:35:22.0000000Z","EndDate":"2026-10-20T13:42:26.0000000Z"}]}"""
        val p = JellyfinApi.parsePrograms(json)
        assertEquals(listOf("First", "Second"), p.map { it.title })
        assertEquals("Vintage.", p[0].overview); assertNull(p[1].overview); assertEquals(1933, p[1].year)
        assertTrue(JellyfinApi.parsePrograms("junk").isEmpty())
    }

    @Test fun programme_request_asks_for_a_time_range() {
        val url = JellyfinApi.programsUrl("http://deck:8096", "u1", 1_792_500_000_000L, 1_792_521_600_000L)
        assertTrue(url.contains("minEndDate=2026-10-20T")); assertTrue(url.contains("maxStartDate=")); assertTrue(url.contains("Fields=Overview"))
    }
}
