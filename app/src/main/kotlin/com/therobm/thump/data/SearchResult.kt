package com.therobm.thump.data

/**
 * Aggregated result of a single `search` call. Each category may be empty.
 */
data class SearchResult(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
)
