package com.example.tvlauncher.data.stream

/** Reads quality metadata out of messy release names, titles and descriptions. Never throws. */
object StreamParser {

    private fun token(pattern: String) = Regex("(?<![a-z0-9])(?:$pattern)(?![a-z0-9])", RegexOption.IGNORE_CASE)

    private val r2160 = token("2160p|2160i|4k|uhd")
    private val r1080 = token("1080p|1080i|fhd|full.?hd")
    private val r720 = token("720p")
    private val rSd = token("480p|576p|dvdrip|dvd")

    private val rRemux = token("remux|bdremux")
    private val rBluray = token("blu-?ray|bdrip|brrip|bd-?rip")
    private val rWebDl = token("web-?dl|webdl")
    private val rWebRip = token("web-?rip|webrip")
    private val rHdtv = token("hdtv")
    private val rCam = token("cam|hdcam|telesync|hdts|telecine")
    private val rWebGeneric = token("web")

    private val rAv1 = token("av1")
    private val rHevc = token("hevc|h\\.?265|x265")
    private val rAvc = token("avc|h\\.?264|x264")

    private val rDv = token("dv|dovi|dolby.?vision")
    private val rHdr10Plus = token("hdr10\\+|hdr10plus")
    private val rHdr10 = token("hdr10")
    private val rHdr = token("hdr")
    private val rSdr = token("sdr")

    private val rAtmos = token("atmos")
    private val rTrueHd = token("true-?hd")
    private val rDtsHd = token("dts-?hd(?:\\.?ma)?|dts-?x")
    private val rDts = token("dts")
    private val rEac3 = token("ddp(?:\\d(?:\\.\\d)?)?|dd\\+|e-?ac-?3")
    private val rAc3 = token("ac-?3|dd\\d(?:\\.\\d)?")
    private val rAac = token("aac(?:\\d(?:\\.\\d)?)?")

    /** "\uD83D\uDC64 10" (Torrentio and similar) or "Seeders: 10". */
    private val rSeeders = Regex("(?:\\uD83D\\uDC64|seeders?|seeds?)\\s*[:=]?\\s*(\\d+)", RegexOption.IGNORE_CASE)

    private val rSize = Regex("(\\d+(?:[.,]\\d+)?)\\s?(TB|GB|MB|GiB|MiB|TiB)", RegexOption.IGNORE_CASE)

    /** [text] is everything known about the stream (name, title, description, filename). */
    fun parse(text: String?, sizeBytes: Long? = null, filename: String? = null): StreamMetadata {
        val t = text.orEmpty()
        val resolution = when {
            r2160.containsMatchIn(t) -> Resolution.P2160
            r1080.containsMatchIn(t) -> Resolution.P1080
            r720.containsMatchIn(t) -> Resolution.P720
            rSd.containsMatchIn(t) -> Resolution.SD
            else -> Resolution.UNKNOWN
        }
        val source = when {
            rRemux.containsMatchIn(t) -> SourceType.REMUX
            rCam.containsMatchIn(t) -> SourceType.CAM
            rWebDl.containsMatchIn(t) -> SourceType.WEB_DL
            rWebRip.containsMatchIn(t) -> SourceType.WEBRIP
            rBluray.containsMatchIn(t) -> SourceType.BLURAY
            rHdtv.containsMatchIn(t) -> SourceType.HDTV
            rWebGeneric.containsMatchIn(t) -> SourceType.WEB_DL
            else -> SourceType.UNKNOWN
        }
        val codec = when {
            rAv1.containsMatchIn(t) -> VideoCodec.AV1
            rHevc.containsMatchIn(t) -> VideoCodec.HEVC
            rAvc.containsMatchIn(t) -> VideoCodec.H264
            else -> VideoCodec.UNKNOWN
        }
        val hasDv = rDv.containsMatchIn(t)
        val hasHdr10 = rHdr10.containsMatchIn(t) || rHdr.containsMatchIn(t)
        val range = when {
            hasDv -> DynamicRange.DOLBY_VISION
            rHdr10Plus.containsMatchIn(t) -> DynamicRange.HDR10_PLUS
            hasHdr10 -> DynamicRange.HDR10
            rSdr.containsMatchIn(t) -> DynamicRange.SDR
            else -> DynamicRange.UNKNOWN
        }
        val audio = when {
            rAtmos.containsMatchIn(t) -> AudioFormat.ATMOS
            rTrueHd.containsMatchIn(t) -> AudioFormat.TRUEHD
            rDtsHd.containsMatchIn(t) -> AudioFormat.DTS_HD
            rDts.containsMatchIn(t) -> AudioFormat.DTS
            rEac3.containsMatchIn(t) -> AudioFormat.EAC3
            rAc3.containsMatchIn(t) -> AudioFormat.AC3
            rAac.containsMatchIn(t) -> AudioFormat.AAC
            else -> AudioFormat.UNKNOWN
        }
        return StreamMetadata(
            resolution, source, codec, range,
            hdrFallback = hasDv && (hasHdr10 || rHdr10Plus.containsMatchIn(t)),
            audio = audio,
            sizeBytes = sizeBytes ?: parseSize(t),
            filename = filename,
            seeders = rSeeders.find(t)?.groupValues?.get(1)?.toIntOrNull()
        )
    }

    /** "14.5 GB" -> bytes. Takes the first size mentioned. */
    fun parseSize(text: String?): Long? {
        val m = rSize.find(text.orEmpty()) ?: return null
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val unit = when (m.groupValues[2].uppercase()) {
            "TB", "TIB" -> 1L shl 40
            "GB", "GIB" -> 1L shl 30
            else -> 1L shl 20
        }
        return (value * unit).toLong().takeIf { it > 0 }
    }
}
