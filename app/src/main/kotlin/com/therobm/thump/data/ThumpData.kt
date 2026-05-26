package com.therobm.thump.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.therobm.thump.settings.ThumpSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * THE class. One instance per process. Owns the OkHttpClient, the SQLite database, the on-disk
 * blob directory, the active IProtocol, and the persisted credentials. Everything UI-side talks
 * through this; nothing else in the app constructs an OkHttpClient, opens a file, or sees an
 * IProtocol reference directly.
 *
 * Skeleton scope (Flatline #218): every public method exists, compiles, and routes through the
 * active IProtocol when one is configured. The disk-hit-first cache path is wired through
 * `ThumpBlobStore` but the default policy for binary blobs in this step is "fetch via the
 * protocol" — the disk read/write integration lands as call-sites get ported (Home screen in
 * step 3 etc.). Cache reads from SQLite are stubbed to "always miss" for metadata too; the
 * write path through SQLite exists but no caller has been moved over yet.
 *
 * Multi-process: the UI process and the MediaLibraryService process each construct their own
 * ThumpData against the same SQLite file and blob directory. WAL mode on SQLite plus the
 * atomic-rename pattern on blob writes mean both can read/write concurrently without locking.
 */
class ThumpData(
    private val applicationContext: Context,
) : DataSource {

    private val database: ThumpDatabase = ThumpDatabase(applicationContext)
    private val thumpSettings: ThumpSettings = ThumpSettings(applicationContext)
    private val blobStore: ThumpBlobStore = ThumpBlobStore(
        database = database,
        applicationContext = applicationContext,
        cacheSizeBytesProvider = { thumpSettings.getAudioCacheSizeBytes() },
    )

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jsonDecoder: Json = Json {
        ignoreUnknownKeys = true
    }

    private val credentialsLock: Any = Any()
    private var cachedServerConfig: ServerConfig? = null
    private var activeProtocol: IProtocol? = null

    private var offlineModeEnabled: Boolean = false

    // -- DataSource state ----------------------------------------------------------------------
    private val transferListeners: ArrayList<TransferListener> = ArrayList<TransferListener>()
    private var openDataSpec: DataSpec? = null
    private var openInputStream: InputStream? = null
    private var openBytesRemaining: Long = 0L

    init {
        // Eager bind from disk-or-prefs at construction so the first caller never sees an
        // unconfigured ThumpData. Two cases:
        //   1. server_config has a row (a previous boot already wrote it) — bind from it.
        //   2. row is empty but the legacy SharedPreferences-backed credentials are present
        //      (first boot of the new code on an existing install) — copy them across and
        //      bind. The legacy prefs are intentionally not deleted; other in-flight code
        //      paths may still read them.
        // If neither has credentials, activeProtocol stays null and the typed-guard path
        // (ThumpDataNotConfigured) handles any premature caller.
        eagerlyBindActiveProtocolFromDiskOrLegacyPreferences()
    }

    // -- Server config & protocol selection ----------------------------------------------------

    /**
     * Persist new server credentials, then re-probe the server's protocol support and rebind
     * the active IProtocol implementation. Always treated as a `NetworkOnly` operation: the
     * write goes straight to SQLite and the probe goes straight over HTTP.
     */
    suspend fun setServerConfig(url: String, username: String, password: String) {
        // Default to token-auth for newly-configured servers; legacy mode can be flipped via a
        // future overload once the Settings screen learns to expose it.
        val useTokenAuth: Boolean = true
        val newConfigBeforeProbe: ServerConfig = ServerConfig(
            serverUrl = url,
            username = username,
            password = password,
            useTokenAuth = useTokenAuth,
            detectedProtocol = null,
            lastProbedAtEpochMillis = null,
        )
        withContext(Dispatchers.IO) {
            writeServerConfigRow(newConfigBeforeProbe)
        }
        val subsonicProtocol: SubsonicProtocol = SubsonicProtocol(
            serverUrl = url,
            username = username,
            password = password,
            useTokenAuth = useTokenAuth,
            okHttpClient = httpClient,
            jsonDecoder = jsonDecoder,
        )
        val pulseDetected: Boolean
        try {
            pulseDetected = subsonicProtocol.probeForPulseSupport()
        } catch (probeFailure: IOException) {
            // Probe failures don't break setServerConfig — we just record "no Pulse detected"
            // and proceed with the SubsonicProtocol. The screen can re-probe later.
            installProtocolWithConfig(
                serverConfig = newConfigBeforeProbe.copy(
                    detectedProtocol = DetectedProtocol.Subsonic,
                    lastProbedAtEpochMillis = System.currentTimeMillis(),
                ),
                builtProtocol = subsonicProtocol,
            )
            return
        }
        val protocolForInstall: IProtocol
        val resolvedProtocolKind: DetectedProtocol
        if (pulseDetected) {
            protocolForInstall = PulseProtocol(
                subsonicFallback = subsonicProtocol,
                okHttpClient = httpClient,
                jsonDecoder = jsonDecoder,
            )
            resolvedProtocolKind = DetectedProtocol.Pulse
        } else {
            protocolForInstall = subsonicProtocol
            resolvedProtocolKind = DetectedProtocol.Subsonic
        }
        val finalConfig: ServerConfig = newConfigBeforeProbe.copy(
            detectedProtocol = resolvedProtocolKind,
            lastProbedAtEpochMillis = System.currentTimeMillis(),
        )
        installProtocolWithConfig(finalConfig, protocolForInstall)
    }

    suspend fun getServerConfig(): ServerConfig {
        val configFromCache: ServerConfig? = synchronized(credentialsLock) { cachedServerConfig }
        if (configFromCache != null) {
            return configFromCache
        }
        val configFromDisk: ServerConfig? = withContext(Dispatchers.IO) { readServerConfigRow() }
        if (configFromDisk == null) {
            throw ThumpDataNotConfigured()
        }
        installProtocolWithConfig(
            serverConfig = configFromDisk,
            builtProtocol = buildProtocolForConfig(configFromDisk),
        )
        return configFromDisk
    }

    // -- Connectivity / lifecycle --------------------------------------------------------------

    suspend fun ping(): ServerInfo {
        val protocol: IProtocol = ensureActiveProtocol()
        return protocol.ping()
    }

    suspend fun setOfflineMode(enabled: Boolean) {
        offlineModeEnabled = enabled
    }

    /**
     * Invalidate part of the local cache. Step-2 implementation handles the metadata-table
     * invalidations and the blob-store cases. Per-id metadata flushes wipe the relevant rows
     * so future calls re-fetch; per-id blob flushes go through `ThumpBlobStore.deleteBlob`.
     */
    suspend fun invalidate(spec: InvalidationSpec) {
        withContext(Dispatchers.IO) {
            applyInvalidation(spec)
        }
    }

    // -- Browsing ------------------------------------------------------------------------------

    suspend fun getAllArtists(): List<Artist> {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstList(
            networkCall = { protocol.getAllArtists() },
        )
    }

    suspend fun getArtist(artistId: String): Artist {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstSingle(
            networkCall = { protocol.getArtist(artistId) },
        )
    }

    suspend fun getArtistTracks(artistId: String): List<Track> {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstList(
            networkCall = { protocol.getArtistTracks(artistId) },
        )
    }

    suspend fun getAlbum(albumId: String): Album {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstSingle(
            networkCall = { protocol.getAlbum(albumId) },
        )
    }

    suspend fun getAllAlbums(sort: AlbumSort, limit: Int, offset: Int): List<Album> {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstList(
            networkCall = { protocol.getAllAlbums(sort, limit, offset) },
        )
    }

    suspend fun getGenres(): List<Genre> {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstList(
            networkCall = { protocol.getGenres() },
        )
    }

    suspend fun getTracksByGenre(genre: String, limit: Int, offset: Int): List<Track> {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstList(
            networkCall = { protocol.getTracksByGenre(genre, limit, offset) },
        )
    }

    // -- Playlists -----------------------------------------------------------------------------

    suspend fun getAllPlaylists(): List<Playlist> {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstList(
            networkCall = { protocol.getAllPlaylists() },
        )
    }

    suspend fun getPlaylist(playlistId: String): Playlist {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstSingle(
            networkCall = { protocol.getPlaylist(playlistId) },
        )
    }

    suspend fun createPlaylist(name: String, trackIds: List<String>): Playlist {
        val protocol: IProtocol = ensureActiveProtocol()
        rejectMutationWhenOffline()
        return protocol.createPlaylist(name, trackIds)
    }

    suspend fun updatePlaylist(playlistId: String, edits: PlaylistEdits): Playlist {
        val protocol: IProtocol = ensureActiveProtocol()
        rejectMutationWhenOffline()
        return protocol.updatePlaylist(playlistId, edits)
    }

    suspend fun deletePlaylist(playlistId: String) {
        val protocol: IProtocol = ensureActiveProtocol()
        rejectMutationWhenOffline()
        protocol.deletePlaylist(playlistId)
    }

    // -- Search --------------------------------------------------------------------------------

    suspend fun search(query: String): SearchResult {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstSingle(
            networkCall = { protocol.search(query) },
        )
    }

    // -- Favourites ----------------------------------------------------------------------------

    suspend fun getStarred(): StarredCollection {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstSingle(
            networkCall = { protocol.getStarred() },
        )
    }

    suspend fun star(kind: StarKind, id: String) {
        val protocol: IProtocol = ensureActiveProtocol()
        rejectMutationWhenOffline()
        protocol.star(kind, id)
    }

    suspend fun unstar(kind: StarKind, id: String) {
        val protocol: IProtocol = ensureActiveProtocol()
        rejectMutationWhenOffline()
        protocol.unstar(kind, id)
    }

    suspend fun setRating(kind: StarKind, id: String, rating: Int) {
        val protocol: IProtocol = ensureActiveProtocol()
        rejectMutationWhenOffline()
        protocol.setRating(kind, id, rating)
    }

    // -- Home shelves --------------------------------------------------------------------------

    suspend fun getRecentlyPlayed(limit: Int, types: Set<HomeItemKind>): List<HomeItem> {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstList(
            networkCall = { protocol.getRecentlyPlayed(limit, types) },
        )
    }

    suspend fun getPopularArtists(limit: Int): List<HomeItem> {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstList(
            networkCall = { protocol.getPopularArtists(limit) },
        )
    }

    suspend fun getTopPlaylists(limit: Int): List<HomeItem> {
        val protocol: IProtocol = ensureActiveProtocol()
        return executeNetworkFirstList(
            networkCall = { protocol.getTopPlaylists(limit) },
        )
    }

    // -- Scrobble ------------------------------------------------------------------------------

    suspend fun scrobble(trackId: String, atMillis: Long, submission: Boolean) {
        val protocol: IProtocol = ensureActiveProtocol()
        rejectMutationWhenOffline()
        protocol.scrobble(trackId, atMillis, submission)
    }

    // -- Cover art -----------------------------------------------------------------------------

    /**
     * In-app cover-art entry point: returns a decoded Bitmap. Disk-hit-or-fetch through the
     * active IProtocol. The bytes path used by the ContentProvider is the same fetch with the
     * decode step skipped.
     */
    suspend fun getCoverArt(artId: String, sizePx: Int): Bitmap {
        val bytes: ByteArray = getCoverArtBytes(artId, sizePx)
        val decoded: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded == null) {
            throw IOException(
                "ThumpData could not decode cover-art bytes (id=" + artId
                    + ", size=" + sizePx + ", byteCount=" + bytes.size + ")"
            )
        }
        return decoded
    }

    /**
     * Returns the raw bytes of a cover-art rendering. Used by both the in-app Bitmap path and
     * by `ThumpCoverArtProvider` for cross-process consumers. CacheFirst by default — disk hit
     * first, network fetch on miss, write-through to disk on success.
     */
    suspend fun getCoverArtBytes(artId: String, sizePx: Int): ByteArray {
        val cacheKey: String = ThumpBlobStore.coverArtBlobKey(artId, sizePx)
        val cached: ByteArray? = withContext(Dispatchers.IO) {
            blobStore.readBlobBytes(cacheKey)
        }
        if (cached != null) {
            return cached
        }
        if (offlineModeEnabled) {
            throw IOException(
                "ThumpData offline mode: no cached cover art for id=" + artId + " size=" + sizePx
            )
        }
        val protocol: IProtocol = ensureActiveProtocol()
        val fetched: ByteArray = protocol.getCoverArtBytes(artId, sizePx)
        withContext(Dispatchers.IO) {
            blobStore.writeBlobBytes(
                blobKey = cacheKey,
                bytes = fetched,
                contentType = null,
            )
        }
        return fetched
    }

    // -- DataSource (ExoPlayer integration) ----------------------------------------------------

    /**
     * Open a `thump://track/<id>` URI for ExoPlayer. Step-2 implementation: parse the id,
     * defer to the active IProtocol for an HTTP stream, and hand the bytes through to
     * ExoPlayer's `read`. The disk write-through and the LRU eviction land in a later step
     * once the audio path's caller set has been ported.
     *
     * Blocking by Media3 contract — bridges into the coroutine world via `runBlocking`.
     */
    override fun open(dataSpec: DataSpec): Long {
        val parsedTrackId: String = parseTrackIdFromUri(dataSpec.uri)
        notifyTransferListenersOnTransferInitializing(dataSpec)
        val protocol: IProtocol = runBlocking {
            ensureActiveProtocol()
        }
        val streamResponse: AudioStreamResponse = runBlocking {
            protocol.openAudioStream(parsedTrackId)
        }
        openDataSpec = dataSpec
        openInputStream = streamResponse.inputStream
        openBytesRemaining = streamResponse.totalBytesAvailable
        notifyTransferListenersOnTransferStarted(dataSpec)
        return streamResponse.totalBytesAvailable
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val activeStream: InputStream? = openInputStream
        if (activeStream == null) {
            throw IOException("ThumpData.read called without an open stream")
        }
        if (length == 0) {
            return 0
        }
        val bytesRead: Int = activeStream.read(buffer, offset, length)
        if (bytesRead < 0) {
            return -1
        }
        if (openBytesRemaining > 0L) {
            openBytesRemaining -= bytesRead.toLong()
        }
        val activeSpec: DataSpec? = openDataSpec
        if (activeSpec != null) {
            notifyTransferListenersOnBytesTransferred(activeSpec, bytesRead)
        }
        return bytesRead
    }

    override fun close() {
        val streamToClose: InputStream? = openInputStream
        if (streamToClose != null) {
            try {
                streamToClose.close()
            } catch (closeFailure: IOException) {
                // Swallow — Media3 expects close to be best-effort. Surface via Log if a future
                // logging story lands; for now we just don't propagate.
            }
        }
        val specToFinish: DataSpec? = openDataSpec
        openInputStream = null
        openBytesRemaining = 0L
        openDataSpec = null
        if (specToFinish != null) {
            notifyTransferListenersOnTransferEnded(specToFinish)
        }
    }

    override fun getUri(): Uri? {
        val activeSpec: DataSpec? = openDataSpec
        if (activeSpec == null) {
            return null
        }
        return activeSpec.uri
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    // -- internals -----------------------------------------------------------------------------

    private fun parseTrackIdFromUri(uri: Uri): String {
        val scheme: String? = uri.scheme
        if (scheme != "thump") {
            throw IOException(
                "ThumpData.open expected a thump://track/<id> URI; got scheme=" + scheme
            )
        }
        val authority: String? = uri.authority
        if (authority != "track") {
            throw IOException(
                "ThumpData.open expected thump://track/<id>; got authority=" + authority
            )
        }
        val pathSegments: List<String> = uri.pathSegments
        if (pathSegments.isEmpty()) {
            throw IOException("ThumpData.open got a thump:// URI without a track id")
        }
        return pathSegments[0]
    }

    private fun notifyTransferListenersOnTransferInitializing(dataSpec: DataSpec): Unit {
        val listenerCount: Int = transferListeners.size
        for (listenerIndex in 0 until listenerCount) {
            val listener: TransferListener = transferListeners[listenerIndex]
            listener.onTransferInitializing(this, dataSpec, false)
        }
    }

    private fun notifyTransferListenersOnTransferStarted(dataSpec: DataSpec): Unit {
        val listenerCount: Int = transferListeners.size
        for (listenerIndex in 0 until listenerCount) {
            val listener: TransferListener = transferListeners[listenerIndex]
            listener.onTransferStart(this, dataSpec, false)
        }
    }

    private fun notifyTransferListenersOnBytesTransferred(dataSpec: DataSpec, bytesTransferred: Int): Unit {
        val listenerCount: Int = transferListeners.size
        for (listenerIndex in 0 until listenerCount) {
            val listener: TransferListener = transferListeners[listenerIndex]
            listener.onBytesTransferred(this, dataSpec, false, bytesTransferred)
        }
    }

    private fun notifyTransferListenersOnTransferEnded(dataSpec: DataSpec): Unit {
        val listenerCount: Int = transferListeners.size
        for (listenerIndex in 0 until listenerCount) {
            val listener: TransferListener = transferListeners[listenerIndex]
            listener.onTransferEnd(this, dataSpec, false)
        }
    }

    private suspend fun <T> executeNetworkFirstList(
        networkCall: suspend () -> List<T>,
    ): List<T> {
        if (offlineModeEnabled) {
            // Skeleton step has no on-disk metadata cache wired into the read path; offline
            // mode for metadata yields empty lists until later steps populate the SQLite mirror.
            return emptyList<T>()
        }
        try {
            return networkCall()
        } catch (networkFailure: IOException) {
            // NetworkFirst falls back to cache. Skeleton step has no metadata cache hooked up
            // to the read path yet, so the fallback returns empty rather than throwing.
            return emptyList<T>()
        }
    }

    private suspend fun <T> executeNetworkFirstSingle(
        networkCall: suspend () -> T,
    ): T {
        if (offlineModeEnabled) {
            throw IOException(
                "ThumpData offline mode: skeleton step has no metadata cache for single-record reads"
            )
        }
        return networkCall()
    }

    private fun rejectMutationWhenOffline(): Unit {
        if (offlineModeEnabled) {
            throw IOException("ThumpData mutations fail immediately in offline mode")
        }
    }

    private suspend fun ensureActiveProtocol(): IProtocol {
        val existingProtocol: IProtocol? = synchronized(credentialsLock) { activeProtocol }
        if (existingProtocol != null) {
            return existingProtocol
        }
        val config: ServerConfig? = withContext(Dispatchers.IO) { readServerConfigRow() }
        if (config == null) {
            throw ThumpDataNotConfigured()
        }
        val builtProtocol: IProtocol = buildProtocolForConfig(config)
        installProtocolWithConfig(config, builtProtocol)
        return builtProtocol
    }

    private fun eagerlyBindActiveProtocolFromDiskOrLegacyPreferences(): Unit {
        val configFromDisk: ServerConfig? = readServerConfigRow()
        if (configFromDisk != null) {
            installProtocolWithoutWritingRow(
                serverConfig = configFromDisk,
                builtProtocol = buildProtocolForConfig(configFromDisk),
            )
            return
        }
        val migratedConfig: ServerConfig? = readLegacyCredentialsFromSharedPreferences()
        if (migratedConfig == null) {
            return
        }
        writeServerConfigRow(migratedConfig)
        installProtocolWithoutWritingRow(
            serverConfig = migratedConfig,
            builtProtocol = buildProtocolForConfig(migratedConfig),
        )
    }

    private fun readLegacyCredentialsFromSharedPreferences(): ServerConfig? {
        val legacyPreferences: SharedPreferences = applicationContext.getSharedPreferences(
            LEGACY_CREDENTIALS_PREFS_NAME,
            Context.MODE_PRIVATE,
        )
        val storedServerUrl: String? = legacyPreferences.getString(LEGACY_PREFS_KEY_SERVER_URL, null)
        val storedUsername: String? = legacyPreferences.getString(LEGACY_PREFS_KEY_USERNAME, null)
        val storedPassword: String? = legacyPreferences.getString(LEGACY_PREFS_KEY_PASSWORD, null)
        if (storedServerUrl == null) {
            return null
        }
        if (storedUsername == null) {
            return null
        }
        if (storedPassword == null) {
            return null
        }
        if (storedServerUrl.isBlank()) {
            return null
        }
        if (storedUsername.isBlank()) {
            return null
        }
        if (storedPassword.isBlank()) {
            return null
        }
        val storedUseTokenAuth: Boolean = legacyPreferences.getBoolean(
            LEGACY_PREFS_KEY_USE_TOKEN_AUTH,
            true,
        )
        return ServerConfig(
            serverUrl = storedServerUrl,
            username = storedUsername,
            password = storedPassword,
            useTokenAuth = storedUseTokenAuth,
            detectedProtocol = null,
            lastProbedAtEpochMillis = null,
        )
    }

    private fun installProtocolWithoutWritingRow(
        serverConfig: ServerConfig,
        builtProtocol: IProtocol,
    ): Unit {
        synchronized(credentialsLock) {
            cachedServerConfig = serverConfig
            activeProtocol = builtProtocol
        }
    }

    private fun buildProtocolForConfig(serverConfig: ServerConfig): IProtocol {
        val subsonicProtocol: SubsonicProtocol = SubsonicProtocol(
            serverUrl = serverConfig.serverUrl,
            username = serverConfig.username,
            password = serverConfig.password,
            useTokenAuth = serverConfig.useTokenAuth,
            okHttpClient = httpClient,
            jsonDecoder = jsonDecoder,
        )
        if (serverConfig.detectedProtocol == DetectedProtocol.Pulse) {
            return PulseProtocol(
                subsonicFallback = subsonicProtocol,
                okHttpClient = httpClient,
                jsonDecoder = jsonDecoder,
            )
        }
        return subsonicProtocol
    }

    private fun installProtocolWithConfig(serverConfig: ServerConfig, builtProtocol: IProtocol): Unit {
        synchronized(credentialsLock) {
            cachedServerConfig = serverConfig
            activeProtocol = builtProtocol
        }
        writeServerConfigRow(serverConfig)
    }

    private fun writeServerConfigRow(serverConfig: ServerConfig): Unit {
        val row: ContentValues = ContentValues()
        row.put("singleton_row_id", 1)
        row.put("server_url", serverConfig.serverUrl)
        row.put("username", serverConfig.username)
        row.put("password", serverConfig.password)
        val useTokenAuthAsInt: Int
        if (serverConfig.useTokenAuth) {
            useTokenAuthAsInt = 1
        } else {
            useTokenAuthAsInt = 0
        }
        row.put("use_token_auth", useTokenAuthAsInt)
        val detected: DetectedProtocol? = serverConfig.detectedProtocol
        if (detected == null) {
            row.putNull("detected_protocol")
        } else {
            row.put("detected_protocol", detected.name)
        }
        val lastProbedAt: Long? = serverConfig.lastProbedAtEpochMillis
        if (lastProbedAt == null) {
            row.putNull("last_probed_at_epoch_millis")
        } else {
            row.put("last_probed_at_epoch_millis", lastProbedAt)
        }
        database.writableDatabase.insertWithOnConflict(
            "server_config",
            null,
            row,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun readServerConfigRow(): ServerConfig? {
        val cursor: Cursor = database.readableDatabase.rawQuery(
            "SELECT server_url, username, password, use_token_auth, detected_protocol, "
                + "last_probed_at_epoch_millis FROM server_config WHERE singleton_row_id = 1",
            arrayOf<String>(),
        )
        try {
            if (!cursor.moveToFirst()) {
                return null
            }
            val serverUrl: String = cursor.getString(0)
            val username: String = cursor.getString(1)
            val password: String = cursor.getString(2)
            val useTokenAuthInt: Int = cursor.getInt(3)
            val detectedString: String?
            if (cursor.isNull(4)) {
                detectedString = null
            } else {
                detectedString = cursor.getString(4)
            }
            val lastProbed: Long?
            if (cursor.isNull(5)) {
                lastProbed = null
            } else {
                lastProbed = cursor.getLong(5)
            }
            val detectedProtocol: DetectedProtocol?
            if (detectedString == null) {
                detectedProtocol = null
            } else if (detectedString == DetectedProtocol.Pulse.name) {
                detectedProtocol = DetectedProtocol.Pulse
            } else if (detectedString == DetectedProtocol.Subsonic.name) {
                detectedProtocol = DetectedProtocol.Subsonic
            } else {
                detectedProtocol = null
            }
            return ServerConfig(
                serverUrl = serverUrl,
                username = username,
                password = password,
                useTokenAuth = useTokenAuthInt != 0,
                detectedProtocol = detectedProtocol,
                lastProbedAtEpochMillis = lastProbed,
            )
        } finally {
            cursor.close()
        }
    }

    private fun applyInvalidation(spec: InvalidationSpec): Unit {
        val writableDatabase: SQLiteDatabase = database.writableDatabase
        when (spec) {
            is InvalidationSpec.Everything -> {
                writableDatabase.delete("artists", null, null)
                writableDatabase.delete("albums", null, null)
                writableDatabase.delete("tracks", null, null)
                writableDatabase.delete("playlists", null, null)
                writableDatabase.delete("playlist_tracks", null, null)
                writableDatabase.delete("home_sections", null, null)
                // Blobs are wiped row-by-row so the on-disk files come with them.
                deleteAllBlobsViaIndex()
            }
            is InvalidationSpec.EverythingMetadata -> {
                writableDatabase.delete("artists", null, null)
                writableDatabase.delete("albums", null, null)
                writableDatabase.delete("tracks", null, null)
                writableDatabase.delete("playlists", null, null)
                writableDatabase.delete("playlist_tracks", null, null)
                writableDatabase.delete("home_sections", null, null)
            }
            is InvalidationSpec.EverythingBlobs -> {
                deleteAllBlobsViaIndex()
            }
            is InvalidationSpec.HomeSections -> {
                writableDatabase.delete(
                    "home_sections",
                    "section_key = ?",
                    arrayOf<String>(spec.sectionKey),
                )
            }
            is InvalidationSpec.Track -> {
                writableDatabase.delete(
                    "tracks",
                    "track_id = ?",
                    arrayOf<String>(spec.trackId),
                )
                blobStore.deleteBlob(ThumpBlobStore.trackBlobKey(spec.trackId))
            }
            is InvalidationSpec.Album -> {
                writableDatabase.delete(
                    "albums",
                    "album_id = ?",
                    arrayOf<String>(spec.albumId),
                )
            }
            is InvalidationSpec.Artist -> {
                writableDatabase.delete(
                    "artists",
                    "artist_id = ?",
                    arrayOf<String>(spec.artistId),
                )
            }
            is InvalidationSpec.Playlist -> {
                writableDatabase.delete(
                    "playlists",
                    "playlist_id = ?",
                    arrayOf<String>(spec.playlistId),
                )
                writableDatabase.delete(
                    "playlist_tracks",
                    "playlist_id = ?",
                    arrayOf<String>(spec.playlistId),
                )
            }
        }
    }

    private fun deleteAllBlobsViaIndex(): Unit {
        val cursor: Cursor = database.readableDatabase.rawQuery(
            "SELECT blob_key FROM blobs",
            arrayOf<String>(),
        )
        val collectedKeys: ArrayList<String> = ArrayList<String>()
        try {
            // Materialise the list before deleting so the cursor isn't invalidated mid-walk.
            for (rowIndex in 0 until cursor.count) {
                if (!cursor.moveToNext()) {
                    break
                }
                collectedKeys.add(cursor.getString(0))
            }
        } finally {
            cursor.close()
        }
        val keyCount: Int = collectedKeys.size
        for (keyIndex in 0 until keyCount) {
            blobStore.deleteBlob(collectedKeys[keyIndex])
        }
    }

    private companion object {
        // The legacy SharedPreferences file MainActivity has been using to persist credentials
        // since before the SQLite server_config table existed. ThumpData reads it once on first
        // boot of the new code to migrate the row across; the legacy keys are not deleted
        // because other in-flight code may still read them.
        private const val LEGACY_CREDENTIALS_PREFS_NAME: String = "thump_settings"
        private const val LEGACY_PREFS_KEY_SERVER_URL: String = "server_url"
        private const val LEGACY_PREFS_KEY_USERNAME: String = "username"
        private const val LEGACY_PREFS_KEY_PASSWORD: String = "password"
        private const val LEGACY_PREFS_KEY_USE_TOKEN_AUTH: String = "use_token_auth"
    }
}
