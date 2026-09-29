package com.example.tvlauncher.data.stream

import org.junit.Assert.*
import org.junit.Test

class HandoffAndSeedersTest {
    private val runtime = 7200L
    private val tv = DeviceCapabilities(2160, setOf(VideoCodec.H264, VideoCodec.HEVC), true, true, false, setOf(AudioFormat.AAC, AudioFormat.AC3))
    private fun rank(vararg c: StreamCandidate) = StreamRanker.rank(c.toList(), tv, NetworkEstimate(100_000_000, 0, "t"), emptyMap(), runtime)

    private fun torrent(id: String, name: String, seeders: Int?, mbps: Double = 8.0): StreamCandidate {
        val size = (mbps * 1_000_000 * runtime / 8).toLong()
        val meta = StreamParser.parse(name, size).copy(seeders = seeders)
        return StreamCandidate(id, "T", name, null, "hash$id", meta)
    }

    // ---- seeders ----
    @Test fun seeders_are_read_from_torrentio_style_titles() {
        assertEquals(10, StreamParser.parse("Film 1080p WEBRip\n👤 10 💾 2.9 GB").seeders)
        assertEquals(7, StreamParser.parse("Film 1080p Seeders: 7").seeders)
        assertNull(StreamParser.parse("Film 1080p WEBRip").seeders)
    }

    @Test fun a_torrent_nobody_is_sharing_is_rejected() {
        val r = rank(torrent("a", "Film 1080p WEB-DL x264", seeders = 0)).single()
        assertEquals(Verdict.INCOMPATIBLE, r.verdict)
        assertNull(StreamRanker.pickBest(listOf(r)))
    }

    @Test fun a_healthy_1080_beats_a_barely_seeded_4k() {
        val fragile = torrent("f", "Film 2160p WEB-DL HEVC HDR10", seeders = 2, mbps = 25.0)
        val healthy = torrent("h", "Film 1080p WEB-DL x264", seeders = 150, mbps = 8.0)
        assertEquals("h", StreamRanker.pickBest(rank(fragile, healthy))!!.candidate.id)
    }

    @Test fun seeders_break_ties_between_equal_quality() {
        val few = torrent("few", "Film 1080p WEB-DL x264", seeders = 8)
        val many = torrent("many", "Film 1080p WEB-DL x264", seeders = 120)
        assertEquals("many", StreamRanker.pickBest(rank(few, many))!!.candidate.id)
    }

    @Test fun unknown_seeders_are_neither_rewarded_nor_punished() {
        val a = rank(torrent("a", "Film 1080p WEB-DL x264", seeders = null)).single().score
        val b = rank(torrent("b", "Film 1080p WEB-DL x264", seeders = 12)).single().score
        assertEquals(a, b)   // 12 seeders is the neutral band
    }

    @Test fun direct_links_ignore_seeders() {
        val meta = StreamParser.parse("Film 1080p WEB-DL x264", 6L shl 30).copy(seeders = 0)
        val c = StreamCandidate("d", "P", "d", "https://x/d.mkv", null, meta)
        assertNotEquals(Verdict.INCOMPATIBLE, rank(c).single().verdict)
    }

    // ---- learning from Stremio playback ----
    private val pending = PendingHandoff("tt1", "Torrentio", Resolution.P1080, VideoCodec.H264, SourceType.WEB_DL, 8_000_000, handedAtMs = 0)

    @Test fun a_normal_session_records_startup_and_play_time() {
        val r = HandoffRecorder(pending)
        r.onState(HandoffRecorder.STATE_BUFFERING, 1_000)
        r.onState(HandoffRecorder.STATE_PLAYING, 9_000)
        val rec = r.finish(9_000 + 120_000)!!
        assertEquals("measured from when the player opened, not from the hand-off", 8_000L, rec.startupTimeMs)
        assertEquals(120_000L, rec.playDurationMs)
        assertFalse(rec.failed)
        assertEquals(0, rec.bufferEventCount)
    }

    @Test fun only_long_mid_play_stalls_count_as_buffering() {
        val r = HandoffRecorder(pending)
        r.onState(HandoffRecorder.STATE_PLAYING, 5_000)
        r.onState(HandoffRecorder.STATE_BUFFERING, 40_000)       // 1 s: a seek, ignored
        r.onState(HandoffRecorder.STATE_PLAYING, 41_000)
        r.onState(HandoffRecorder.STATE_BUFFERING, 70_000)       // 8 s: real
        r.onState(HandoffRecorder.STATE_PLAYING, 78_000)
        val rec = r.finish(200_000)!!
        assertEquals(1, rec.bufferEventCount)
        assertEquals(8_000L, rec.totalBufferDurationMs)
    }

    @Test fun pausing_is_not_a_fault_and_is_not_play_time() {
        val r = HandoffRecorder(pending)
        r.onState(HandoffRecorder.STATE_PLAYING, 5_000)
        r.onState(2, 65_000)                                      // paused
        r.onState(HandoffRecorder.STATE_PLAYING, 600_000)
        val rec = r.finish(660_000)!!
        assertEquals(0, rec.bufferEventCount)
        assertEquals(120_000L, rec.playDurationMs)
    }

    @Test fun leaving_within_seconds_teaches_nothing() {
        val r = HandoffRecorder(pending)
        r.onState(HandoffRecorder.STATE_PLAYING, 5_000)
        assertNull(r.finish(15_000))
    }

    @Test fun a_film_that_never_starts_is_recorded_as_a_failure_only_after_four_minutes() {
        val r = HandoffRecorder(pending)
        r.onState(HandoffRecorder.STATE_BUFFERING, 2_000)
        assertFalse(r.neverStarted(60_000))
        assertNull("too early to blame the source", HandoffRecorder(pending).finish(60_000))
        val late = r.finish(5 * 60_000)!!
        assertTrue(late.failed)
        assertNull(late.startupTimeMs)
    }
}
