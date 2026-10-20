package com.example.tvlauncher.data.movies

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MoviesDataTest {
    private val answer = """{"data":{"popularTitles":{"edges":[
      {"node":{"id":"tm1","objectType":"MOVIE","content":{"title":"Doctor Sleep","originalReleaseYear":2019,"shortDescription":"Danny.","runtime":152,
        "genres":[{"translation":"Horror"},{"translation":"Drama"}],"ageCertification":"15","posterUrl":"/poster/1/{profile}/doctor-sleep.{format}",
        "backdrops":[{"backdropUrl":"/backdrop/2/s1440/doctor-sleep.jpg"}],"externalIds":{"imdbId":"tt5606664","tmdbId":"501170"},"scoring":{"imdbScore":7.3}}}},
      {"node":{"id":"ts2","objectType":"SHOW","content":{"title":"TYSON","originalReleaseYear":2026,"runtime":66,"genres":[],"ageCertification":"",
        "posterUrl":"","backdrops":[],"externalIds":{"imdbId":"","tmdbId":"335536"}}}},
      {"node":{"id":"tm1b","objectType":"MOVIE","content":{"title":"Doctor Sleep","originalReleaseYear":2019,"externalIds":{"imdbId":"tt5606664","tmdbId":"501170"}}}},
      {"node":{"id":"bad","objectType":"MOVIE","content":{"title":""}}}
    ]}}}"""

    @Test fun reads_titles_with_ids_images_and_meta() {
        val t = Catalog.parse(answer)
        assertEquals(3, t.size)   // the nameless one is dropped
        with(t[0]) {
            assertEquals(Kind.MOVIE, kind); assertEquals("tt5606664", imdbId); assertEquals("501170", tmdbId)
            assertEquals("https://images.justwatch.com/poster/1/s332/doctor-sleep.jpg", posterUrl)
            assertEquals("https://images.justwatch.com/backdrop/2/s1440/doctor-sleep.jpg", backdropUrl)
            assertEquals(7.3, imdbScore!!, 0.001)
            assertEquals("2019  ·  15  ·  2h 32m  ·  Horror, Drama", metaLine())
        }
        assertEquals(Kind.SHOW, t[1].kind); assertNull(t[1].imdbId); assertNull(t[1].posterUrl); assertNull(t[1].age)
    }

    @Test fun one_film_on_two_services_appears_once() {
        assertEquals(2, Catalog.dedupe(Catalog.parse(answer)).size)
    }

    @Test fun bad_answers_are_an_empty_shelf() {
        assertTrue(Catalog.parse("nope").isEmpty())
        assertTrue(Catalog.parse("""{"data":{}}""").isEmpty())
    }

    @Test fun builds_a_request_for_a_shelf() {
        val body = JSONObject(Catalog.requestBody(Catalog.SHELVES[3]))   // documentaries
        assertTrue(body.getString("query").contains("sortBy: TRENDING"))
        val f = body.getJSONObject("variables").getJSONObject("f")
        assertEquals("doc", f.getJSONArray("genres").getString(0))
        assertEquals(6, f.getJSONArray("packages").length())
        assertEquals("GB", body.getJSONObject("variables").getString("c"))
        assertTrue(JSONObject(Catalog.requestBody(Catalog.SHELVES[4])).getString("query").contains("sortBy: POPULAR"))
    }

    @Test fun covers_the_big_western_services() {
        val all = Catalog.SHELVES.flatMap { it.packages }.toSet()
        listOf(Catalog.NETFLIX, Catalog.HBO_MAX, Catalog.PARAMOUNT, Catalog.PRIME, Catalog.DISNEY, Catalog.APPLE).forEach { assertTrue(it, it in all) }
    }

    @Test fun seerr_request_bodies() {
        val movie = JSONObject(SeerrApi.requestBody(Kind.MOVIE, 501170))
        assertEquals("movie", movie.getString("mediaType")); assertEquals(501170L, movie.getLong("mediaId")); assertFalse(movie.has("seasons"))
        val show = JSONObject(SeerrApi.requestBody(Kind.SHOW, 335536))
        assertEquals("tv", show.getString("mediaType")); assertEquals("all", show.getString("seasons"))
        assertEquals("/api/v1/tv/9", SeerrApi.path(Kind.SHOW, 9))
    }

    @Test fun seerr_status_and_outcomes() {
        assertEquals(Availability.AVAILABLE, SeerrApi.parseAvailability("""{"mediaInfo":{"status":5}}"""))
        assertEquals(Availability.REQUESTED, SeerrApi.parseAvailability("""{"mediaInfo":{"status":2}}"""))
        assertEquals(Availability.DOWNLOADING, SeerrApi.parseAvailability("""{"mediaInfo":{"status":3}}"""))
        assertEquals(Availability.NONE, SeerrApi.parseAvailability("""{"title":"no media info yet"}"""))
        assertEquals(Availability.NONE, SeerrApi.parseAvailability("junk"))
        assertEquals(RequestOutcome.QUEUED, SeerrApi.outcomeFor(201)); assertEquals(RequestOutcome.ALREADY, SeerrApi.outcomeFor(409))
        assertEquals(RequestOutcome.NEEDS_SIGN_IN, SeerrApi.outcomeFor(401)); assertEquals(RequestOutcome.FAILED, SeerrApi.outcomeFor(500))
    }

    @Test fun picks_the_session_cookie() {
        assertEquals("connect.sid=s%3Aabc", SeerrApi.sessionCookie(listOf("other=1; Path=/", "connect.sid=s%3Aabc; Path=/; HttpOnly")))
        assertNull(SeerrApi.sessionCookie(listOf("other=1"))); assertNull(SeerrApi.sessionCookie(null))
        assertFalse(SeerrApi.signInBody("daniel", "pw").contains("\"mediaType\""))
    }
}
