package com.therobm.thump.playback

/**
 * The track currently loaded into the player, plus whether it is actively playing.
 *
 * Held in PlaybackController's state flow. The UI binds to it to render the mini player and the
 * full now-playing screen. coverArtId is the stable cover-art identifier; the renderer calls
 * ThumpData.getCoverArt to materialise a Bitmap. album is optional — some entry points do not
 * carry it through.
 */
data class NowPlaying(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtId: String?,
    val isPlaying: Boolean,
    val source: PlaybackSource?,
)
