package com.therobm.thump.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * IProtocol over standard OpenSubsonic endpoints. No caching, no storage — purely an HTTP +
 * JSON translator. ThumpData owns the OkHttpClient; SubsonicProtocol borrows the reference.
 *
 * Authentication: token mode (md5(password + salt) + per-request salt) by default, with a
 * legacy hex-password fallback for old servers. Mode is selected by the caller via
 * `useTokenAuth` since the server config persists the choice next to the credentials.
 *
 * Step-2 status: `ping` and `probeForPulseSupport` are fully wired. The rest of the IProtocol
 * surface is stubbed with sensibly-shaped empty/default values per the skeleton scope of
 * Flatline #218; later steps fill them in as call-sites get ported.
 */
class SubsonicProtocol(
    private val serverUrl: String,
    private val username: String,
    private val password: String,
    private val useTokenAuth: Boolean,
    private val okHttpClient: OkHttpClient,
    private val jsonDecoder: Json,
) : IProtocol {

    override suspend fun ping(): ServerInfo {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint("rest/ping", emptyMap())
        return ServerInfo(
            protocolVersion = envelope.version,
            serverType = envelope.type,
            serverVersion = envelope.serverVersion,
            isOpenSubsonicServer = envelope.openSubsonic == true,
        )
    }

    /**
     * Probe `pulse/recentlyPlayed?count=1`. 200 means the server speaks Pulse extensions, 404
     * means standard Subsonic. Any other status is treated as "not Pulse" rather than throwing
     * so the caller can still proceed with the SubsonicProtocol path.
     */
    internal suspend fun probeForPulseSupport(): Boolean {
        val requestUrl: String = buildAuthenticatedUrl(
            pathAfterBase = "pulse/recentlyPlayed",
            extraQueryParameters = mapOf("count" to "1"),
        )
        val request: Request = Request.Builder().url(requestUrl).get().build()
        val httpStatusCode: Int = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                response.code
            }
        }
        if (httpStatusCode == 200) {
            return true
        }
        return false
    }

    /**
     * Build a fully-qualified, authenticated cover-art URL. Used by `getCoverArtBytes` and by
     * PulseProtocol when it needs the same URL pattern for delegated calls.
     */
    internal fun buildCoverArtUrl(coverArtId: String, sizePx: Int): String {
        return buildAuthenticatedUrl(
            pathAfterBase = "rest/getCoverArt",
            extraQueryParameters = mapOf("id" to coverArtId, "size" to sizePx.toString()),
        )
    }

    /**
     * Build a fully-qualified, authenticated stream URL. Used by `openAudioStream`.
     */
    internal fun buildStreamUrl(trackId: String): String {
        return buildAuthenticatedUrl(
            pathAfterBase = "rest/stream",
            extraQueryParameters = mapOf("id" to trackId),
        )
    }

    override suspend fun getAllArtists(): List<Artist> {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint("rest/getArtists", emptyMap())
        val artistsPayload: SubsonicArtistsPayload? = envelope.artists
        if (artistsPayload == null) {
            return emptyList<Artist>()
        }
        val collected: ArrayList<Artist> = ArrayList<Artist>()
        val indexCount: Int = artistsPayload.index.size
        for (indexIndex in 0 until indexCount) {
            val singleIndex: SubsonicArtistsIndex = artistsPayload.index[indexIndex]
            val artistsInIndex: Int = singleIndex.artist.size
            for (artistIndexWithinIndex in 0 until artistsInIndex) {
                val rawArtist: SubsonicLibraryArtist = singleIndex.artist[artistIndexWithinIndex]
                val albumCountValue: Int
                if (rawArtist.albumCount == null) {
                    albumCountValue = 0
                } else {
                    albumCountValue = rawArtist.albumCount
                }
                collected.add(
                    Artist(
                        artistId = rawArtist.id,
                        name = rawArtist.name,
                        albumCount = albumCountValue,
                        coverArtId = rawArtist.coverArt,
                    )
                )
            }
        }
        return collected
    }

    override suspend fun getArtist(artistId: String): Artist {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint(
            "rest/getArtist",
            mapOf("id" to artistId),
        )
        val artistPayload: SubsonicArtistDetailPayload? = envelope.artist
        if (artistPayload == null) {
            return Artist(
                artistId = artistId,
                name = "",
                albumCount = 0,
                coverArtId = null,
            )
        }
        val albumCountValue: Int
        if (artistPayload.albumCount == null) {
            albumCountValue = artistPayload.album.size
        } else {
            albumCountValue = artistPayload.albumCount
        }
        return Artist(
            artistId = artistPayload.id,
            name = artistPayload.name,
            albumCount = albumCountValue,
            coverArtId = artistPayload.coverArt,
        )
    }

    override suspend fun getArtistTracks(artistId: String): List<Track> {
        // Skeleton stub. Real fan-out (call getArtist, then getAlbum per album) lands when the
        // ArtistDetail screen is ported to ThumpData.
        return emptyList<Track>()
    }

    override suspend fun getAlbum(albumId: String): Album {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint(
            "rest/getAlbum",
            mapOf("id" to albumId),
        )
        val albumPayload: SubsonicAlbumDetailPayload? = envelope.album
        if (albumPayload == null) {
            return Album(
                albumId = albumId,
                name = "",
                artistName = null,
                artistId = null,
                year = null,
                genre = null,
                durationSeconds = null,
                songCount = null,
                coverArtId = null,
                tracks = emptyList<Track>(),
            )
        }
        val translatedTracks: ArrayList<Track> = ArrayList<Track>(albumPayload.song.size)
        val songCountForLoop: Int = albumPayload.song.size
        for (songIndex in 0 until songCountForLoop) {
            translatedTracks.add(translateSongDetail(albumPayload.song[songIndex]))
        }
        return Album(
            albumId = albumPayload.id,
            name = albumPayload.name,
            artistName = albumPayload.artist,
            artistId = albumPayload.artistId,
            year = albumPayload.year,
            genre = albumPayload.genre,
            durationSeconds = albumPayload.duration,
            songCount = albumPayload.songCount,
            coverArtId = albumPayload.coverArt,
            tracks = translatedTracks,
        )
    }

    override suspend fun getAllAlbums(sort: AlbumSort, limit: Int, offset: Int): List<Album> {
        val wireType: String = mapAlbumSortToWireType(sort)
        val envelope: SubsonicEnvelope = callSubsonicEndpoint(
            "rest/getAlbumList2",
            mapOf(
                "type" to wireType,
                "size" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )
        val payload: SubsonicAlbumList2Payload? = envelope.albumList2
        if (payload == null) {
            return emptyList<Album>()
        }
        val collected: ArrayList<Album> = ArrayList<Album>(payload.album.size)
        val albumCount: Int = payload.album.size
        for (albumIndex in 0 until albumCount) {
            val summary: SubsonicAlbumSummary = payload.album[albumIndex]
            collected.add(translateAlbumSummary(summary))
        }
        return collected
    }

    override suspend fun getGenres(): List<Genre> {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint("rest/getGenres", emptyMap())
        val payload: SubsonicGenresPayload? = envelope.genres
        if (payload == null) {
            return emptyList<Genre>()
        }
        val collected: ArrayList<Genre> = ArrayList<Genre>(payload.genre.size)
        val genreCount: Int = payload.genre.size
        for (genreIndex in 0 until genreCount) {
            val rawGenre: SubsonicGenre = payload.genre[genreIndex]
            collected.add(
                Genre(
                    name = rawGenre.value,
                    songCount = rawGenre.songCount,
                    albumCount = rawGenre.albumCount,
                )
            )
        }
        return collected
    }

    override suspend fun getTracksByGenre(genre: String, limit: Int, offset: Int): List<Track> {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint(
            "rest/getSongsByGenre",
            mapOf(
                "genre" to genre,
                "count" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )
        val payload: SubsonicSongsByGenrePayload? = envelope.songsByGenre
        if (payload == null) {
            return emptyList<Track>()
        }
        val collected: ArrayList<Track> = ArrayList<Track>(payload.song.size)
        val songCountForLoop: Int = payload.song.size
        for (songIndex in 0 until songCountForLoop) {
            collected.add(translateSongDetail(payload.song[songIndex]))
        }
        return collected
    }

    override suspend fun getAllPlaylists(): List<Playlist> {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint("rest/getPlaylists", emptyMap())
        val payload: SubsonicPlaylistsPayload? = envelope.playlists
        if (payload == null) {
            return emptyList<Playlist>()
        }
        val collected: ArrayList<Playlist> = ArrayList<Playlist>(payload.playlist.size)
        val playlistCount: Int = payload.playlist.size
        for (playlistIndex in 0 until playlistCount) {
            val summary: SubsonicPlaylistSummary = payload.playlist[playlistIndex]
            collected.add(translatePlaylistSummary(summary))
        }
        return collected
    }

    override suspend fun getPlaylist(playlistId: String): Playlist {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint(
            "rest/getPlaylist",
            mapOf("id" to playlistId),
        )
        val payload: SubsonicPlaylistDetailPayload? = envelope.playlist
        if (payload == null) {
            return Playlist(
                playlistId = playlistId,
                name = "",
                ownerUsername = null,
                comment = null,
                songCount = null,
                durationSeconds = null,
                coverArtId = null,
                tracks = emptyList<Track>(),
            )
        }
        val translatedTracks: ArrayList<Track> = ArrayList<Track>(payload.entry.size)
        val entryCount: Int = payload.entry.size
        for (entryIndex in 0 until entryCount) {
            translatedTracks.add(translateSongDetail(payload.entry[entryIndex]))
        }
        return Playlist(
            playlistId = payload.id,
            name = payload.name,
            ownerUsername = payload.owner,
            comment = payload.comment,
            songCount = payload.songCount,
            durationSeconds = payload.duration,
            coverArtId = payload.coverArt,
            tracks = translatedTracks,
        )
    }

    override suspend fun createPlaylist(name: String, trackIds: List<String>): Playlist {
        // Skeleton stub: the Subsonic createPlaylist endpoint returns the new playlist body, but
        // the call-site that needs round-tripping is not yet ported. Returning an empty record
        // so the IProtocol contract is preserved.
        return Playlist(
            playlistId = "",
            name = name,
            ownerUsername = null,
            comment = null,
            songCount = trackIds.size,
            durationSeconds = null,
            coverArtId = null,
            tracks = emptyList<Track>(),
        )
    }

    override suspend fun updatePlaylist(playlistId: String, edits: PlaylistEdits): Playlist {
        // Skeleton stub. Real implementation calls /rest/updatePlaylist with name/comment +
        // songIdToAdd/songIndexToRemove repeats; lands when the playlist-detail screen is ported.
        return Playlist(
            playlistId = playlistId,
            name = "",
            ownerUsername = null,
            comment = null,
            songCount = null,
            durationSeconds = null,
            coverArtId = null,
            tracks = emptyList<Track>(),
        )
    }

    override suspend fun deletePlaylist(playlistId: String) {
        // Skeleton stub. Real implementation calls /rest/deletePlaylist?id=. No return value.
    }

    override suspend fun search(query: String): SearchResult {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint(
            "rest/search3",
            mapOf(
                "query" to query,
                "artistCount" to "20",
                "albumCount" to "20",
                "songCount" to "50",
            ),
        )
        val payload: SubsonicSearchResult3Payload? = envelope.searchResult3
        if (payload == null) {
            return SearchResult(
                artists = emptyList<Artist>(),
                albums = emptyList<Album>(),
                tracks = emptyList<Track>(),
            )
        }
        val translatedArtists: ArrayList<Artist> = ArrayList<Artist>(payload.artist.size)
        val artistCountInPayload: Int = payload.artist.size
        for (artistIndex in 0 until artistCountInPayload) {
            translatedArtists.add(translateLibraryArtist(payload.artist[artistIndex]))
        }
        val translatedAlbums: ArrayList<Album> = ArrayList<Album>(payload.album.size)
        val albumCountInPayload: Int = payload.album.size
        for (albumIndex in 0 until albumCountInPayload) {
            translatedAlbums.add(translateAlbumSummary(payload.album[albumIndex]))
        }
        val translatedTracks: ArrayList<Track> = ArrayList<Track>(payload.song.size)
        val songCountInPayload: Int = payload.song.size
        for (songIndex in 0 until songCountInPayload) {
            translatedTracks.add(translateSongDetail(payload.song[songIndex]))
        }
        return SearchResult(
            artists = translatedArtists,
            albums = translatedAlbums,
            tracks = translatedTracks,
        )
    }

    override suspend fun getStarred(): StarredCollection {
        val envelope: SubsonicEnvelope = callSubsonicEndpoint("rest/getStarred2", emptyMap())
        val payload: SubsonicStarred2Payload? = envelope.starred2
        if (payload == null) {
            return StarredCollection(
                tracks = emptyList<Track>(),
                albums = emptyList<Album>(),
                artists = emptyList<Artist>(),
            )
        }
        val translatedTracks: ArrayList<Track> = ArrayList<Track>(payload.song.size)
        val songCount: Int = payload.song.size
        for (songIndex in 0 until songCount) {
            translatedTracks.add(translateSongDetail(payload.song[songIndex]))
        }
        val translatedAlbums: ArrayList<Album> = ArrayList<Album>(payload.album.size)
        val albumCount: Int = payload.album.size
        for (albumIndex in 0 until albumCount) {
            translatedAlbums.add(translateAlbumSummary(payload.album[albumIndex]))
        }
        val translatedArtists: ArrayList<Artist> = ArrayList<Artist>(payload.artist.size)
        val starredArtistCount: Int = payload.artist.size
        for (artistIndex in 0 until starredArtistCount) {
            val rawArtist: SubsonicStarredArtist = payload.artist[artistIndex]
            val albumCountValue: Int
            if (rawArtist.albumCount == null) {
                albumCountValue = 0
            } else {
                albumCountValue = rawArtist.albumCount
            }
            translatedArtists.add(
                Artist(
                    artistId = rawArtist.id,
                    name = rawArtist.name,
                    albumCount = albumCountValue,
                    coverArtId = rawArtist.coverArt,
                )
            )
        }
        return StarredCollection(
            tracks = translatedTracks,
            albums = translatedAlbums,
            artists = translatedArtists,
        )
    }

    override suspend fun star(kind: StarKind, id: String) {
        val parameterName: String = starParameterNameFor(kind)
        callSubsonicEndpoint("rest/star", mapOf(parameterName to id))
    }

    override suspend fun unstar(kind: StarKind, id: String) {
        val parameterName: String = starParameterNameFor(kind)
        callSubsonicEndpoint("rest/unstar", mapOf(parameterName to id))
    }

    override suspend fun setRating(kind: StarKind, id: String, rating: Int) {
        callSubsonicEndpoint(
            "rest/setRating",
            mapOf("id" to id, "rating" to rating.toString()),
        )
    }

    override suspend fun getRecentlyPlayed(limit: Int, types: Set<HomeItemKind>): List<HomeItem> {
        // Subsonic fallback per Projects/Thump.md: getAlbumList2(type=recent) -> AlbumItem shelf.
        // The `types` filter is ignored here; the screen renders whatever's in the shelf.
        val envelope: SubsonicEnvelope = callSubsonicEndpoint(
            "rest/getAlbumList2",
            mapOf("type" to "recent", "size" to limit.toString()),
        )
        val payload: SubsonicAlbumList2Payload? = envelope.albumList2
        if (payload == null) {
            return emptyList<HomeItem>()
        }
        val collected: ArrayList<HomeItem> = ArrayList<HomeItem>(payload.album.size)
        val albumCount: Int = payload.album.size
        for (albumIndex in 0 until albumCount) {
            val translated: Album = translateAlbumSummary(payload.album[albumIndex])
            collected.add(HomeItem.AlbumItem(translated))
        }
        return collected
    }

    override suspend fun getPopularArtists(limit: Int): List<HomeItem> {
        // Subsonic fallback per Projects/Thump.md: getAlbumList2(type=frequent), surfaced as
        // AlbumItem entries. Standard Subsonic has no native "popular artists" endpoint.
        val envelope: SubsonicEnvelope = callSubsonicEndpoint(
            "rest/getAlbumList2",
            mapOf("type" to "frequent", "size" to limit.toString()),
        )
        val payload: SubsonicAlbumList2Payload? = envelope.albumList2
        if (payload == null) {
            return emptyList<HomeItem>()
        }
        val collected: ArrayList<HomeItem> = ArrayList<HomeItem>(payload.album.size)
        val albumCount: Int = payload.album.size
        for (albumIndex in 0 until albumCount) {
            collected.add(HomeItem.AlbumItem(translateAlbumSummary(payload.album[albumIndex])))
        }
        return collected
    }

    override suspend fun getTopPlaylists(limit: Int): List<HomeItem> {
        // Subsonic fallback per Projects/Thump.md: getPlaylists, truncated client-side. No
        // server-side ranking on standard Subsonic, so order is whatever the server returned.
        val playlists: List<Playlist> = getAllPlaylists()
        val collected: ArrayList<HomeItem> = ArrayList<HomeItem>()
        val takeCount: Int
        if (playlists.size < limit) {
            takeCount = playlists.size
        } else {
            takeCount = limit
        }
        for (playlistIndex in 0 until takeCount) {
            collected.add(HomeItem.PlaylistItem(playlists[playlistIndex]))
        }
        return collected
    }

    override suspend fun scrobble(trackId: String, atMillis: Long, submission: Boolean) {
        callSubsonicEndpoint(
            "rest/scrobble",
            mapOf(
                "id" to trackId,
                "time" to atMillis.toString(),
                "submission" to submission.toString(),
            ),
        )
    }

    override suspend fun getCoverArtBytes(coverArtId: String, sizePx: Int): ByteArray {
        val requestUrl: String = buildCoverArtUrl(coverArtId, sizePx)
        val request: Request = Request.Builder().url(requestUrl).get().build()
        val bodyBytes: ByteArray = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        "Cover-art fetch for id=" + coverArtId
                            + " size=" + sizePx + " returned HTTP " + response.code
                    )
                }
                val body: okhttp3.ResponseBody? = response.body
                if (body == null) {
                    throw IOException("Cover-art response had no body for id=" + coverArtId)
                }
                body.bytes()
            }
        }
        return bodyBytes
    }

    override suspend fun openAudioStream(trackId: String): AudioStreamResponse {
        val requestUrl: String = buildStreamUrl(trackId)
        val request: Request = Request.Builder().url(requestUrl).get().build()
        val response: okhttp3.Response = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
        }
        if (!response.isSuccessful) {
            response.close()
            throw IOException(
                "Audio stream fetch for id=" + trackId + " returned HTTP " + response.code
            )
        }
        val responseBody: okhttp3.ResponseBody? = response.body
        if (responseBody == null) {
            response.close()
            throw IOException("Audio stream response had no body for id=" + trackId)
        }
        val contentLength: Long = responseBody.contentLength()
        val contentTypeString: String?
        val rawContentType: okhttp3.MediaType? = responseBody.contentType()
        if (rawContentType == null) {
            contentTypeString = null
        } else {
            contentTypeString = rawContentType.toString()
        }
        return AudioStreamResponse(
            inputStream = responseBody.byteStream(),
            totalBytesAvailable = contentLength,
            contentType = contentTypeString,
        )
    }

    // -- helpers ---------------------------------------------------------------------------------

    private fun translateSongDetail(song: SubsonicSongDetail): Track {
        return Track(
            trackId = song.id,
            title = song.title,
            artistName = song.artist,
            artistId = song.artistId,
            albumName = song.album,
            albumId = song.albumId,
            trackNumber = song.track,
            discNumber = song.discNumber,
            year = song.year,
            genre = song.genre,
            durationSeconds = song.duration,
            sizeBytes = song.size,
            suffix = song.suffix,
            contentType = song.contentType,
            coverArtId = song.coverArt,
        )
    }

    private fun translateAlbumSummary(summary: SubsonicAlbumSummary): Album {
        return Album(
            albumId = summary.id,
            name = summary.name,
            artistName = summary.artist,
            artistId = summary.artistId,
            year = summary.year,
            genre = summary.genre,
            durationSeconds = summary.duration,
            songCount = summary.songCount,
            coverArtId = summary.coverArt,
            tracks = emptyList<Track>(),
        )
    }

    private fun translatePlaylistSummary(summary: SubsonicPlaylistSummary): Playlist {
        return Playlist(
            playlistId = summary.id,
            name = summary.name,
            ownerUsername = summary.owner,
            comment = summary.comment,
            songCount = summary.songCount,
            durationSeconds = summary.duration,
            coverArtId = summary.coverArt,
            tracks = emptyList<Track>(),
        )
    }

    private fun translateLibraryArtist(raw: SubsonicLibraryArtist): Artist {
        val albumCountValue: Int
        if (raw.albumCount == null) {
            albumCountValue = 0
        } else {
            albumCountValue = raw.albumCount
        }
        return Artist(
            artistId = raw.id,
            name = raw.name,
            albumCount = albumCountValue,
            coverArtId = raw.coverArt,
        )
    }

    private fun mapAlbumSortToWireType(sort: AlbumSort): String {
        when (sort) {
            AlbumSort.AlphabeticalByName -> {
                return "alphabeticalByName"
            }
            AlbumSort.AlphabeticalByArtist -> {
                return "alphabeticalByArtist"
            }
            AlbumSort.Newest -> {
                return "newest"
            }
            AlbumSort.Recent -> {
                return "recent"
            }
            AlbumSort.Frequent -> {
                return "frequent"
            }
            AlbumSort.Random -> {
                return "random"
            }
        }
    }

    private fun starParameterNameFor(kind: StarKind): String {
        when (kind) {
            StarKind.Track -> {
                return "id"
            }
            StarKind.Album -> {
                return "albumId"
            }
            StarKind.Artist -> {
                return "artistId"
            }
        }
    }

    private suspend fun callSubsonicEndpoint(
        pathAfterBase: String,
        extraQueryParameters: Map<String, String>,
    ): SubsonicEnvelope {
        val requestUrl: String = buildAuthenticatedUrl(pathAfterBase, extraQueryParameters)
        val request: Request = Request.Builder().url(requestUrl).get().build()
        val responseBodyText: String = withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute().use { response ->
                val body: okhttp3.ResponseBody? = response.body
                if (body == null) {
                    throw IOException(
                        "Subsonic response had no body (path=" + pathAfterBase
                            + ", http=" + response.code + ")"
                    )
                }
                body.string()
            }
        }
        val root: SubsonicResponseRoot = jsonDecoder.decodeFromString(
            SubsonicResponseRoot.serializer(),
            responseBodyText,
        )
        val envelope: SubsonicEnvelope = root.subsonicResponse
        if (envelope.status != "ok") {
            val errorPayload: SubsonicErrorPayload? = envelope.error
            val errorMessage: String
            if (errorPayload == null) {
                errorMessage = "Subsonic returned status=" + envelope.status
            } else {
                errorMessage = "Subsonic error " + errorPayload.code + ": " + errorPayload.message
            }
            throw IOException(errorMessage)
        }
        return envelope
    }

    internal fun buildAuthenticatedUrl(
        pathAfterBase: String,
        extraQueryParameters: Map<String, String>,
    ): String {
        val trimmed: String = serverUrl.trimEnd('/')
        val lowerCased: String = trimmed.lowercase()
        val base: String
        if (lowerCased.startsWith("http://") || lowerCased.startsWith("https://")) {
            base = trimmed
        } else {
            base = "http://" + trimmed
        }
        val builder: HttpUrl.Builder = (base + "/" + pathAfterBase).toHttpUrl().newBuilder()
        builder.addQueryParameter("u", username)
        builder.addQueryParameter("v", SUBSONIC_PROTOCOL_VERSION)
        builder.addQueryParameter("c", SUBSONIC_CLIENT_NAME)
        builder.addQueryParameter("f", "json")
        for (extraEntry in extraQueryParameters.entries) {
            builder.addQueryParameter(extraEntry.key, extraEntry.value)
        }
        if (useTokenAuth) {
            val salt: String = generateAuthSalt()
            val token: String = computeMd5Hex(password + salt)
            builder.addQueryParameter("t", token)
            builder.addQueryParameter("s", salt)
        } else {
            builder.addQueryParameter("p", "enc:" + encodePasswordAsHex(password))
        }
        return builder.build().toString()
    }

    private fun generateAuthSalt(): String {
        val saltCharacters: String = "abcdefghijklmnopqrstuvwxyz0123456789"
        val saltCharacterCount: Int = saltCharacters.length
        val saltLength: Int = 12
        val result: StringBuilder = StringBuilder(saltLength)
        for (saltCharacterIndex in 0 until saltLength) {
            val pickedIndex: Int = saltRandom.nextInt(saltCharacterCount)
            result.append(saltCharacters[pickedIndex])
        }
        return result.toString()
    }

    private fun computeMd5Hex(input: String): String {
        val digestBytes: ByteArray = MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        return bytesToLowerHex(digestBytes)
    }

    private fun encodePasswordAsHex(password: String): String {
        return bytesToLowerHex(password.toByteArray(Charsets.UTF_8))
    }

    private fun bytesToLowerHex(source: ByteArray): String {
        val hexCharacters: String = "0123456789abcdef"
        val sourceLength: Int = source.size
        val result: StringBuilder = StringBuilder(sourceLength * 2)
        for (sourceByteIndex in 0 until sourceLength) {
            val currentByte: Int = source[sourceByteIndex].toInt() and 0xff
            result.append(hexCharacters[currentByte ushr 4])
            result.append(hexCharacters[currentByte and 0x0f])
        }
        return result.toString()
    }

    companion object {
        const val SUBSONIC_PROTOCOL_VERSION: String = "1.16.1"
        const val SUBSONIC_CLIENT_NAME: String = "Thump"
        private val saltRandom: SecureRandom = SecureRandom()
    }
}

