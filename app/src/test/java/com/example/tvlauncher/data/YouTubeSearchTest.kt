package com.example.tvlauncher.data

import org.junit.Assert.*
import org.junit.Test

class YouTubeSearchTest {
    private fun result(title: String, channel: String, url: String) =
        """{"id":{"kind":"youtube#video","videoId":"id_${url.substringAfterLast('/')}"},"snippet":{"title":"$title","channelTitle":"$channel","thumbnails":{"default":{"url":"${url}_d.jpg"},"high":{"url":"${url}_h.jpg"}}}}"""

    private fun response(vararg r: String) = """{"items":[${r.joinToString(",")}]}"""

    @Test fun an_exact_title_match_gives_its_best_thumbnail() {
        val json = response(result("Other video", "X", "https://a/other"), result("Fireplace Evenings of Calm", "Fireplace 4K", "https://a/right"))
        assertEquals("https://a/right_h.jpg", YouTubeSearch.pickThumbnail(json, "Fireplace Evenings of Calm", "Fireplace 4K"))
    }

    @Test fun the_match_carries_the_video_id_for_deep_linking() {
        val json = response(result("Fireplace Evenings of Calm", "Fireplace 4K", "https://a/right"))
        assertEquals("id_right", YouTubeSearch.pickMatch(json, "Fireplace Evenings of Calm", null)!!.id)
    }

    @Test fun the_matching_channel_breaks_ties_between_identical_titles() {
        val json = response(result("Relaxing Fire", "Copycat", "https://a/copy"), result("Relaxing Fire", "Fireplace 4K", "https://a/real"))
        assertEquals("https://a/real_h.jpg", YouTubeSearch.pickThumbnail(json, "Relaxing Fire", "Fireplace 4K"))
    }

    @Test fun accents_case_and_punctuation_do_not_matter() {
        val json = response(result("Café TOUR!", "C", "https://a/cafe"))
        assertEquals("https://a/cafe_h.jpg", YouTubeSearch.pickThumbnail(json, "cafe tour", null))
    }

    @Test fun no_convincing_match_gives_nothing_rather_than_a_wrong_picture() {
        val json = response(result("Completely different", "X", "https://a/no"))
        assertNull(YouTubeSearch.pickThumbnail(json, "Fireplace Evenings of Calm", null))
    }

    @Test fun errors_and_garbage_give_nothing() {
        assertNull(YouTubeSearch.pickThumbnail("""{"error":{"code":403}}""", "x", null))
        assertNull(YouTubeSearch.pickThumbnail("nope", "x", null))
    }
}
