package com.therobm.thump.subsonic

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the library-listing endpoints: getArtists (indexed alphabetically) and
 * getGenres (flat list).
 *
 * Field names match the wire exactly. Optional fields default to null or empty so the
 * deserializer tolerates servers that omit them.
 */
@Serializable
data class StandardArtistsPayload(
    val ignoredArticles: String? = null,
    val index: List<StandardArtistsIndex> = emptyList(),
)

@Serializable
data class StandardArtistsIndex(
    val name: String,
    val artist: List<StandardLibraryArtist> = emptyList(),
)

@Serializable
data class StandardLibraryArtist(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
)

@Serializable
data class StandardGenresPayload(
    val genre: List<StandardGenre> = emptyList(),
)

@Serializable
data class StandardGenre(
    val value: String,
    val songCount: Int? = null,
    val albumCount: Int? = null,
)