// -- wire shapes (private to this protocol implementation) ---------------------------------------

/**
 * The standard Subsonic JSON wrapper. The hyphen in the wire field name `subsonic-response`
 * forces a SerialName here; everywhere else field names match the wire exactly.
 */
@Serializable
private data class SubsonicResponseRoot(
    @SerialName("subsonic-response")
    val subsonicResponse: SubsonicEnvelope,
)

@Serializable
private data class SubsonicEnvelope(
    val status: String,
    val version: String,
    val type: String? = null,
    val serverVersion: String? = null,
    val openSubsonic: Boolean? = null,
    val error: SubsonicErrorPayload? = null,
    val albumList2: SubsonicAlbumList2Payload? = null,
    val playlists: SubsonicPlaylistsPayload? = null,
    val starred2: SubsonicStarred2Payload? = null,
    val album: SubsonicAlbumDetailPayload? = null,
    val playlist: SubsonicPlaylistDetailPayload? = null,
    val artist: SubsonicArtistDetailPayload? = null,
    val artists: SubsonicArtistsPayload? = null,
    val genres: SubsonicGenresPayload? = null,
    val songsByGenre: SubsonicSongsByGenrePayload? = null,
    val searchResult3: SubsonicSearchResult3Payload? = null,
)

@Serializable
private data class SubsonicErrorPayload(
    val code: Int,
    val message: String,
)

