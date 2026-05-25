package com.therobm.thump.subsonic

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the standard OpenSubsonic detail endpoints (getAlbum, getPlaylist, getArtist).
 *
 * Each lives inside the standard subsonic-response envelope as a single named field (`album`,
 * `playlist`, or `artist`). Field names match the wire exactly, optional fields default to null
 * or empty so the deserializer tolerates servers that omit them.
 */
@Serializable
data class StandardAlbumDetailPayload(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val song: List<StandardSongDetail> = emptyList(),
)

@Serializable
data class StandardPlaylistDetailPayload(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val entry: List<StandardSongDetail> = emptyList(),
)

@Serializable
data class StandardArtistDetailPayload(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val album: List<StandardArtistAlbum> = emptyList(),
)

@Serializable
data class StandardArtistAlbum(
    val id: String,
    val name: String,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val year: Int? = null,
)

@Serializable
data class StandardSongDetail(
    val id: String,
    val title: String,
    val artist: String? = null,
    val artistId: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val coverArt: String? = null,
    val duration: Int? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
)
