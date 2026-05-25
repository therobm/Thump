package com.therobm.thump.subsonic

import kotlinx.serialization.Serializable

/**
 * Wire shapes for the optional Pulse home-screen endpoints.
 *
 * These endpoints do not use the standard Subsonic envelope; the Pulse server returns the
 * payload as the top-level JSON object directly. Each response type is a single-field wrapper
 * around the list it carries so the client can deserialize without writing a custom decoder.
 *
 * Field names match the wire exactly, per GENERAL.md no-name-transforms rule.
 */
@Serializable
data class PulseRecentlyPlayedResponse(
    val tracks: List<PulseRecentlyPlayedTrack>,
)

/**
 * pulse/artistTracks returns the same per-track shape as pulse/recentlyPlayed (id, title,
 * artist, album, coverArt, duration) so the element type is shared. Only the wrapper differs
 * to keep the function signature on SubsonicClient self-describing.
 */
@Serializable
data class PulseArtistTracksResponse(
    val tracks: List<PulseRecentlyPlayedTrack>,
)

@Serializable
data class PulseRecentlyPlayedTrack(
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
data class PulsePopularArtistsResponse(
    val artists: List<PulsePopularArtist>,
)

@Serializable
data class PulsePopularArtist(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val score: Float? = null,
    val coverArt: String? = null,
)

@Serializable
data class PulseTopPlaylistsResponse(
    val playlists: List<PulseTopPlaylist>,
)

/**
 * pulse/recentPlaylists shares the per-item shape with pulse/topPlaylists — only the wrapper
 * field is reused. Element type is therefore shared.
 */
@Serializable
data class PulseRecentPlaylistsResponse(
    val playlists: List<PulseTopPlaylist>,
)

@Serializable
data class PulseTopPlaylist(
    val id: String,
    val name: String,
    val songCount: Int? = null,
    val duration: Int? = null,
    val score: Float? = null,
    val lastPlayed: String? = null,
    val coverArt: String? = null,
)
