package com.example.tvlauncher.data.stream

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.util.Locale

private const val TAG = "SmartStream"
private const val MAX_PROBES = 3

enum class SmartStatus { OK, NO_SOURCES, NO_STREAMS, NOTHING_PLAYABLE, OFFLINE, ERROR }

data class SmartResult(
    val status: SmartStatus,
    val ranked: List<RankedStream>,
    val best: RankedStream?,
    val network: NetworkEstimate?,
    val runtimeSec: Long?
)

/**
 * The whole pipeline behind PLAY: film id -> configured addons -> candidates -> parsed metadata ->
 * device and network filtering -> reliability-aware ranking -> best pick. The UI only ever sees a
 * [SmartResult]; it knows nothing about individual addons.
 */
class SmartStreamRepository(private val context: Context) {
    private val sources = StreamSources(context)
    private val runtimes = RuntimeLookup(context)
    private val network = NetworkEstimator(context)
    private val history = PlaybackStore(context)
    private val debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Blocking; call off the main thread. Never throws. */
    fun select(imdbId: String, title: String): SmartResult = try {
        val addons = sources.addons()
        when {
            addons.isEmpty() -> SmartResult(if (sources.stremioConnected) SmartStatus.NO_STREAMS else SmartStatus.NO_SOURCES, emptyList(), null, null, null)
            !network.isOnline() -> SmartResult(SmartStatus.OFFLINE, emptyList(), null, null, null)
            else -> run(imdbId, title, addons)
        }
    } catch (e: Exception) {
        Log.w(TAG, "select failed: ${e.javaClass.simpleName}: ${e.message}")
        SmartResult(SmartStatus.ERROR, emptyList(), null, null, null)
    }

    private fun run(imdbId: String, title: String, addons: List<AddonConfig>): SmartResult {
        val candidates = sources.query(imdbId, addons)
        if (candidates.isEmpty()) return SmartResult(SmartStatus.NO_STREAMS, emptyList(), null, null, null)
        val runtime = runtimes.seconds(imdbId)
        val device = DeviceProfile.detect(context)
        val stats = history.statsByProvider()

        var estimate = network.current()
        val pool = candidates.toMutableList()
        var ranked = StreamRanker.rank(pool, device, estimate, stats, runtime)

        // Check the front-runner before recommending it: expired or dead links are dropped (up to three times),
        // and when our bandwidth estimate is stale the same request also measures real speed.
        for (attempt in 1..MAX_PROBES) {
            val lead = StreamRanker.pickBest(ranked)?.candidate ?: break
            val url = lead.url ?: break
            if (lead.viaEngine) break   // probing would start a torrent; the engine is already known to be up
            val probe = network.probe(url, lead.headers, measure = network.isStale())
            if (probe.alive) {
                probe.bps?.let { network.record(it); estimate = network.current() }
                ranked = StreamRanker.rank(pool, device, estimate, stats, runtime)
                break
            }
            Log.w(TAG, "Dropping dead link from ${lead.provider}")
            pool.remove(lead)
            ranked = StreamRanker.rank(pool, device, estimate, stats, runtime)
        }
        if (pool.isEmpty()) return SmartResult(SmartStatus.NO_STREAMS, emptyList(), null, estimate, runtime)

        val best = StreamRanker.pickBest(ranked)
        if (debug) logDecision(title, estimate, ranked, best)
        val status = if (best == null) SmartStatus.NOTHING_PLAYABLE else SmartStatus.OK
        return SmartResult(status, ranked, best, estimate, runtime)
    }

    /** Debug builds only: enough detail to tune the weights. Never includes URLs. */
    private fun logDecision(title: String, estimate: NetworkEstimate, ranked: List<RankedStream>, best: RankedStream?) {
        val sb = StringBuilder("Film: $title\n")
        sb.append(String.format(Locale.UK, "Network estimate: %.0f Mbps (%s)\nSafe bandwidth: %.1f Mbps\n", estimate.mbps, estimate.basis, estimate.safeBps() / 1e6))
        ranked.take(10).forEachIndexed { i, r ->
            sb.append("#${i + 1}\n${r.candidate.meta.qualityLabel()} [${r.candidate.provider}]${if (!r.playable) " (needs Stremio)" else ""}\n")
            sb.append(String.format(Locale.UK, "bitrate: %.0f Mbps%s\n", r.requiredBps / 1e6, if (r.requiredKnown) "" else " (assumed)"))
            when (r.verdict) {
                Verdict.MAY_BUFFER, Verdict.INCOMPATIBLE -> sb.append("rejected: ${r.reason}\n")
                else -> sb.append("score: ${r.score}${if (r === best) "\nSELECTED" else ""}\n")
            }
            r.candidate.meta.seeders?.let { sb.append("seeders: $it\n") }
            if (r.notes.isNotEmpty()) sb.append("notes: ${r.notes.joinToString(", ")}\n")
        }
        Log.d(TAG, sb.toString())
    }
}
