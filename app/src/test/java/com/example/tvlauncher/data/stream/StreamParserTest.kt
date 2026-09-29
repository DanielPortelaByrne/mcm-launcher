package com.example.tvlauncher.data.stream

import org.junit.Assert.*
import org.junit.Test

class StreamParserTest {
    @Test fun web_dl_4k_dv_hdr_hevc_atmos() {
        val m = StreamParser.parse("Movie.2026.2160p.WEB-DL.DV.HDR.HEVC.Atmos.mkv")
        assertEquals(Resolution.P2160, m.resolution)
        assertEquals(SourceType.WEB_DL, m.source)
        assertEquals(VideoCodec.HEVC, m.codec)
        assertEquals(DynamicRange.DOLBY_VISION, m.range)
        assertTrue("DV + HDR keeps an HDR10 fallback", m.hdrFallback)
        assertEquals(AudioFormat.ATMOS, m.audio)
    }

    @Test fun bluray_1080_x264_dts() {
        val m = StreamParser.parse("Movie.2026.1080p.BluRay.x264.DTS.mkv")
        assertEquals(Resolution.P1080, m.resolution)
        assertEquals(SourceType.BLURAY, m.source)
        assertEquals(VideoCodec.H264, m.codec)
        assertEquals(AudioFormat.DTS, m.audio)
        assertEquals(DynamicRange.UNKNOWN, m.range)
    }

    @Test fun remux_4k_truehd_atmos_reports_atmos() {
        val m = StreamParser.parse("Movie.2026.2160p.REMUX.HEVC.TrueHD.Atmos.mkv")
        assertEquals(SourceType.REMUX, m.source)
        assertEquals(AudioFormat.ATMOS, m.audio)
    }

    @Test fun plain_dv_without_hdr_has_no_fallback() {
        val m = StreamParser.parse("Movie 2160p WEB-DL DoVi HEVC")
        assertEquals(DynamicRange.DOLBY_VISION, m.range)
        assertFalse(m.hdrFallback)
    }

    @Test fun hdr10_plus_and_hdr10() {
        assertEquals(DynamicRange.HDR10_PLUS, StreamParser.parse("Movie 2160p HDR10+ HEVC").range)
        assertEquals(DynamicRange.HDR10, StreamParser.parse("Movie 2160p HDR10 HEVC").range)
    }

    @Test fun av1_and_4k_alias() {
        val m = StreamParser.parse("Movie 4K WEBRip AV1 DDP5.1")
        assertEquals(Resolution.P2160, m.resolution)
        assertEquals(VideoCodec.AV1, m.codec)
        assertEquals(SourceType.WEBRIP, m.source)
        assertEquals(AudioFormat.EAC3, m.audio)
    }

    @Test fun cam_is_recognised() {
        assertEquals(SourceType.CAM, StreamParser.parse("Movie.2026.HDCAM.x264").source)
    }

    @Test fun size_parsing() {
        assertEquals((13.5 * (1L shl 30)).toLong(), StreamParser.parseSize("size 13.5 GB"))
        assertEquals(700L * (1L shl 20), StreamParser.parseSize("700 MB"))
        assertEquals(2L * (1L shl 40), StreamParser.parseSize("2 TB"))
        assertNull(StreamParser.parseSize("no size here"))
    }

    @Test fun garbage_never_throws_and_stays_unknown() {
        val m = StreamParser.parse("###  ???")
        assertEquals(Resolution.UNKNOWN, m.resolution)
        assertEquals(SourceType.UNKNOWN, m.source)
        assertNull(m.sizeBytes)
        StreamParser.parse(null)
        StreamParser.parse("")
    }
}
