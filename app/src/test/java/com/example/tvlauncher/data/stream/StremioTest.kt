package com.example.tvlauncher.data.stream

import org.junit.Assert.*
import org.junit.Test

class StremioTest {
    @Test fun login_response_gives_token_or_error() {
        assertEquals("abc123" to null, StremioApiParser.parseLogin("""{"result":{"authKey":"abc123","user":{}}}"""))
        assertEquals(null to "Wrong password", StremioApiParser.parseLogin("""{"error":{"message":"Wrong password"}}"""))
        assertNull(StremioApiParser.parseLogin("garbage").first)
    }

    @Test fun addon_list_keeps_only_movie_stream_addons() {
        val json = """{"result":{"addons":[
          {"transportUrl":"https://a.example/cfg/manifest.json","manifest":{"name":"Streamer","types":["movie","series"],"resources":["catalog",{"name":"stream","types":["movie"]}]}},
          {"transportUrl":"https://cinemeta.example/manifest.json","manifest":{"name":"Cinemeta","types":["movie"],"resources":["catalog","meta"]}},
          {"transportUrl":"https://tv.example/manifest.json","manifest":{"name":"TV only","types":["tv"],"resources":["stream"]}},
          {"transportUrl":"https://b.example/manifest.json","manifest":{"name":"Plain","types":["movie"],"resources":["stream"]}}
        ]}}"""
        val addons = StremioApiParser.parseAddons(json)
        assertEquals(listOf("Streamer", "Plain"), addons.map { it.name })
        assertEquals("https://a.example/cfg", addons[0].baseUrl)
    }

    @Test fun bad_addon_json_gives_empty_list() = assertTrue(StremioApiParser.parseAddons("nope").isEmpty())

    @Test fun torrent_results_stream_through_the_engine_only_when_it_is_up() {
        val json = """{"streams":[{"name":"T","title":"Movie 1080p BluRay x264","infoHash":"abcdef0123","fileIdx":2,"sources":["tracker:udp://t.example:80/announce","dht:abcdef0123"]}]}"""
        val off = AddonResponseParser.parse("A", json, engineUp = false).single()
        assertFalse(off.directPlayable)
        assertTrue("file 2 of a pack cannot be picked through a magnet link", off.isPack)
        assertFalse(off.stremioHandoff)
        assertEquals(listOf("udp://t.example:80/announce"), off.trackers)
        assertEquals(2, off.fileIdx)
        val on = AddonResponseParser.parse("A", json, engineUp = true).single()
        assertTrue(on.directPlayable)
        assertTrue(on.viaEngine)
        assertTrue(on.url!!.startsWith("http://127.0.0.1:11470/abcdef0123/2?tr="))
        assertFalse("dht entries are not trackers", on.url!!.contains("dht"))
    }

    @Test fun single_film_torrents_are_handed_to_stremio_but_packs_are_not() {
        val json = """{"streams":[
          {"name":"T","title":"Film 1080p WEB-DL","infoHash":"aaa","fileIdx":0},
          {"name":"T","title":"Film 1080p WEB-DL","infoHash":"bbb"},
          {"name":"T","title":"Collection Pack 1080p","infoHash":"ccc","fileIdx":45}]}"""
        val r = AddonResponseParser.parse("A", json)
        assertEquals(listOf(true, true, false), r.map { it.stremioHandoff })
        assertTrue(r[2].isPack)
    }

    @Test fun engine_url_without_file_index_lets_the_engine_choose() {
        assertEquals("http://127.0.0.1:11470/hash/-1", StremioEngine.streamUrl("hash", null, emptyList()))
    }
}
