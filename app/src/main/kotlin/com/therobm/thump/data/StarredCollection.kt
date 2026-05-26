package com.therobm.thump.data

/**
 * User's starred items: songs (returned as Tracks), albums, and artists.
 */
data class StarredCollection(
    val tracks: List<Track>,
    val albums: List<Album>,
    val artists: List<Artist>,
)
