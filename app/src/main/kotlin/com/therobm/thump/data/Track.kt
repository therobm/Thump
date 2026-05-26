package com.therobm.thump.data

/**
 * Thump-shaped track record. Translated from the server's SongID3 wire shape.
 *
 * `coverArtId` is the id the cover-art endpoints resolve, not a URL. Salted URLs only exist
 * inside the IProtocol HTTP code; persisted playback state and MediaItem metadata carry
 * stable `thump://track/<id>` URIs that ExoPlayer hands to `ThumpData.open`.
 */
data class Track(
    val trackId: String,
    val title: String,
    val artistName: String?,
    val artistId: String?,
    val albumName: String?,
    val albumId: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val genre: String?,
    val durationSeconds: Int?,
    val sizeBytes: Long?,
    val suffix: String?,
    val contentType: String?,
    val coverArtId: String?,
)
