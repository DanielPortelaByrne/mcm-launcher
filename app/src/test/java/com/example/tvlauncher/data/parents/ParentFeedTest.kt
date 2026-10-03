package com.example.tvlauncher.data.parents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentFeedTest {

    private fun photo(id: String, firstSeen: String) =
        """{"id":"$id","imageUrl":"https://lh3.googleusercontent.com/pw/$id=w1920-h1080","thumbUrl":"https://lh3.googleusercontent.com/pw/$id=w720-h720","width":4000,"height":3000,"caption":null,"takenAt":"2026-09-30T12:00:00.000Z","firstSeenAt":"$firstSeen"}"""

    private fun feed(updatedAt: String = "2026-10-03T18:30:00Z", photos: String = photo("a", "2026-10-02T10:00:00Z"), extra: String = "") = """
        {"version":1,"updatedAt":"$updatedAt",
         "danielLately":{"photos":[$photos]},
         "familyArchive":null,
         "comingUp":[{"title":"Daniel & Eva home","date":"2026-10-15","endDate":"2026-10-19","allDay":true}],
         "forAmelia":{"live":[{"title":"Record News","subtitle":"Ao vivo","image":"https://i.ytimg.com/vi/x/hqdefault.jpg","link":{"uri":"https://www.youtube.com/watch?v=x","packages":["com.amazon.firetv.youtube"]}}],"novelas":[],"film":null,"duolingoStreak":1705,"shows":[{"title":"Domingo Legal","subtitle":"Passa ou Repassa","link":{"uri":"https://www.youtube.com/watch?v=d","packages":["com.amazon.firetv.youtube"]}}]},
         "tonight":[],"listening":null,
         "sport":{"wolves":{"name":"Wolves","live":null,"last":{"date":"2026-09-20T11:00Z","competition":"Championship","home":"Wolves","away":"West Brom","homeScore":1,"awayScore":0,"state":"post","venue":"Molineux"},"next":null,"table":{"position":4,"of":24,"league":"2026-27 English League Championship"}},"ireland":null},
         "crochet":[],"aria":[],"localEvents":[] $extra}
    """.trimIndent()

    @Test fun parsesAWellFormedFeed() {
        val f = ParentFeedParser.parse(feed())
        assertEquals(1, f.danielLately.size)
        assertEquals("Daniel & Eva home", f.comingUp.single().title)
        assertEquals("Record News", f.forAmelia!!.live.single().title)
        assertEquals("Domingo Legal", f.forAmelia!!.shows.single().title)
        assertEquals(1705, f.forAmelia!!.duolingoStreak)
        assertEquals(4, f.sport!!.wolves!!.position)
        assertEquals(1, f.sport!!.wolves!!.last!!.homeScore)
        assertTrue(f.familyArchive.isEmpty())
        assertNull(f.listening)
    }

    @Test fun emptyAlbumIsAValidFeed() {
        assertTrue(ParentFeedParser.parse(feed(photos = "")).danielLately.isEmpty())
    }

    @Test(expected = InvalidFeedException::class) fun rejectsWrongVersion() { ParentFeedParser.parse(feed().replace("\"version\":1", "\"version\":2")) }

    @Test(expected = InvalidFeedException::class) fun rejectsNonJson() { ParentFeedParser.parse("<html>rate limited</html>") }

    @Test(expected = InvalidFeedException::class) fun rejectsInsecureImages() { ParentFeedParser.parse(feed().replace("https://lh3", "http://lh3")) }

    @Test(expected = InvalidFeedException::class) fun rejectsALinkWithoutAUri() { ParentFeedParser.parse(feed().replace("\"uri\":\"https://www.youtube.com/watch?v=x\",", "")) }

    @Test(expected = InvalidFeedException::class) fun rejectsUndatedEvents() { ParentFeedParser.parse(feed().replace("\"date\":\"2026-10-15\"", "\"date\":\"soon\"")) }

    // --- repository: stale-while-revalidate ---

    private class MemoryStore(var json: String? = null) : FeedStore {
        var writes = 0
        override fun read() = json
        override fun write(json: String) { this.json = json; writes++ }
        override var etag: String? = null
        override var checkedAt: Long = 0
    }

    private fun repo(store: FeedStore, fetch: FeedFetcher, url: String? = "https://example.test/feed.json", now: Long = 1_000_000L) =
        ParentFeedRepository(store, fetch, { url }, { now })

    @Test fun cachedFeedLoadsWithoutNetwork() {
        val r = repo(MemoryStore(feed()), { _, _ -> error("no network") })
        assertEquals(1, r.load()!!.danielLately.size)
    }

    @Test fun aGoodFetchReplacesTheCacheAtomically() {
        val store = MemoryStore(feed())
        val r = repo(store, { _, _ -> Fetched(feed(updatedAt = "2026-10-04T08:00:00Z", photos = photo("a", "2026-10-02T10:00:00Z") + "," + photo("b", "2026-10-04T07:00:00Z")), "\"e2\"") })
        r.load()
        assertEquals(ParentFeedRepository.Outcome.UPDATED, r.refresh(force = true))
        assertEquals(2, r.cached!!.danielLately.size)
        assertEquals(1, store.writes)
        assertEquals("\"e2\"", store.etag)
    }

    @Test fun aNetworkFailureKeepsTheLastGoodFeed() {
        val store = MemoryStore(feed())
        val r = repo(store, { _, _ -> throw java.io.IOException("offline") })
        val before = r.load()
        assertEquals(ParentFeedRepository.Outcome.FAILED, r.refresh(force = true))
        assertSame(before, r.cached)
        assertEquals(0, store.writes)
    }

    @Test fun aMalformedFeedIsRejectedAndTheCacheKept() {
        val store = MemoryStore(feed())
        val r = repo(store, { _, _ -> Fetched("{\"version\":1}", null) })
        r.load()
        assertEquals(ParentFeedRepository.Outcome.REJECTED, r.refresh(force = true))
        assertEquals(1, r.cached!!.danielLately.size)
        assertEquals(feed(), store.json)
    }

    @Test fun notModifiedKeepsTheCacheAndSendsTheEtag() {
        val store = MemoryStore(feed()).apply { etag = "\"e1\"" }
        var sent: String? = null
        val r = repo(store, { _, etag -> sent = etag; null })
        r.load()
        assertEquals(ParentFeedRepository.Outcome.UNCHANGED, r.refresh(force = true))
        assertEquals("\"e1\"", sent)
        assertEquals(0, store.writes)
    }

    @Test fun recentChecksAreSkippedUnlessForced() {
        val store = MemoryStore(feed()).apply { checkedAt = 1_000_000L - 60_000L }
        var calls = 0
        val r = repo(store, { _, _ -> calls++; null })
        r.load()
        assertEquals(ParentFeedRepository.Outcome.SKIPPED, r.refresh())
        assertEquals(0, calls)
        r.refresh(force = true)
        assertEquals(1, calls)
    }

    @Test fun firstInstallOfflineHasNoFeedAndNoCrash() {
        val r = repo(MemoryStore(null), { _, _ -> throw java.io.IOException("offline") })
        assertNull(r.load())
        assertEquals(ParentFeedRepository.Outcome.FAILED, r.refresh())
        assertNull(r.cached)
    }

    @Test fun unconfiguredTvDoesNotFetch() {
        val r = repo(MemoryStore(null), { _, _ -> error("must not fetch") }, url = null)
        assertEquals(ParentFeedRepository.Outcome.NOT_CONFIGURED, r.refresh(force = true))
    }

    @Test fun corruptStoredFeedIsTreatedAsNone() {
        assertNull(repo(MemoryStore("{broken"), { _, _ -> null }).load())
    }
}
