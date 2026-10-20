package com.example.tvlauncher.ui

import android.app.Activity
import com.example.tvlauncher.data.jellyfin.JellyfinClient
import com.example.tvlauncher.data.jellyfin.JellyfinItem
import com.example.tvlauncher.data.stream.RankedStream
import com.example.tvlauncher.data.stream.StreamCandidate
import com.example.tvlauncher.data.stream.StreamMetadata
import com.example.tvlauncher.data.stream.Verdict

/** Plays something from the owner's Jellyfin in the launcher's own player. */
object JellyfinPlayback {
    /**
     * The file as stored first, then a copy Jellyfin re-encodes for this TV, which the player falls back to on its
     * own. [resumeKey] keeps a separate "where I got to" for each film or episode.
     */
    fun play(activity: Activity, client: JellyfinClient, item: JellyfinItem, title: String, resumeKey: String) {
        val headers = client.playbackHeaders()
        fun ranked(label: String, url: String) = RankedStream(
            StreamCandidate("jellyfin:${item.id}:$label", "Jellyfin", label, url, null, StreamMetadata(), headers = headers),
            100, 0L, false, Verdict.RECOMMENDED, null, emptyList()
        )
        PlayerActivity.launch(activity, listOf(ranked("Original file", client.streamUrl(item)), ranked("Converted for this TV", client.transcodeUrl(item))), title, resumeKey)
    }
}
