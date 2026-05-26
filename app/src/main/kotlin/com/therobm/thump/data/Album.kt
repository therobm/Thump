package com.therobm.thump.data

/**
 * Thump-shaped album record. The track list is populated by detail calls (`getAlbum`) and left
 * empty on summary calls (`getAllAlbums`, `getStarred`).
 */
data class Album(
    val albumId: String,
    val name: String,
    val artistName: String?,
    val artistId: String?,
    val year: Int?,
    val genre: String?,
    val durationSeconds: Int?,
    val songCount: Int?,
    val coverArtId: String?,
    val tracks: List<Track>,
)
