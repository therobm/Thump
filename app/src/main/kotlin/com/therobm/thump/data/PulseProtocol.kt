package com.therobm.thump.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * IProtocol over Pulse extensions. Pulse-specific home shelves are served by Pulse endpoints
 * (`pulse/recentlyPlayed`, `pulse/popularArtists`, `pulse/topPlaylists`). Everything else
 * falls through to a SubsonicProtocol instance the caller supplies, which Pulse-aware servers
 * also speak.
 *
 * The fallback object is taken in the constructor rather than constructed here so ThumpData
 * keeps responsibility for protocol construction and credential changes propagate uniformly.
 */
class PulseProtocol(
    private val subsonicFallback: SubsonicProtocol,
    private val okHttpClient: OkHttpClient,
    private val jsonDecoder: Json,
) : IProtocol {

    override suspend fun ping(): ServerInfo {
        return subsonicFallback.ping()
    }

    override suspend fun getAllArtists(): List<Artist> {
        return subsonicFallback.getAllArtists()
    }

    override suspend fun getArtist(artistId: String): Artist {
        return subsonicFallback.getArtist(artistId)
    }

    override suspend fun getArtistTracks(artistId: String): List<Track> {
        // Pulse has `pulse/artistTracks?id=` which avoids the per-album fan-out. Skeleton step
        // leaves the call stubbed via the Subsonic fallback so the IProtocol contract is met
        // without committing to wire details before the screen is ported.
        return subsonicFallback.getArtistTracks(artistId)
    }

    override suspend fun getAlbum(albumId: String): Album {
        return subsonicFallback.getAlbum(albumId)
    }

    override suspend fun getAllAlbums(sort: AlbumSort, limit: Int, offset: Int): List<Album> {
        return subsonicFallback.getAllAlbums(sort, limit, offset)
    }

    override suspend fun getGenres(): List<Genre> {
        return subsonicFallback.getGenres()
    }

    override suspend fun getTracksByGenre(genre: String, limit: Int, offset: Int): List<Track> {
        return subsonicFallback.getTracksByGenre(genre, limit, offset)
    }

    override suspend fun getAllPlaylists(): List<Playlist> {
        return subsonicFallback.getAllPlaylists()
    }

    override suspend fun getPlaylist(playlistId: String): Playlist {
        return subsonicFallback.getPlaylist(playlistId)
    }

    override suspend fun createPlaylist(name: String, trackIds: List<String>): Playlist {
        return subsonicFallback.createPlaylist(name, trackIds)
    }

    override suspend fun updatePlaylist(playlistId: String, edits: PlaylistEdits): Playlist {
        return subsonicFallback.updatePlaylist(playlistId, edits)
    }

    override suspend fun deletePlaylist(playlistId: String) {
        subsonicFallback.deletePlaylist(playlistId)
    }

    override suspend fun search(query: String): SearchResult {
        return subsonicFallback.search(query)
    }

    override suspend fun getStarred(): StarredCollection {
        return subsonicFallback.getStarred()
    }

    override suspend fun star(kind: StarKind, id: String) {
        subsonicFallback.star(kind, id)
    }

    override suspend fun unstar(kind: StarKind, id: String) {
        subsonicFallback.unstar(kind, id)
    }

    override suspend fun setRating(kind: StarKind, id: String, rating: Int) {
        subsonicFallback.setRating(kind, id, rating)
    }

    override suspend fun getRecentlyPlayed(limit: Int, types: Set<HomeItemKind>): List<HomeItem> {
        // TODO Flatline #223: `pulse/recentlyPlayed` does not yet accept the `types` query
        //  parameter. Once that lands server-side, pass `types` through so Pulse can return a
        //  mixed shelf with the kinds the caller asked for. For now every entry is wrapped as a
        //  TrackItem since the existing endpoint returns track-shaped objects.
        val pulseUrl: String = subsonicFallback.buildAuthenticatedUrl(
            pathAfterBase = "pulse/recentlyPlayed",
            extraQueryParameters = mapOf("count" to limit.toString()),
        )
        val request: Request = Request.Builder().url(pulseUrl).get().build()
        val responseBodyText: String = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("pulse/recentlyPlayed returned HTTP " + response.code)
                }
                val body: okhttp3.ResponseBody? = response.body
                if (body == null) {
                    throw IOException("pulse/recentlyPlayed response had no body")
                }
                body.string()
            }
        }
        val parsed: PulseRecentlyPlayedWire = jsonDecoder.decodeFromString(
            PulseRecentlyPlayedWire.serializer(),
            responseBodyText,
        )
        val collected: ArrayList<HomeItem> = ArrayList<HomeItem>(parsed.tracks.size)
        val trackCount: Int = parsed.tracks.size
        for (trackIndex in 0 until trackCount) {
            val raw: PulseRecentlyPlayedTrackWire = parsed.tracks[trackIndex]
            val translatedTrack: Track = translatePulseTrack(raw)
            collected.add(HomeItem.TrackItem(translatedTrack))
        }
        return collected
    }

    override suspend fun getPopularArtists(limit: Int): List<HomeItem> {
        val pulseUrl: String = subsonicFallback.buildAuthenticatedUrl(
            pathAfterBase = "pulse/popularArtists",
            extraQueryParameters = mapOf("count" to limit.toString()),
        )
        val request: Request = Request.Builder().url(pulseUrl).get().build()
        val responseBodyText: String = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("pulse/popularArtists returned HTTP " + response.code)
                }
                val body: okhttp3.ResponseBody? = response.body
                if (body == null) {
                    throw IOException("pulse/popularArtists response had no body")
                }
                body.string()
            }
        }
        val parsed: PulsePopularArtistsWire = jsonDecoder.decodeFromString(
            PulsePopularArtistsWire.serializer(),
            responseBodyText,
        )
        val collected: ArrayList<HomeItem> = ArrayList<HomeItem>(parsed.artists.size)
        val artistCount: Int = parsed.artists.size
        for (artistIndex in 0 until artistCount) {
            val raw: PulsePopularArtistWire = parsed.artists[artistIndex]
            val albumCountValue: Int
            if (raw.albumCount == null) {
                albumCountValue = 0
            } else {
                albumCountValue = raw.albumCount
            }
            val translatedArtist: Artist = Artist(
                artistId = raw.id,
                name = raw.name,
                albumCount = albumCountValue,
                coverArtId = raw.coverArt,
            )
            collected.add(HomeItem.ArtistItem(translatedArtist))
        }
        return collected
    }

    override suspend fun getTopPlaylists(limit: Int): List<HomeItem> {
        val pulseUrl: String = subsonicFallback.buildAuthenticatedUrl(
            pathAfterBase = "pulse/topPlaylists",
            extraQueryParameters = mapOf("count" to limit.toString()),
        )
        val request: Request = Request.Builder().url(pulseUrl).get().build()
        val responseBodyText: String = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("pulse/topPlaylists returned HTTP " + response.code)
                }
                val body: okhttp3.ResponseBody? = response.body
                if (body == null) {
                    throw IOException("pulse/topPlaylists response had no body")
                }
                body.string()
            }
        }
        val parsed: PulseTopPlaylistsWire = jsonDecoder.decodeFromString(
            PulseTopPlaylistsWire.serializer(),
            responseBodyText,
        )
        val collected: ArrayList<HomeItem> = ArrayList<HomeItem>(parsed.playlists.size)
        val playlistCount: Int = parsed.playlists.size
        for (playlistIndex in 0 until playlistCount) {
            val raw: PulseTopPlaylistWire = parsed.playlists[playlistIndex]
            val translatedPlaylist: Playlist = Playlist(
                playlistId = raw.id,
                name = raw.name,
                ownerUsername = null,
                comment = null,
                songCount = raw.songCount,
                durationSeconds = raw.duration,
                coverArtId = raw.coverArt,
                tracks = emptyList<Track>(),
            )
            collected.add(HomeItem.PlaylistItem(translatedPlaylist))
        }
        return collected
    }

    override suspend fun scrobble(trackId: String, atMillis: Long, submission: Boolean) {
        subsonicFallback.scrobble(trackId, atMillis, submission)
    }

    override suspend fun getCoverArtBytes(coverArtId: String, sizePx: Int): ByteArray {
        return subsonicFallback.getCoverArtBytes(coverArtId, sizePx)
    }

    override suspend fun openAudioStream(trackId: String): AudioStreamResponse {
        return subsonicFallback.openAudioStream(trackId)
    }

    private fun translatePulseTrack(raw: PulseRecentlyPlayedTrackWire): Track {
        return Track(
            trackId = raw.id,
            title = raw.title,
            artistName = raw.artist,
            artistId = raw.artistId,
            albumName = raw.album,
            albumId = raw.albumId,
            trackNumber = null,
            discNumber = null,
            year = null,
            genre = null,
            durationSeconds = raw.duration,
            sizeBytes = null,
            suffix = null,
            contentType = null,
            coverArtId = raw.coverArt,
        )
    }
}

// -- wire shapes (private to this protocol implementation) ---------------------------------------

@Serializable
private data class PulseRecentlyPlayedWire(
    val tracks: List<PulseRecentlyPlayedTrackWire> = emptyList(),
)

@Serializable
private data class PulseRecentlyPlayedTrackWire(
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
private data class PulsePopularArtistsWire(
    val artists: List<PulsePopularArtistWire> = emptyList(),
)

@Serializable
private data class PulsePopularArtistWire(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val score: Float? = null,
    val coverArt: String? = null,
)

@Serializable
private data class PulseTopPlaylistsWire(
    val playlists: List<PulseTopPlaylistWire> = emptyList(),
)

@Serializable
private data class PulseTopPlaylistWire(
    val id: String,
    val name: String,
    val songCount: Int? = null,
    val duration: Int? = null,
    val score: Float? = null,
    val lastPlayed: String? = null,
    val coverArt: String? = null,
)
