package com.example.tvlauncher.ui.guide

/** Where a programme sits on the guide's time ruler, in dp from the ruler's left edge. */
data class Span(val offsetDp: Int, val widthDp: Int, val cutOnLeft: Boolean, val cutOnRight: Boolean)

/** The arithmetic behind the programme guide, kept free of Android so it can be tested. */
object GuideMath {
    const val SLOT_MIN = 30
    private const val MIN_MS = 60_000L

    /** The guide opens on the half hour just gone, so what is on now always has a little of its start showing. */
    fun windowStart(nowMs: Long): Long = nowMs - Math.floorMod(nowMs, SLOT_MIN * MIN_MS)

    /** [startMs, endMs) clipped to the window, or null when it does not touch it. */
    fun span(startMs: Long, endMs: Long, windowStart: Long, windowEnd: Long, dpPerMin: Float): Span? {
        if (endMs <= windowStart || startMs >= windowEnd || endMs <= startMs) return null
        val from = maxOf(startMs, windowStart)
        val to = minOf(endMs, windowEnd)
        val offset = (from - windowStart) / MIN_MS.toFloat() * dpPerMin
        val width = (to - from) / MIN_MS.toFloat() * dpPerMin
        return Span(Math.round(offset), Math.round(width).coerceAtLeast(1), cutOnLeft = (startMs < windowStart), cutOnRight = (endMs > windowEnd))
    }

    /** How far through [startMs, endMs) it is at [nowMs], 0..1; 0 when it has not started, 1 when it is over. */
    fun progress(startMs: Long, endMs: Long, nowMs: Long): Float =
        if (endMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)

    fun isOnNow(startMs: Long, endMs: Long, nowMs: Long) = nowMs in startMs until endMs

    /** Offset of "now" on the ruler, in dp. */
    fun nowOffsetDp(nowMs: Long, windowStart: Long, dpPerMin: Float): Int = Math.round((nowMs - windowStart) / MIN_MS.toFloat() * dpPerMin)

    /** The tick times along the ruler, one per slot, from the window start (inclusive) to its end (exclusive). */
    fun ticks(windowStart: Long, windowEnd: Long): List<Long> = generateSequence(windowStart) { it + SLOT_MIN * MIN_MS }.takeWhile { it < windowEnd }.toList()
}
