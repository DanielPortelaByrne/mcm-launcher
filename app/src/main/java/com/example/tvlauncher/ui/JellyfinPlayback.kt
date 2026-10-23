package com.example.tvlauncher.ui

import android.app.Activity
import com.example.tvlauncher.data.jellyfin.JellyfinApi
import com.example.tvlauncher.data.jellyfin.JellyfinClient
import com.example.tvlauncher.data.jellyfin.JellyfinItem
import com.example.tvlauncher.data.jellyfin.JellyfinReport
import com.example.tvlauncher.data.stream.RankedStream
import com.example.tvlauncher.data.stream.StreamCandidate
import com.example.tvlauncher.data.stream.StreamMetadata
import com.example.tvlauncher.data.stream.Verdict

/** Plays something from the owner's Jellyfin in the launcher's own player, and reports how far the viewer gets. */
object JellyfinPlayback {
    data class Plan(val streams: List<RankedStream>, val title: String, val resumeKey: String, val report: JellyfinReport)
    /**
     * The file as stored first, then a copy Jellyfin re-encodes for this TV, which the player falls back to on its
     * own. [resumeKey] keeps a separate "where I got to" for each film or episode. Playback starts where Jellyfin says
     * the viewer left off, and its progress is sent back so the server's "continue watching" stays right.
     */
    fun play(activity: Activity, client: JellyfinClient, item: JellyfinItem, title: String, resumeKey: String) {
        Perf.mark("library.play")
        val plan = prepare(client, item, title, resumeKey)
        PlayerActivity.launch(activity, plan.streams, plan.title, plan.resumeKey, plan.report)
    }

    /** Builds the next playback locally, so the countdown never waits on an extra item lookup. */
    fun prepare(client: JellyfinClient, item: JellyfinItem, title: String, resumeKey: String): Plan {
        val headers = client.playbackHeaders()
        fun ranked(label: String, url: String) = RankedStream(
            StreamCandidate("jellyfin:${item.id}:$label", "Jellyfin", label, url, null, StreamMetadata(), headers = headers),
            100, 0L, false, Verdict.RECOMMENDED, null, emptyList()
        )
        val report = JellyfinReport(JellyfinApi.cleanBase(client.server), item.id, item.sourceId, if (item.played) 0L else item.resumeMs)
        return Plan(listOf(ranked("Original file", client.streamUrl(item)), ranked("Converted for this TV", client.transcodeUrl(item))), title, resumeKey, report)
    }
}
