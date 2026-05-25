package com.therobm.thump.subsonic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A thin client over the OpenSubsonic JSON API.
 *
 * The client is the layer boundary between the rest of the app and the HTTP/JSON machinery, so
 * it constructs requests, runs them on a background dispatcher, and decodes the response. The
 * rest of the app sees suspend functions returning a SubsonicResult and never touches OkHttp or
 * the response envelope directly.
 *
 * One client per server connection. Construct a new one if the credentials or server URL change.
 */
class SubsonicClient(
    private val okHttpClient: OkHttpClient,
    private val jsonDecoder: Json,
    private val credentials: SubsonicCredentials,
    private val authMode: SubsonicAuthMode,
) {

    /**
     * Calls /rest/ping. A success result confirms the credentials work and tells the caller what
     * protocol version and server brand the server reports.
     */
    suspend fun ping(): SubsonicResult<SubsonicPingResult> {
        return callAndDecodeEnvelope("rest/ping", emptyMap()) { envelope: SubsonicResponseEnvelope ->
            SubsonicPingResult(
                protocolVersion = envelope.version,
                serverType = envelope.type,
                serverVersion = envelope.serverVersion,
                isOpenSubsonicServer = envelope.openSubsonic == true,
            )
        }
    }

    /**
     * Calls /rest/pulse/recentlyPlayed?count=1 and turns the HTTP status into a boolean.
     *
     * Per the Pulse extension spec, HTTP 200 means the server implements the Pulse home-screen
     * endpoints and HTTP 404 means it is a standard OpenSubsonic server with no Pulse layer. Any
     * other status is reported as a server error so callers can decide whether to retry or treat
     * the server as standard.
     */
    suspend fun probePulseExtensions(): SubsonicResult<Boolean> {
        val request: Request
        try {
            val requestUrl: String = buildAuthenticatedUrl(
                pathAfterBase = "pulse/recentlyPlayed",
                extraQueryParameters = mapOf("count" to "1"),
            )
            request = Request.Builder().url(requestUrl).get().build()
        } catch (urlBuildFailure: Exception) {
            return SubsonicResult.TransportError(urlBuildFailure)
        }

        val httpStatusCode: Int
        try {
            httpStatusCode = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    response.code
                }
            }
        } catch (transportFailure: Exception) {
            return SubsonicResult.TransportError(transportFailure)
        }

        if (httpStatusCode == 200) {
            return SubsonicResult.Ok(true)
        }
        if (httpStatusCode == 404) {
            return SubsonicResult.Ok(false)
        }
        return SubsonicResult.ServerError(httpStatusCode, "Unexpected HTTP status from Pulse probe")
    }

    /**
     * Calls /rest/getAlbumList2 and returns the list of album summaries.
     *
     * The `type` parameter is one of the standard Subsonic values: "newest", "recent",
     * "frequent", "alphabeticalByName", and so on. `size` caps the result count (Subsonic
     * default is 10, max 500).
     */
    suspend fun getAlbumList2(type: String, size: Int): SubsonicResult<List<StandardAlbumSummary>> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/getAlbumList2",
            extraQueryParameters = mapOf("type" to type, "size" to size.toString()),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.albumList2
            if (payload == null) {
                emptyList<StandardAlbumSummary>()
            } else {
                payload.album
            }
        }
    }

    /**
     * Calls /rest/getPlaylists and returns the playlist summaries for the authenticated user.
     */
    suspend fun getPlaylists(): SubsonicResult<List<StandardPlaylistSummary>> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/getPlaylists",
            extraQueryParameters = emptyMap(),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.playlists
            if (payload == null) {
                emptyList<StandardPlaylistSummary>()
            } else {
                payload.playlist
            }
        }
    }

    /**
     * Calls /rest/getStarred2 and returns the user's starred songs, albums, and artists.
     */
    suspend fun getStarred2(): SubsonicResult<StandardStarred2Payload> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/getStarred2",
            extraQueryParameters = emptyMap(),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.starred2
            if (payload == null) {
                StandardStarred2Payload()
            } else {
                payload
            }
        }
    }

    /**
     * Calls /rest/getAlbum and returns the album with its tracks.
     */
    suspend fun getAlbum(albumId: String): SubsonicResult<StandardAlbumDetailPayload> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/getAlbum",
            extraQueryParameters = mapOf("id" to albumId),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.album
            if (payload == null) {
                StandardAlbumDetailPayload(id = albumId, name = "")
            } else {
                payload
            }
        }
    }

    /**
     * Calls /rest/getPlaylist and returns the playlist with its entries.
     */
    suspend fun getPlaylist(playlistId: String): SubsonicResult<StandardPlaylistDetailPayload> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/getPlaylist",
            extraQueryParameters = mapOf("id" to playlistId),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.playlist
            if (payload == null) {
                StandardPlaylistDetailPayload(id = playlistId, name = "")
            } else {
                payload
            }
        }
    }

    /**
     * Calls /rest/getArtists and returns the alphabetically-indexed list of artists.
     */
    suspend fun getArtists(): SubsonicResult<StandardArtistsPayload> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/getArtists",
            extraQueryParameters = emptyMap(),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.artists
            if (payload == null) {
                StandardArtistsPayload()
            } else {
                payload
            }
        }
    }

    /**
     * Calls /rest/search3 and returns the merged artist/album/song result lists for a query.
     *
     * The three count parameters cap how many of each type the server returns; passing 0 for
     * any category disables it. Empty lists come back when nothing matches in a given category.
     */
    suspend fun search3(
        query: String,
        artistCount: Int,
        albumCount: Int,
        songCount: Int,
    ): SubsonicResult<StandardSearchResult3Payload> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/search3",
            extraQueryParameters = mapOf(
                "query" to query,
                "artistCount" to artistCount.toString(),
                "albumCount" to albumCount.toString(),
                "songCount" to songCount.toString(),
            ),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.searchResult3
            if (payload == null) {
                StandardSearchResult3Payload()
            } else {
                payload
            }
        }
    }

    /**
     * Calls /rest/getSongsByGenre and returns up to `count` songs (after `offset`) tagged with
     * the given genre. Used by the Genre detail screen and by the Library genre row's composite
     * art (which only needs the first few cover IDs).
     */
    suspend fun getSongsByGenre(
        genreName: String,
        count: Int,
        offset: Int,
    ): SubsonicResult<List<StandardSongDetail>> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/getSongsByGenre",
            extraQueryParameters = mapOf(
                "genre" to genreName,
                "count" to count.toString(),
                "offset" to offset.toString(),
            ),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.songsByGenre
            if (payload == null) {
                emptyList<StandardSongDetail>()
            } else {
                payload.song
            }
        }
    }

    /**
     * Calls /rest/getGenres and returns the genre list with per-genre song/album counts.
     */
    suspend fun getGenres(): SubsonicResult<List<StandardGenre>> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/getGenres",
            extraQueryParameters = emptyMap(),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.genres
            if (payload == null) {
                emptyList<StandardGenre>()
            } else {
                payload.genre
            }
        }
    }

    /**
     * Calls /rest/getArtist and returns the artist with their albums.
     */
    suspend fun getArtist(artistId: String): SubsonicResult<StandardArtistDetailPayload> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/getArtist",
            extraQueryParameters = mapOf("id" to artistId),
        ) { envelope: SubsonicResponseEnvelope ->
            val payload = envelope.artist
            if (payload == null) {
                StandardArtistDetailPayload(id = artistId, name = "")
            } else {
                payload
            }
        }
    }

    /**
     * Calls the optional Pulse pulse/recentlyPlayed endpoint. Only valid when the server has
     * already been detected as a Pulse server via probePulseExtensions().
     */
    suspend fun getPulseRecentlyPlayed(count: Int): SubsonicResult<List<PulseRecentlyPlayedTrack>> {
        return callAndDecodeRaw(
            pathAfterBase = "pulse/recentlyPlayed",
            extraQueryParameters = mapOf("count" to count.toString()),
            payloadDeserializer = PulseRecentlyPlayedResponse.serializer(),
        ) { response: PulseRecentlyPlayedResponse ->
            response.tracks
        }
    }

    /**
     * Calls the optional Pulse pulse/artistTracks endpoint, returning every track for an artist
     * in (album-index, track-number) order. Only valid when the server has already been detected
     * as a Pulse server. Standard OpenSubsonic clients fan out getAlbum per album instead.
     */
    suspend fun getPulseArtistTracks(artistId: String): SubsonicResult<List<PulseRecentlyPlayedTrack>> {
        return callAndDecodeRaw(
            pathAfterBase = "pulse/artistTracks",
            extraQueryParameters = mapOf("id" to artistId),
            payloadDeserializer = PulseArtistTracksResponse.serializer(),
        ) { response: PulseArtistTracksResponse ->
            response.tracks
        }
    }

    /**
     * Calls the optional Pulse pulse/popularArtists endpoint. Only valid when the server has
     * already been detected as a Pulse server.
     */
    suspend fun getPulsePopularArtists(count: Int): SubsonicResult<List<PulsePopularArtist>> {
        return callAndDecodeRaw(
            pathAfterBase = "pulse/popularArtists",
            extraQueryParameters = mapOf("count" to count.toString()),
            payloadDeserializer = PulsePopularArtistsResponse.serializer(),
        ) { response: PulsePopularArtistsResponse ->
            response.artists
        }
    }

    /**
     * Calls the optional Pulse pulse/recentPlaylists endpoint. Same per-item shape as
     * pulse/topPlaylists but sorted by per-user lastPlayed (most recent first), with never-
     * played playlists falling to the back. Only valid when the server has already been
     * detected as a Pulse server.
     */
    suspend fun getPulseRecentPlaylists(count: Int): SubsonicResult<List<PulseTopPlaylist>> {
        return callAndDecodeRaw(
            pathAfterBase = "pulse/recentPlaylists",
            extraQueryParameters = mapOf("count" to count.toString()),
            payloadDeserializer = PulseRecentPlaylistsResponse.serializer(),
        ) { response: PulseRecentPlaylistsResponse ->
            response.playlists
        }
    }

    /**
     * Calls the optional Pulse pulse/topPlaylists endpoint. Only valid when the server has
     * already been detected as a Pulse server.
     */
    suspend fun getPulseTopPlaylists(count: Int): SubsonicResult<List<PulseTopPlaylist>> {
        return callAndDecodeRaw(
            pathAfterBase = "pulse/topPlaylists",
            extraQueryParameters = mapOf("count" to count.toString()),
            payloadDeserializer = PulseTopPlaylistsResponse.serializer(),
        ) { response: PulseTopPlaylistsResponse ->
            response.playlists
        }
    }

    /**
     * Calls /rest/scrobble. `submission=false` is the now-playing notification (sent when a
     * track starts); `submission=true` is the play-count submission (sent at 50% played or
     * 4 minutes, whichever first, per Subsonic convention).
     */
    suspend fun scrobble(trackId: String, submission: Boolean): SubsonicResult<Unit> {
        return callAndDecodeEnvelope(
            pathAfterBase = "rest/scrobble",
            extraQueryParameters = mapOf("id" to trackId, "submission" to submission.toString()),
        ) { _ ->
            Unit
        }
    }

    /**
     * Build the authenticated URL for a /rest/stream request.
     *
     * Returned as a plain string so an audio player (Media3 ExoPlayer) can fetch it directly.
     * The server picks the format and bitrate based on its own defaults; future revisions may
     * add maxBitRate or format hints once the Settings screen exposes them.
     */
    fun buildStreamUrl(trackId: String): String {
        return buildAuthenticatedUrl(
            pathAfterBase = "rest/stream",
            extraQueryParameters = mapOf("id" to trackId),
        )
    }

    /**
     * Build the authenticated URL for a /rest/getCoverArt request.
     *
     * Returned as a plain string so an image loader (Coil) can fetch it directly. The size hint
     * lets the server scale the art rather than always sending originals.
     */
    fun buildCoverArtUrl(coverArtId: String, size: Int): String {
        return buildAuthenticatedUrl(
            pathAfterBase = "rest/getCoverArt",
            extraQueryParameters = mapOf("id" to coverArtId, "size" to size.toString()),
        )
    }

    /**
     * Run a Subsonic call that only needs the standard response envelope to produce its result.
     *
     * For endpoints that carry an endpoint-specific payload, write a dedicated method that
     * decodes the payload separately rather than extending this helper.
     */
    private suspend fun <T> callAndDecodeEnvelope(
        pathAfterBase: String,
        extraQueryParameters: Map<String, String>,
        buildResult: (SubsonicResponseEnvelope) -> T,
    ): SubsonicResult<T> {
        val request: Request
        try {
            val requestUrl: String = buildAuthenticatedUrl(pathAfterBase, extraQueryParameters)
            request = Request.Builder().url(requestUrl).get().build()
        } catch (urlBuildFailure: Exception) {
            return SubsonicResult.TransportError(urlBuildFailure)
        }

        val responseBodyText: String = try {
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    if (body == null) {
                        throw IllegalStateException("Subsonic response had no body")
                    }
                    body.string()
                }
            }
        } catch (transportFailure: Exception) {
            return SubsonicResult.TransportError(transportFailure)
        }

        val envelope: SubsonicResponseEnvelope = try {
            jsonDecoder.decodeFromString(SubsonicResponseRoot.serializer(), responseBodyText)
                .subsonicResponse
        } catch (decodeFailure: Exception) {
            return SubsonicResult.MalformedResponse(decodeFailure)
        }

        if (envelope.status != "ok") {
            val errorPayload = envelope.error
            if (errorPayload != null) {
                return SubsonicResult.ServerError(errorPayload.code, errorPayload.message)
            }
            return SubsonicResult.ServerError(-1, "Subsonic returned status=${envelope.status} with no error payload")
        }

        return SubsonicResult.Ok(buildResult(envelope))
    }

    /**
     * Run a Subsonic call whose response is raw JSON without the subsonic-response wrapper.
     *
     * The Pulse extension endpoints return their payload as the top-level JSON object directly,
     * so the standard envelope helper cannot decode them. Status is inferred from the HTTP code:
     * a non-200 response becomes a ServerError without trying to parse the body.
     */
    private suspend fun <P, T> callAndDecodeRaw(
        pathAfterBase: String,
        extraQueryParameters: Map<String, String>,
        payloadDeserializer: KSerializer<P>,
        buildResult: (P) -> T,
    ): SubsonicResult<T> {
        val request: Request
        try {
            val requestUrl: String = buildAuthenticatedUrl(pathAfterBase, extraQueryParameters)
            request = Request.Builder().url(requestUrl).get().build()
        } catch (urlBuildFailure: Exception) {
            return SubsonicResult.TransportError(urlBuildFailure)
        }

        val responseBodyText: String
        val httpStatusCode: Int
        try {
            val pair: Pair<Int, String> = withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body
                    val bodyText: String
                    if (body == null) {
                        bodyText = ""
                    } else {
                        bodyText = body.string()
                    }
                    Pair(response.code, bodyText)
                }
            }
            httpStatusCode = pair.first
            responseBodyText = pair.second
        } catch (transportFailure: Exception) {
            return SubsonicResult.TransportError(transportFailure)
        }

        if (httpStatusCode != 200) {
            return SubsonicResult.ServerError(httpStatusCode, "Unexpected HTTP status from $pathAfterBase")
        }

        val payload: P = try {
            jsonDecoder.decodeFromString(payloadDeserializer, responseBodyText)
        } catch (decodeFailure: Exception) {
            return SubsonicResult.MalformedResponse(decodeFailure)
        }

        return SubsonicResult.Ok(buildResult(payload))
    }

    /**
     * Build the fully-qualified URL for a Subsonic endpoint with auth and standard params attached.
     *
     * Callers supply the path after the server origin (for example "rest/ping" or
     * "rest/pulse/recentlyPlayed") and any endpoint-specific query parameters; this method adds
     * the username, protocol version, client identifier, response format, and the chosen auth
     * params (token+salt or legacy hex-encoded password).
     */
    private fun buildAuthenticatedUrl(
        pathAfterBase: String,
        extraQueryParameters: Map<String, String>,
    ): String {
        val trimmed = credentials.serverUrl.trimEnd('/')
        // If the user omitted a scheme, default to http. https takes precedence when explicitly
        // provided; this default only kicks in for LAN servers typed as bare host[:port].
        val base: String
        val lowerCased = trimmed.lowercase()
        if (lowerCased.startsWith("http://") || lowerCased.startsWith("https://")) {
            base = trimmed
        } else {
            base = "http://" + trimmed
        }
        val builder: HttpUrl.Builder = "$base/$pathAfterBase".toHttpUrl().newBuilder()

        builder.addQueryParameter("u", credentials.username)
        builder.addQueryParameter("v", PROTOCOL_VERSION)
        builder.addQueryParameter("c", CLIENT_NAME)
        builder.addQueryParameter("f", "json")

        for (extraEntry in extraQueryParameters.entries) {
            builder.addQueryParameter(extraEntry.key, extraEntry.value)
        }

        when (authMode) {
            is SubsonicAuthMode.Token -> {
                val salt: String = generateAuthSalt()
                val token: String = computeMd5Hex(credentials.password + salt)
                builder.addQueryParameter("t", token)
                builder.addQueryParameter("s", salt)
            }
            is SubsonicAuthMode.Legacy -> {
                builder.addQueryParameter("p", "enc:" + encodePasswordAsHex(credentials.password))
            }
        }

        return builder.build().toString()
    }

    private fun generateAuthSalt(): String {
        val saltCharacters = "abcdefghijklmnopqrstuvwxyz0123456789"
        val saltCharacterCount = saltCharacters.length
        val saltLength = 12
        val result = StringBuilder(saltLength)
        for (saltCharacterIndex in 0 until saltLength) {
            val pickedIndex = saltRandom.nextInt(saltCharacterCount)
            result.append(saltCharacters[pickedIndex])
        }
        return result.toString()
    }

    private fun computeMd5Hex(input: String): String {
        val digestBytes: ByteArray = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytesToLowerHex(digestBytes)
    }

    private fun encodePasswordAsHex(password: String): String {
        return bytesToLowerHex(password.toByteArray(Charsets.UTF_8))
    }

    private fun bytesToLowerHex(source: ByteArray): String {
        val hexCharacters = "0123456789abcdef"
        val sourceLength = source.size
        val result = StringBuilder(sourceLength * 2)
        for (sourceByteIndex in 0 until sourceLength) {
            val currentByte = source[sourceByteIndex].toInt() and 0xff
            result.append(hexCharacters[currentByte ushr 4])
            result.append(hexCharacters[currentByte and 0x0f])
        }
        return result.toString()
    }

    companion object {
        const val PROTOCOL_VERSION: String = "1.16.1"
        const val CLIENT_NAME: String = "Thump"
        private val saltRandom: SecureRandom = SecureRandom()
    }
}
