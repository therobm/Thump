package com.therobm.thump.data

/**
 * Thump-shaped playlist record. `tracks` is empty on summary calls (`getAllPlaylists`) and
 * populated on detail calls (`getPlaylist`).
 */
data class Playlist(
    val playlistId: String,
    val name: String,
    val ownerUsername: String?,
    val comment: String?,
    val songCount: Int?,
    val durationSeconds: Int?,
    val coverArtId: String?,
    val tracks: List<Track>,
)
