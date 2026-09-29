package com.example.tvlauncher.data

import org.junit.Assert.*
import org.junit.Test

class ResumeLinksTest {
    private fun item(pkg: String, mediaId: String?, pos: Long = 600_000, dur: Long = 3_600_000) =
        ResumeItem("Title", null, pkg, null, pos, dur, null, 0, mediaId = mediaId)

    @Test fun a_youtube_card_opens_that_video_at_the_saved_time() {
        val l = ResumeLinks.target(item("org.smarttube.stable", "youtube:aqz-KE-bpKQ"))!!
        assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=600s", l.uri)
        assertEquals("org.smarttube.stable", l.packageName)
    }

    @Test fun near_the_start_or_the_end_it_does_not_add_a_time() {
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", ResumeLinks.target(item("p", "youtube:abcdefghijk", pos = 10_000))!!.uri)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", ResumeLinks.target(item("p", "youtube:abcdefghijk", pos = 3_550_000))!!.uri)
    }

    @Test fun a_stremio_card_opens_the_film_page_when_the_film_is_known() {
        val l = ResumeLinks.target(item("com.stremio.one", "imdb:tt0014972"))!!
        assertEquals("stremio:///detail/movie/tt0014972/tt0014972", l.uri)
        assertEquals("com.stremio.one", l.packageName)
    }

    @Test fun a_stremio_card_without_a_film_id_searches_by_title() {
        assertEquals("stremio:///search?search=Title", ResumeLinks.target(item("com.stremio.one", null))!!.uri)
    }

    @Test fun other_apps_have_no_deep_link() = assertNull(ResumeLinks.target(item("com.netflix.ninja", null)))

    @Test fun a_worked_out_id_survives_an_update_that_has_none() {
        assertEquals("youtube:abc\nhttps://x/art.jpg", ResumeLinks.mergeIds("https://x/art.jpg", "youtube:abc"))
        assertEquals("youtube:new", ResumeLinks.mergeIds("youtube:new", "youtube:old"))
        assertEquals("plain", ResumeLinks.mergeIds("plain", null))
    }
}
