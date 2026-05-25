package com.therobm.thump.playback

/**
 * A single entry passed to PlaybackController.playQueue.
 *
 * Carries everything the player needs to fetch audio (the authenticated stream URL) and
 * everything the mini player and full Now Playing screen need to render (title, artist,
 * album, art). Built by the caller from whatever source the queue came from — an album's
 * tracks, a playlist's entries, or a single tile tap.
 *
 * album is nullable because some entry points (e.g. a Home Recently Played track tile) do not
 * carry the album name through the carousel; the UI hides the line when absent.
 */
data class PlaybackQueueItem(
    val trackId: String,
    val streamUrl: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtUrl: String?,
)
