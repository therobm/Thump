package com.therobm.thump.playback

import kotlinx.serialization.Serializable

/**
 * On-disk snapshot of the player's queue, current track index, current position, and the
 * source (album / playlist / artist) the queue came from. Written to SharedPreferences as a
 * single JSON string by both the app's PlaybackController (on playQueue) and by the playback
 * service (on track transitions and on a periodic position tick), and read on service start
 * to restore Auto's now-playing immediately on cold launch.
 */
@Serializable
data class PersistedPlaybackState(
    val items: List<PersistedItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val source: PersistedSource?,
)

@Serializable
data class PersistedItem(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtId: String?,
)

@Serializable
data class PersistedSource(
    val kind: String,
    val name: String,
)
