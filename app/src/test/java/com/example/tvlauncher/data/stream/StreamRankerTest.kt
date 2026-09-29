package com.example.tvlauncher.data.stream

import org.junit.Assert.*
import org.junit.Test

class StreamRankerTest {
    private val gb = 1L shl 30
    private val runtime = 7200L   // 2 hours

    private val tv4k = DeviceCapabilities(
        2160, setOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1), dolbyVision = true, hdr10 = true, hdr10Plus = false,
        audio = setOf(AudioFormat.AAC, AudioFormat.AC3, AudioFormat.EAC3, AudioFormat.ATMOS)
    )
    private fun net(mbps: Long) = NetworkEstimate(mbps * 1_000_000L, 0, "test")

    /** A candidate whose average bitrate is [mbps] over the 2-hour runtime. */
    private fun cand(id: String, name: String, mbps: Double, provider: String = "P", url: String? = "https://x/$id.mkv"): StreamCandidate {
        val size = (mbps * 1_000_000 * runtime / 8).toLong()
        return StreamCandidate(id, provider, name, url, null, StreamParser.parse(name, size))
    }

    private fun rank(list: List<StreamCandidate>, n: Long, device: DeviceCapabilities = tv4k, stats: Map<String, ProviderStats> = emptyMap()) =
        StreamRanker.rank(list, device, net(n), stats, runtime)

    @Test fun exampleA_remux_without_headroom_loses_to_web_dl() {
        val remux = cand("r", "Movie 2160p REMUX HEVC HDR10", 95.0)
        val web = cand("w", "Movie 2160p WEB-DL HEVC HDR10", 28.0)
        val ranked = rank(listOf(remux, web), 105)
        assertEquals("w", StreamRanker.pickBest(ranked)!!.candidate.id)
        assertEquals(Verdict.MAY_BUFFER, ranked.first { it.candidate.id == "r" }.verdict)
    }

    @Test fun exampleB_4k_web_dl_hdr_beats_1080_bluray_on_fast_network() {
        val web = cand("w", "Movie 2160p WEB-DL HEVC HDR10", 24.0)
        val bd = cand("b", "Movie 1080p BluRay x264", 14.0)
        assertEquals("w", StreamRanker.pickBest(rank(listOf(bd, web), 100))!!.candidate.id)
    }

    @Test fun compatible_4k_beats_1080_on_fast_network() {
        val uhd = cand("u", "Movie 2160p WEB-DL HEVC", 20.0)
        val hd = cand("h", "Movie 1080p WEB-DL H264", 8.0)
        assertEquals("u", StreamRanker.pickBest(rank(listOf(hd, uhd), 200))!!.candidate.id)
    }

    @Test fun slow_network_prefers_what_it_can_sustain() {
        val uhd = cand("u", "Movie 2160p WEB-DL HEVC", 20.0)
        val hd = cand("h", "Movie 1080p WEB-DL H264", 6.0)
        assertEquals("h", StreamRanker.pickBest(rank(listOf(uhd, hd), 20))!!.candidate.id)
    }

    @Test fun unsupported_codec_is_incompatible() {
        val noAv1 = tv4k.copy(codecs = setOf(VideoCodec.H264, VideoCodec.HEVC))
        val r = rank(listOf(cand("a", "Movie 2160p WEB-DL AV1", 15.0)), 100, noAv1).single()
        assertEquals(Verdict.INCOMPATIBLE, r.verdict)
        assertNull(StreamRanker.pickBest(listOf(r)))
    }

    @Test fun resolution_above_panel_is_incompatible() {
        val fhd = tv4k.copy(maxHeight = 1080)
        assertEquals(Verdict.INCOMPATIBLE, rank(listOf(cand("u", "Movie 2160p WEB-DL HEVC", 20.0)), 100, fhd).single().verdict)
    }

    @Test fun unsupported_dolby_vision_gets_no_advantage() {
        val noDv = tv4k.copy(dolbyVision = false)
        val dv = cand("dv", "Movie 2160p WEB-DL DV HEVC", 24.0)          // DV only, no HDR10 base layer
        val hdr = cand("hdr", "Movie 2160p WEB-DL HDR10 HEVC", 24.0)
        val ranked = rank(listOf(dv, hdr), 100, noDv)
        assertEquals("hdr", ranked.first().candidate.id)
        assertTrue(ranked.first { it.candidate.id == "dv" }.score < ranked.first { it.candidate.id == "hdr" }.score)
    }

    @Test fun dolby_vision_beats_hdr10_when_supported() {
        val dv = cand("dv", "Movie 2160p WEB-DL DV HEVC", 24.0)
        val hdr = cand("hdr", "Movie 2160p WEB-DL HDR10 HEVC", 24.0)
        assertEquals("dv", rank(listOf(hdr, dv), 100).first().candidate.id)
    }

    @Test fun dv_with_hdr10_fallback_on_non_dv_tv_is_treated_as_hdr10() {
        val noDv = tv4k.copy(dolbyVision = false)
        val a = rank(listOf(cand("x", "Movie 2160p WEB-DL DV HDR HEVC", 24.0)), 100, noDv).single().score
        val b = rank(listOf(cand("y", "Movie 2160p WEB-DL HDR10 HEVC", 24.0)), 100, noDv).single().score
        assertEquals(b, a)
    }

    @Test fun exampleC_unreliable_provider_loses_to_reliable_lower_quality() {
        val flaky = cand("f", "Movie 2160p WEB-DL HEVC HDR10", 22.0, provider = "Flaky")
        val solid = cand("s", "Movie 1080p BluRay x264", 12.0, provider = "Solid")
        val stats = mapOf(
            "Flaky" to ProviderStats(9_000, 20_000_000, bufferRate = 0.6, failureRate = 0.5, sessions = 6),
            "Solid" to ProviderStats(1_800, 90_000_000, bufferRate = 0.0, failureRate = 0.0, sessions = 8)
        )
        assertEquals("s", StreamRanker.pickBest(rank(listOf(flaky, solid), 100, stats = stats))!!.candidate.id)
    }

    @Test fun same_streams_without_bad_history_pick_the_higher_quality_one() {
        val flaky = cand("f", "Movie 2160p WEB-DL HEVC HDR10", 22.0, provider = "Flaky")
        val solid = cand("s", "Movie 1080p BluRay x264", 12.0, provider = "Solid")
        assertEquals("f", StreamRanker.pickBest(rank(listOf(flaky, solid), 100))!!.candidate.id)
    }

    @Test fun torrent_results_are_handed_to_stremio_and_still_ranked_on_quality() {
        val torrent = StreamCandidate("t", "T", "t", null, "abcdef", StreamParser.parse("Movie 2160p WEB-DL HEVC", 10 * gb), trackers = listOf("udp://t.example:80"))
        val direct = cand("d", "Movie 1080p WEB-DL H264", 8.0)
        val best = StreamRanker.pickBest(rank(listOf(torrent, direct), 100))!!
        assertEquals("the better 4K torrent wins and goes to Stremio", "t", best.candidate.id)
        assertTrue(best.candidate.stremioHandoff)
        assertFalse(best.candidate.directPlayable)
        assertTrue(best.candidate.magnetUri("Movie").startsWith("magnet:?xt=urn:btih:abcdef&dn=Movie&tr=udp"))
    }

    @Test fun a_result_with_neither_link_nor_hash_is_not_playable() {
        val nothing = StreamCandidate("n", "P", "n", null, null, StreamParser.parse("Movie 1080p", 5 * gb))
        assertNull(StreamRanker.pickBest(rank(listOf(nothing), 100)))
    }

    @Test fun play_button_label_shows_only_resolution_and_range() {
        assertEquals("4K \u00b7 Dolby Vision", StreamParser.parse("Movie 2160p WEB-DL DV HEVC").shortLabel())
        assertEquals("1080p", StreamParser.parse("Movie 1080p BluRay x264").shortLabel())
        assertEquals("Best available", StreamMetadata().shortLabel())
    }

    @Test fun if_everything_is_too_heavy_the_lightest_playable_is_chosen() {
        val a = cand("a", "Movie 2160p REMUX HEVC", 90.0)
        val b = cand("b", "Movie 2160p WEB-DL HEVC", 60.0)
        assertEquals("b", StreamRanker.pickBest(rank(listOf(a, b), 40))!!.candidate.id)
    }

    @Test fun exactly_one_recommended() {
        val ranked = rank(listOf(cand("a", "Movie 2160p WEB-DL HEVC", 20.0), cand("b", "Movie 1080p WEB-DL H264", 8.0)), 100)
        assertEquals(1, ranked.count { it.verdict == Verdict.RECOMMENDED })
    }

    @Test fun unknown_metadata_does_not_crash() {
        val c = StreamCandidate("z", "P", "mystery", "https://x/z", null, StreamMetadata())
        assertEquals(1, rank(listOf(c), 100).size)
    }
}
