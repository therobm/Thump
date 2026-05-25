package com.therobm.thump.playback

/**
 * A single entry passed to PlaybackController.playQueue.
 *
 * Carries everything the player needs to fetch audio (the authenticated stream URL) and
 * everything the mini player needs to render (title, artist, art). Built by the caller from
 * whatever source the queue came from — an album's tracks, a playlist's entries, or a single
 * tile tap.
 */
data class PlaybackQueueItem(
    val trackId: String,
    val streamUrl: String,
    val title: String,
    val artist: String,
    val coverArtUrl: String?,
)
