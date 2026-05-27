package com.therobm.thump.playback

/**
 * The track currently loaded into the player, plus whether it is actively playing.
 *
 * Held in PlaybackController's state flow. The UI binds to it to render the mini player and the
 * full now-playing screen. coverArtId is the stable cover-art identifier; the renderer calls
 * ThumpData.getCoverArt to materialise a Bitmap. album is optional — some entry points do not
 * carry it through.
 *
 * unavailableReason is non-null when the track is loaded but cannot play (prefetch failed offline,
 * no protocol configured, transport error). The UI uses it to show an error indicator instead of
 * normal play/pause affordance. Null means the normal playable state.
 */
data class NowPlaying(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val coverArtId: String?,
    val isPlaying: Boolean,
    val source: PlaybackSource?,
    val unavailableReason: String? = null,
)
