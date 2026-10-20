package com.example.tvlauncher.data.jellyfin

import org.junit.Assert.*
import org.junit.Test

class JellyfinApiTest {
    private val item = JellyfinItem("abc123", "Pat and Mike", 1952, "Movie", "src9")
    private val library = """{"Items":[
        {"Id":"a1","Name":"Betty Boop - Snow White","Type":"Movie","ProviderIds":{}},
        {"Id":"h2","Name":"Harakiri","ProductionYear":1962,"Type":"Movie","ProviderIds":{"Imdb":"tt0056058","Tmdb":"14537"}},
        {"Id":"l3","Name":"Leon","Type":"Movie","ProviderIds":{"Imdb":"tt0110413"}},
        {"Id":"s4","Name":"A Series","Type":"Series","ProviderIds":{"Imdb":"tt9999999"}}
    ],"TotalRecordCount":4}"""

    @Test fun library_request_lists_films_with_provider_ids() {
        val url = JellyfinApi.libraryMoviesUrl("http://deck:8096", "user1")
        assertTrue(url.startsWith("http://deck:8096/Items?"))
        assertTrue(url.contains("IncludeItemTypes=Movie"))
        assertTrue(url.contains("Fields=ProviderIds"))
    }

    /** Jellyfin 12.2 ignores AnyProviderIdEquals and returns every film; matching must be done on the TV. */
    @Test fun matches_only_the_exact_imdb_id() {
        val items = JellyfinApi.parseItems(library)
        assertEquals("Harakiri", JellyfinApi.findByImdb(items, "tt0056058")?.name)
        assertEquals("Leon", JellyfinApi.findByImdb(items, "TT0110413")?.name)
    }

    @Test fun a_film_that_is_not_in_the_library_is_not_found_not_guessed() {
        val items = JellyfinApi.parseItems(library)
        assertNull(JellyfinApi.findByImdb(items, "tt1234567"))
        // the first item (no id at all) and a series with a matching id must never be returned
        assertNull(JellyfinApi.findByImdb(items, "tt9999999"))
        assertNull(JellyfinApi.findByImdb(emptyList(), "tt0056058"))
    }

    @Test fun stream_urls_never_carry_the_token() {
        val direct = JellyfinApi.directStreamUrl("http://deck:8096", item)
        val transcode = JellyfinApi.transcodeUrl("http://deck:8096", item, "dev1")
        assertEquals("http://deck:8096/Videos/abc123/stream?static=true&mediaSourceId=src9", direct)
        assertTrue(transcode.startsWith("http://deck:8096/Videos/abc123/master.m3u8?DeviceId=dev1&MediaSourceId=src9"))
        listOf(direct, transcode).forEach { assertFalse(it.contains("api_key", true) || it.contains("token", true)) }
    }

    @Test fun source_defaults_to_the_item_id_so_the_transcode_is_never_missing_one() {
        val bare = JellyfinItem("h2", "Harakiri", 1962, "Movie", null)
        assertTrue(JellyfinApi.transcodeUrl("http://deck:8096", bare, "d").contains("MediaSourceId=h2"))
    }

    @Test fun cleans_server_addresses() {
        assertEquals("http://192.168.1.233:8096", JellyfinApi.cleanBase(" 192.168.1.233:8096/ "))
        assertEquals("https://jf.example.com", JellyfinApi.cleanBase("https://jf.example.com/"))
    }

    @Test fun auth_header_includes_token_only_when_signed_in() {
        assertFalse(JellyfinApi.authHeader("d", "TCL TV").contains("Token"))
        assertTrue(JellyfinApi.authHeader("d", "TCL TV", "secret").endsWith("Token=\"secret\""))
    }

    @Test fun parses_resume_position() {
        val json = """{"Items":[{"Id":"abc123","Name":"Pat and Mike","ProductionYear":1952,"Type":"Movie",
            "MediaSources":[{"Id":"src9"}],"UserData":{"PlaybackPositionTicks":6000000000,"Played":false}},
            {"Name":"no id, skipped"}]}"""
        val items = JellyfinApi.parseItems(json)
        assertEquals(1, items.size)
        assertEquals("src9", items[0].mediaSourceId); assertEquals(600_000L, items[0].resumeMs)
    }

    @Test fun bad_json_is_an_empty_library() {
        assertTrue(JellyfinApi.parseItems("not json").isEmpty())
        assertTrue(JellyfinApi.parseItems("""{"Items":[]}""").isEmpty())
    }
}
