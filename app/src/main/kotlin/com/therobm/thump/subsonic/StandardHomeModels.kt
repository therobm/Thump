package com.therobm.thump.subsonic

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the standard OpenSubsonic endpoints that feed the home screen.
 *
 * These all sit inside the standard subsonic-response envelope. Each top-level response wraps
 * the endpoint-specific payload in a single named field, matching the JSON the server returns.
 *
 * Field names match the wire exactly. Optional fields default to null so the deserializer
 * tolerates servers that omit them; required fields have no default and will fail decode if
 * absent.
 */
@Serializable
data class StandardAlbumList2Wrapper(
    val albumList2: StandardAlbumList2Payload,
)

@Serializable
data class StandardAlbumList2Payload(
    val album: List<StandardAlbumSummary> = emptyList(),
)

@Serializable
data class StandardAlbumSummary(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val created: String? = null,
)

@Serializable
data class StandardPlaylistsWrapper(
    val playlists: StandardPlaylistsPayload,
)

@Serializable
data class StandardPlaylistsPayload(
    val playlist: List<StandardPlaylistSummary> = emptyList(),
)

@Serializable
data class StandardPlaylistSummary(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val created: String? = null,
    val changed: String? = null,
)

@Serializable
data class StandardStarred2Wrapper(
    val starred2: StandardStarred2Payload,
)

@Serializable
data class StandardStarred2Payload(
    val song: List<StandardStarredSong> = emptyList(),
    val album: List<StandardAlbumSummary> = emptyList(),
    val artist: List<StandardStarredArtist> = emptyList(),
)

@Serializable
data class StandardStarredSong(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val duration: Int? = null,
)

@Serializable
data class StandardStarredArtist(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
)