@Serializable
private data class SubsonicAlbumList2Payload(
    val album: List<SubsonicAlbumSummary> = emptyList(),
)

@Serializable
private data class SubsonicAlbumSummary(
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
private data class SubsonicPlaylistsPayload(
    val playlist: List<SubsonicPlaylistSummary> = emptyList(),
)

@Serializable
private data class SubsonicPlaylistSummary(
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
private data class SubsonicStarred2Payload(
    val song: List<SubsonicSongDetail> = emptyList(),
    val album: List<SubsonicAlbumSummary> = emptyList(),
    val artist: List<SubsonicStarredArtist> = emptyList(),
)

@Serializable
private data class SubsonicStarredArtist(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
)

@Serializable
private data class SubsonicAlbumDetailPayload(
    val id: String,
    val name: String,
    val artist: String? = null,
    val artistId: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    val song: List<SubsonicSongDetail> = emptyList(),
)

@Serializable
private data class SubsonicPlaylistDetailPayload(
    val id: String,
    val name: String,
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val entry: List<SubsonicSongDetail> = emptyList(),
)

@Serializable
private data class SubsonicArtistDetailPayload(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val album: List<SubsonicArtistAlbum> = emptyList(),
)

@Serializable
private data class SubsonicArtistAlbum(
    val id: String,
    val name: String,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null,
    val year: Int? = null,
)

@Serializable
private data class SubsonicArtistsPayload(
    val ignoredArticles: String? = null,
    val index: List<SubsonicArtistsIndex> = emptyList(),
)

@Serializable
private data class SubsonicArtistsIndex(
    val name: String,
    val artist: List<SubsonicLibraryArtist> = emptyList(),
)

@Serializable
private data class SubsonicLibraryArtist(
    val id: String,
    val name: String,
    val albumCount: Int? = null,
    val coverArt: String? = null,
)

@Serializable
private data class SubsonicGenresPayload(
    val genre: List<SubsonicGenre> = emptyList(),
)

@Serializable
private data class SubsonicGenre(
    val value: String,
    val songCount: Int? = null,
    val albumCount: Int? = null,
)

@Serializable
private data class SubsonicSongsByGenrePayload(
    val song: List<SubsonicSongDetail> = emptyList(),
)

@Serializable
private data class SubsonicSearchResult3Payload(
    val artist: List<SubsonicLibraryArtist> = emptyList(),
    val album: List<SubsonicAlbumSummary> = emptyList(),
    val song: List<SubsonicSongDetail> = emptyList(),
)

@Serializable
private data class SubsonicSongDetail(
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
    val year: Int? = null,
    val genre: String? = null,
    val size: Long? = null,
    val suffix: String? = null,
    val contentType: String? = null,
)
