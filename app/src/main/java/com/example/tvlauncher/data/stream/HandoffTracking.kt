package com.example.tvlauncher.data.stream

/** A film handed to Stremio's player, waiting to be seen playing. */
data class PendingHandoff(
    val contentId: String,
    val provider: String,
    val resolution: Resolution,
    val codec: VideoCodec,
    val source: SourceType,
    val estimatedBitrateBps: Long?,
    val handedAtMs: Long
)

/**
 * Turns Stremio's media-session events into one [PlaybackRecord], so source reliability keeps learning
 * even though Stremio, not the launcher, plays the film. Pure logic: the service feeds it events.
 *
 * Limits: a session cannot tell a seek from a stall, so only waits over [MIN_STALL_MS] count as buffering,
 * and pausing is never treated as a fault.
 */
class HandoffRecorder(private val pending: PendingHandoff) {
    private var firstPlayAt: Long? = null
    /** When Stremio's player first showed signs of life. Startup is measured from here, not from the hand-off, which also contains the time you took to press play on Stremio's page. */
    private var openedAt: Long? = null
    private var lastState = STATE_NONE
    private var stallStart = 0L
    private var bufferEvents = 0
    private var bufferMs = 0L
    private var playMs = 0L
    private var playingSince = 0L
    private var finished = false

    /** [state] is one of the STATE_ constants. */
    fun onState(state: Int, nowMs: Long) {
        if (finished || state == lastState) return
        if (openedAt == null) openedAt = nowMs
        // leaving PLAYING
        if (lastState == STATE_PLAYING && playingSince != 0L) { playMs += nowMs - playingSince; playingSince = 0 }
        // leaving BUFFERING after playback began
        if (lastState == STATE_BUFFERING && stallStart != 0L) {
            val waited = nowMs - stallStart
            if (waited >= MIN_STALL_MS) { bufferEvents++; bufferMs += waited }
            stallStart = 0
        }
        when (state) {
            STATE_PLAYING -> { if (firstPlayAt == null) firstPlayAt = nowMs; playingSince = nowMs }
            STATE_BUFFERING -> if (firstPlayAt != null) stallStart = nowMs
        }
        lastState = state
    }

    val hasStarted: Boolean get() = firstPlayAt != null

    /** True when the film never started within [NEVER_STARTED_MS] of the hand-off. */
    fun neverStarted(nowMs: Long) = firstPlayAt == null && nowMs - pending.handedAtMs > NEVER_STARTED_MS

    /** The record for this session, or null when there is nothing worth learning from (e.g. left within seconds). */
    fun finish(nowMs: Long, observedBps: Long? = null): PlaybackRecord? {
        if (finished) return null
        finished = true
        if (lastState == STATE_PLAYING && playingSince != 0L) playMs += nowMs - playingSince
        val started = firstPlayAt
        val failed = started == null
        if (!failed && playMs < MIN_PLAY_MS) return null
        if (failed && !neverStarted(nowMs)) return null
        return PlaybackRecord(
            pending.contentId, pending.provider, pending.resolution, pending.codec, pending.source, pending.estimatedBitrateBps,
            started?.let { s -> openedAt?.let { s - it } }, observedBps, bufferEvents, bufferMs, playMs,
            completedNormally = false, failed = failed, timestampMs = nowMs
        )
    }

    companion object {
        const val STATE_NONE = 0
        const val STATE_PLAYING = 3
        const val STATE_BUFFERING = 6
        const val MIN_STALL_MS = 3_000L
        const val MIN_PLAY_MS = 30_000L
        const val NEVER_STARTED_MS = 4 * 60_000L
    }
}
