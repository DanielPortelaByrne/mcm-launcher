package com.example.tvlauncher.data.live

/** One entry of a free-to-air channel list. */
data class IptvChannel(val name: String, val url: String, val logo: String?, val group: String?)

/**
 * Reads the common `.m3u` channel-list format: an `#EXTINF` line (attributes, then a comma and the channel
 * name) followed by the stream address. Only plain http(s) streams are kept; anything else cannot be played here.
 */
object M3u {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")
    private val tag = Regex("""\s*\[[^\]]*]""")   // "[Geo-blocked]", "[Not 24/7]" and similar notes
    private val quality = Regex("""\s*\(\d{3,4}p\)""")   // "(720p)"

    fun parse(text: String): List<IptvChannel> {
        val out = ArrayList<IptvChannel>()
        var pending: Triple<String, String?, String?>? = null   // name, logo, group
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val shown = line.substringAfterLast(',', "")
                    val name = shown.replace(tag, "").replace(quality, "").trim().ifBlank { attrs["tvg-name"].orEmpty() }
                    // Region-locked relays (a BBC One for each English region, say) only play in their own region.
                    pending = if (name.isBlank() || shown.contains("[Geo-blocked]", ignoreCase = true)) null
                        else Triple(name, attrs["tvg-logo"]?.ifBlank { null }, attrs["group-title"]?.ifBlank { null })
                }
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val p = pending
                    if (p != null && (line.startsWith("http://") || line.startsWith("https://"))) out += IptvChannel(p.first, line, p.second, p.third)
                    pending = null
                }
            }
        }
        // The same channel often appears once per quality: keep the first, which lists are ordered to put first.
        return out.distinctBy { it.name.lowercase() }
    }
}
