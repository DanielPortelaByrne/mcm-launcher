package com.example.tvlauncher.data.live

import com.example.tvlauncher.data.jellyfin.JellyfinApi
import org.junit.Assert.*
import org.junit.Test

class LiveDataTest {
    private val list = """
        #EXTM3U
        #EXTINF:-1 tvg-id="BBCOne.uk@LondonHD" tvg-logo="https://x/bbc1.png" group-title="General",BBC One London HD (720p)
        https://a.example/bbc1.m3u8
        #EXTINF:-1 tvg-id="BBCOne.uk@East" group-title="General",BBC One East (720p) [Geo-blocked]
        https://a.example/east.m3u8
        #EXTINF:-1 group-title="News",Some News [Not 24/7]
        http://b.example/news.m3u8
        #EXTINF:-1 group-title="News",Some News (1080p)
        http://b.example/news-hd.m3u8
        #EXTINF:-1,RTMP Thing
        rtmp://c.example/live
        #EXTINF:-1,
        https://d.example/nameless.m3u8
    """.trimIndent()

    @Test fun reads_names_logos_groups_and_streams() {
        val ch = M3u.parse(list)
        val bbc = ch.first()
        assertEquals("BBC One London HD", bbc.name)
        assertEquals("https://a.example/bbc1.m3u8", bbc.url)
        assertEquals("https://x/bbc1.png", bbc.logo)
        assertEquals("General", bbc.group)
    }

    @Test fun drops_region_locked_unplayable_and_nameless_entries() {
        val names = M3u.parse(list).map { it.name }
        assertFalse(names.any { it.contains("East") })
        assertFalse(names.contains("RTMP Thing"))
        assertEquals(listOf("BBC One London HD", "Some News"), names)
    }

    @Test fun keeps_the_first_of_duplicate_names() {
        assertEquals("http://b.example/news.m3u8", M3u.parse(list).first { it.name == "Some News" }.url)
    }

    @Test fun reads_jellyfin_channels_with_the_guide() {
        val json = """{"Items":[
          {"Id":"c1","Name":"Classic Cartoons","Number":"1","Type":"TvChannel","ImageTags":{"Primary":"x"},
           "CurrentProgram":{"Name":"Popeye","EndDate":"2026-10-20T12:57:02.0000000Z"}},
          {"Id":"m1","Name":"A movie, not a channel","Type":"Movie"},
          {"Id":"c2","Name":"Horror","Type":"TvChannel"}]}"""
        val ch = JellyfinApi.parseChannels(json)
        assertEquals(listOf("Classic Cartoons", "Horror"), ch.map { it.name })
        assertEquals("Popeye", ch[0].nowPlaying)
        assertEquals(1_792_501_022_000L, ch[0].nowEndsAtMs)
        assertTrue(ch[0].hasImage); assertFalse(ch[1].hasImage); assertNull(ch[1].nowPlaying)
    }

    @Test fun makes_the_live_stream_address_absolute() {
        val json = """{"MediaSources":[{"Id":"s","TranscodingUrl":"/videos/abc/master.m3u8?DeviceId=d&LiveStreamId=L"}]}"""
        assertEquals("http://deck:8096/videos/abc/master.m3u8?DeviceId=d&LiveStreamId=L", JellyfinApi.parseLiveStreamUrl("http://deck:8096", json))
        assertNull(JellyfinApi.parseLiveStreamUrl("http://deck:8096", """{"MediaSources":[{"Id":"s"}]}"""))
        assertNull(JellyfinApi.parseLiveStreamUrl("http://deck:8096", "oops"))
    }
}
