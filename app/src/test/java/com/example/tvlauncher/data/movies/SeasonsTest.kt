package com.example.tvlauncher.data.movies

import com.example.tvlauncher.data.jellyfin.JellyfinApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SeasonsTest {
    private val tv = """{"seasons":[
        {"seasonNumber":0,"name":"Specials","episodeCount":9},
        {"seasonNumber":1,"name":"Season 1","episodeCount":7},
        {"seasonNumber":2,"name":"","episodeCount":13}],
      "mediaInfo":{"status":4,"seasons":[{"seasonNumber":1,"status":5},{"seasonNumber":2,"status":3}]}}"""

    @Test fun lists_seasons_without_specials_and_with_their_state() {
        val s = SeerrSeasons.parseSeasons(tv)
        assertEquals(listOf(1, 2), s.map { it.number }); assertEquals(listOf(7, 13), s.map { it.episodeCount })
        assertEquals(Availability.AVAILABLE, s[0].availability); assertEquals(Availability.PROCESSING, s[1].availability)
        assertEquals("Season 2", s[1].name)
    }

    @Test fun seasons_nobody_has_asked_for_are_none() {
        val s = SeerrSeasons.parseSeasons("""{"seasons":[{"seasonNumber":1,"episodeCount":3}]}""")
        assertEquals(Availability.NONE, s.single().availability)
        assertTrue(SeerrSeasons.parseSeasons("junk").isEmpty())
    }

    @Test fun reads_episodes() {
        val e = SeerrSeasons.parseEpisodes("""{"episodes":[{"episodeNumber":1,"seasonNumber":1,"name":"Pilot","overview":"Walt.","airDate":"2008-01-20"},{"episodeNumber":2,"seasonNumber":1,"name":""}]}""")
        assertEquals(listOf("Pilot", "Episode 2"), e.map { it.name }); assertEquals("2008-01-20", e[0].airDate); assertNull(e[1].overview)
    }

    @Test fun a_request_names_its_seasons_or_asks_for_all() {
        val one = JSONObject(SeerrApi.requestBody(Kind.SHOW, 1396, listOf(2)))
        assertEquals(2, one.getJSONArray("seasons").getInt(0)); assertEquals(1, one.getJSONArray("seasons").length())
        assertEquals("all", JSONObject(SeerrApi.requestBody(Kind.SHOW, 1396)).getString("seasons"))
        assertFalse(JSONObject(SeerrApi.requestBody(Kind.MOVIE, 5, listOf(1))).has("seasons"))
    }

    @Test fun playback_reports_use_ticks_and_the_right_addresses() {
        val b = JSONObject(JellyfinApi.playbackBody("item1", "src1", 90_000L, paused = true, sessionId = "sess"))
        assertEquals(900_000_000L, b.getLong("PositionTicks")); assertEquals("item1", b.getString("ItemId")); assertEquals("src1", b.getString("MediaSourceId"))
        assertTrue(b.getBoolean("IsPaused")); assertEquals("sess", b.getString("PlaySessionId"))
        assertEquals("http://d/Sessions/Playing", JellyfinApi.reportUrl("http://d", "Playing"))
        assertEquals("http://d/Sessions/Playing/Progress", JellyfinApi.reportUrl("http://d", "Progress"))
        assertEquals("http://d/Sessions/Playing/Stopped", JellyfinApi.reportUrl("http://d", "Stopped"))
        assertEquals(0L, JSONObject(JellyfinApi.playbackBody("i", "s", -5, false, "x")).getLong("PositionTicks"))
    }

    @Test fun episodes_url_names_the_series() {
        assertTrue(JellyfinApi.episodesUrl("http://d", "u", "ser1").contains("/Shows/ser1/Episodes?userId=u"))
    }
}
