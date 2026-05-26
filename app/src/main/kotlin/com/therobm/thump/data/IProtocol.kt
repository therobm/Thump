package com.therobm.thump.data

/**
 * Every operation ThumpData might ask of a remote music server. Implementations translate the
 * server's wire shape (Subsonic JSON, Pulse JSON, etc.) into Thump-shaped Kotlin domain types
 * and translate domain calls back to the right wire requests.
 *
 * IProtocol implementations are stateless beyond credentials. They do not cache, do not write
 * to disk, and do not branch on protocol availability — picking the right protocol is
 * ThumpData's job (via the Pulse-detection probe at connect time).
 *
 * Every method is a suspending function and must run on `Dispatchers.IO` since the body makes
 * HTTP calls. The internal helpers in each implementation are responsible for the dispatcher
 * switch; callers (ThumpData) do not need to wrap.
 */
interface IProtocol {

    suspend fun ping(): ServerInfo

    suspend fun getAllArtists(): List<Artist>

    suspend fun getArtist(artistId: String): Artist

    suspend fun getArtistTracks(artistId: String): List<Track>

    suspend fun getAlbum(albumId: String): Album

    suspend fun getAllAlbums(sort: AlbumSort, limit: Int, offset: Int): List<Album>

    suspend fun getGenres(): List<Genre>

    suspend fun getTracksByGenre(genre: String, limit: Int, offset: Int): List<Track>

    suspend fun getAllPlaylists(): List<Playlist>

    suspend fun getPlaylist(playlistId: String): Playlist

    suspend fun createPlaylist(name: String, trackIds: List<String>): Playlist

    suspend fun updatePlaylist(playlistId: String, edits: PlaylistEdits): Playlist

    suspend fun deletePlaylist(playlistId: String)

    suspend fun search(query: String): SearchResult

    suspend fun getStarred(): StarredCollection

    suspend fun star(kind: StarKind, id: String)

    suspend fun unstar(kind: StarKind, id: String)

    suspend fun setRating(kind: StarKind, id: String, rating: Int)

    suspend fun getRecentlyPlayed(limit: Int, types: Set<HomeItemKind>): List<HomeItem>

    suspend fun getPopularArtists(limit: Int): List<HomeItem>

    suspend fun getTopPlaylists(limit: Int): List<HomeItem>

    suspend fun scrobble(trackId: String, atMillis: Long, submission: Boolean)

    /**
     * Fetch the bytes of a cover-art image at the requested size. ThumpData uses the result to
     * fill the on-disk blob store and (for the in-app path) to decode a Bitmap.
     */
    suspend fun getCoverArtBytes(coverArtId: String, sizePx: Int): ByteArray

    /**
     * Open an HTTP stream for a track's audio bytes. Called by ThumpData's `open(DataSpec)` on
     * a cache miss; the returned stream is read synchronously by ExoPlayer's `read` and closed
     * via the DataSource's `close`.
     */
    suspend fun openAudioStream(trackId: String): AudioStreamResponse
}
