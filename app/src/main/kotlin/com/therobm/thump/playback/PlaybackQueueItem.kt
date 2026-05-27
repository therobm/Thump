package com.therobm.thump.playback

/**
 * A single entry passed to PlaybackController.playQueue.
 *
 * Carries the stable identifiers ExoPlayer (through ThumpData) and the in-app art renderer
 * (through ArtImage) need to resolve audio bytes and cover art, plus the display strings the
 * mini player and full Now Playing screen surface. Built by the caller from whatever source
 * the queue came from — an album's tracks, a playlist's entries, or a single tile tap.
 *
 * album is nullable because some entry points (e.g. a Home Recently Played track tile) do not
 * carry the album name through the carousel; the UI hides the line when absent.
 */
data class PlaybackQueueItem(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtId: String?,
)
