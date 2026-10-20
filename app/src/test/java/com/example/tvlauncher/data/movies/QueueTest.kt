package com.example.tvlauncher.data.movies

import org.junit.Assert.*
import org.junit.Test

class QueueTest {
    private fun record(id: Int, status: String, added: String, size: Long = 0, left: Long = 0, series: String = "The Walking Dead", s: Int = 3, e: Int = id, timeleft: String = "") =
        """{"id":$id,"status":"$status","added":"$added","size":$size,"sizeleft":$left,"timeleft":"$timeleft","series":{"title":"$series","tmdbId":1402},"episode":{"seasonNumber":$s,"episodeNumber":$e,"title":"Ep $e"}}"""

    private fun queue(vararg r: String) = """{"page":1,"totalRecords":${r.size},"records":[${r.joinToString(",")}]}"""

    @Test fun reads_what_is_downloading_and_what_is_waiting() {
        val items = QueueApi.parse(queue(record(1, "downloading", "2026-10-20T12:00:00Z", 273485243, 266145211, timeleft = "00:12:30"), record(2, "queued", "2026-10-20T12:05:00Z")), QueueSource.SONARR)
        assertEquals("The Walking Dead", items[0].title); assertEquals("S3 E1 · Ep 1", items[0].detail); assertEquals(1402L, items[0].tmdbId)
        assertTrue(items[0].isDownloading); assertEquals(0.027f, items[0].progress()!!, 0.001f); assertEquals(750L, items[0].etaSeconds)
        assertTrue(items[1].isWaiting); assertNull(items[1].progress())   // size not known until it starts
    }

    @Test fun numbers_the_waiting_in_the_order_they_were_added() {
        val items = QueueApi.withPositions(QueueApi.parse(queue(
            record(10, "queued", "2026-10-20T12:30:00Z"), record(11, "downloading", "2026-10-20T12:00:00Z", 100, 50),
            record(12, "queued", "2026-10-20T12:10:00Z"), record(13, "completed", "2026-10-20T11:00:00Z")), QueueSource.SONARR))
        assertEquals(mapOf(10 to 2, 11 to 0, 12 to 1, 13 to 0), items.associate { it.id to it.position })
        assertEquals(listOf(11, 12, 10, 13), QueueApi.ordered(items).map { it.id })   // downloading, then in line, then the rest
    }

    @Test fun movies_come_from_radarrs_shape() {
        val m = QueueApi.parse("""{"records":[{"id":5,"status":"downloading","size":1000,"sizeleft":250,"movie":{"title":"Dune","year":2021,"tmdbId":438631}}]}""", QueueSource.RADARR).single()
        assertEquals("Dune", m.title); assertEquals("2021", m.detail); assertEquals(438631L, m.tmdbId); assertEquals(0.75f, m.progress()!!, 0.001f)
    }

    @Test fun reads_time_left_with_and_without_days() {
        assertEquals(750L, QueueApi.timeLeftSeconds("00:12:30")); assertEquals(93_784L, QueueApi.timeLeftSeconds("1.02:03:04"))
        assertNull(QueueApi.timeLeftSeconds("")); assertNull(QueueApi.timeLeftSeconds(null)); assertNull(QueueApi.timeLeftSeconds("soon"))
    }

    @Test fun cancelling_also_clears_the_download_client_but_never_blocks_the_release() {
        assertEquals("/api/v3/queue/42?removeFromClient=true&blocklist=false", QueueApi.cancelPath(42))
        assertTrue(QueueApi.parse("junk", QueueSource.SONARR).isEmpty())
    }
}
