package com.example.tvlauncher.data.jellyfin

import org.junit.Assert.*
import org.junit.Test

class JellyfinApiTest {
    private val item = JellyfinItem("abc123", "Pat and Mike", 1952, "Movie", "src9")

    @Test fun finds_by_provider_id_not_title() {
        val url = JellyfinApi.findByImdbUrl("http://deck:8096", "user1", "tt0045104")
        assertTrue(url.startsWith("http://deck:8096/Items?"))
        assertTrue(url.contains("AnyProviderIdEquals=imdb.tt0045104"))
        assertTrue(url.contains("IncludeItemTypes=Movie"))
        assertFalse(url.contains("searchTerm", ignoreCase = true))
    }

    @Test fun stream_urls_never_carry_the_token() {
        val direct = JellyfinApi.directStreamUrl("http://deck:8096", item)
        val transcode = JellyfinApi.transcodeUrl("http://deck:8096", item, "dev1")
        assertEquals("http://deck:8096/Videos/abc123/stream?static=true&mediaSourceId=src9", direct)
        assertTrue(transcode.startsWith("http://deck:8096/Videos/abc123/master.m3u8?DeviceId=dev1&MediaSourceId=src9"))
        listOf(direct, transcode).forEach { assertFalse(it.contains("api_key", true) || it.contains("token", true)) }
    }

    @Test fun cleans_server_addresses() {
        assertEquals("http://192.168.1.233:8096", JellyfinApi.cleanBase(" 192.168.1.233:8096/ "))
        assertEquals("https://jf.example.com", JellyfinApi.cleanBase("https://jf.example.com/"))
    }

    @Test fun auth_header_includes_token_only_when_signed_in() {
        assertFalse(JellyfinApi.authHeader("d", "TCL TV").contains("Token"))
        assertTrue(JellyfinApi.authHeader("d", "TCL TV", "secret").endsWith("Token=\"secret\""))
    }

    @Test fun parses_a_library_answer() {
        val json = """{"Items":[{"Id":"abc123","Name":"Pat and Mike","ProductionYear":1952,"Type":"Movie",
            "MediaSources":[{"Id":"src9"}],"UserData":{"PlaybackPositionTicks":6000000000,"Played":false}},
            {"Name":"no id, skipped"}],"TotalRecordCount":2}"""
        val items = JellyfinApi.parseItems(json)
        assertEquals(1, items.size)
        with(items[0]) {
            assertEquals("abc123", id); assertEquals(1952, year); assertEquals("src9", mediaSourceId)
            assertEquals(600_000L, resumeMs); assertTrue(isMovie)
        }
    }

    @Test fun bad_json_is_an_empty_library() {
        assertTrue(JellyfinApi.parseItems("not json").isEmpty())
        assertTrue(JellyfinApi.parseItems("""{"Items":[]}""").isEmpty())
    }
}
