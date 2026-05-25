package com.therobm.thump.playback

/**
 * The track currently loaded into the player, plus whether it is actively playing.
 *
 * Held in PlaybackController's state flow. The UI binds to it to render the mini player and the
 * full now-playing screen. coverArtUrl is the already-built getCoverArt URL so the UI does not
 * need a reference to the SubsonicClient just to fetch art. album is optional — some entry
 * points do not carry it through.
 */
data class NowPlaying(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtUrl: String?,
    val isPlaying: Boolean,
    val source: PlaybackSource?,
)
