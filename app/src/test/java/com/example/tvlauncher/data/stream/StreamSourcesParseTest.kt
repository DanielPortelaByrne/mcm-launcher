package com.example.tvlauncher.data.stream

import org.junit.Assert.*
import org.junit.Test

class StreamSourcesParseTest {

    @Test fun parses_direct_and_torrent_streams_and_skips_junk() {
        val json = """{"streams":[
          {"name":"Addon 4K","title":"Movie.2026.2160p.WEB-DL.DV.HDR.HEVC.Atmos.mkv","url":"https://cdn.example/a.mkv","behaviorHints":{"videoSize":25769803776,"filename":"Movie.mkv"}},
          {"name":"Torrent","title":"Movie 1080p BluRay x264","infoHash":"abc123"},
          {"name":"Not ready","title":"Movie 720p","url":"https://cdn.example/b.mkv","behaviorHints":{"notWebReady":true}},
          "garbage"
        ]}"""
        val list = AddonResponseParser.parse("Addon", json)
        assertEquals(3, list.size)
        assertTrue(list[0].directPlayable)
        assertEquals(Resolution.P2160, list[0].meta.resolution)
        assertEquals(25769803776L, list[0].meta.sizeBytes)
        assertFalse(list[1].directPlayable)
        assertFalse(list[2].directPlayable)
    }

    @Test fun malformed_json_gives_empty_list() {
        assertTrue(AddonResponseParser.parse("A", "not json").isEmpty())
        assertTrue(AddonResponseParser.parse("A", "{}").isEmpty())
    }
}
