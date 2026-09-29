package com.example.tvlauncher.data

import org.junit.Assert.*
import org.junit.Test

class ThumbnailsTest {
    @Test fun a_bare_youtube_id_gives_thumbnail_addresses() {
        val u = Thumbnails.candidates(listOf("aqz-KE-bpKQ"))
        assertEquals("https://i.ytimg.com/vi/aqz-KE-bpKQ/maxresdefault.jpg", u[0])
        assertEquals("https://i.ytimg.com/vi/aqz-KE-bpKQ/hqdefault.jpg", u[1])
    }

    @Test fun watch_and_short_links_are_understood() {
        assertTrue(Thumbnails.candidates(listOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=5")).first().contains("aqz-KE-bpKQ"))
        assertTrue(Thumbnails.candidates(listOf("https://youtu.be/aqz-KE-bpKQ")).first().contains("aqz-KE-bpKQ"))
        assertTrue(Thumbnails.candidates(listOf("https://i.ytimg.com/vi/aqz-KE-bpKQ/default.jpg")).any { it.contains("aqz-KE-bpKQ/hqdefault") })
    }

    @Test fun a_plain_image_link_is_used_as_is() {
        assertEquals(listOf("https://img.example/poster.jpg"), Thumbnails.candidates(listOf("https://img.example/poster.jpg")))
    }

    @Test fun ids_that_are_not_youtube_ids_give_nothing() {
        assertTrue(Thumbnails.candidates(listOf("content://media/123", "not an id at all", "")).isEmpty())
    }

    @Test fun several_lines_are_all_considered() {
        assertEquals(2, Thumbnails.candidates(listOf("junk\naqz-KE-bpKQ")).size)
    }
}
