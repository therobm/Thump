package com.therobm.thump.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.therobm.thump.settings.ThumpSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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

    private val metadataCache: ThumpMetadataCache = ThumpMetadataCache(
        database = database,
        jsonCodec = jsonDecoder,
    )

    private val credentialsLock: Any = Any()
    private var cachedServerConfig: ServerConfig? = null
    private var activeProtocol: IProtocol? = null

    private var offlineModeEnabled: Boolean = false

    // -- DataSource state ----------------------------------------------------------------------
    private val transferListeners: ArrayList<TransferListener> = ArrayList<TransferListener>()
    private var openDataSpec: DataSpec? = null
    private var openInputStream: InputStream? = null
    private var openBytesRemaining: Long = 0L

    // -- Audio prefetch state ------------------------------------------------------------------
    //
    // Coalesces concurrent `prefetchAudio` calls for the same trackId so two callers asking
    // for the same audio body only trigger one network download. The Deferred is installed by
    // the first caller and any later caller awaits the same Deferred. Each Deferred runs on
    // `prefetchCoroutineScope`, a private SupervisorJob-backed scope: that scope is what makes
    // the prefetch outlive a caller that gets cancelled (per spec: other awaiters may still
    // need the bytes) and what keeps one prefetch failure from cancelling sibling prefetches
    // when the playback service kicks off a window of them at once.
    private val prefetchCoroutineScope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO,
    )
    private val prefetchInFlightMutex: Mutex = Mutex()
    private val prefetchInFlightDeferreds: HashMap<String, Deferred<Unit>> = HashMap<String, Deferred<Unit>>()

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

    /**
     * Read-only snapshot of the on-disk blob store for the Settings cache panel. Disk-bound
     * aggregate queries, so it hops to `Dispatchers.IO`.
     */
    suspend fun getBlobStoreStats(): BlobStoreStats {
        return withContext(Dispatchers.IO) {
            blobStore.getStats()
        }
    }

    // -- Browsing ------------------------------------------------------------------------------

    suspend fun getAllArtists(): List<Artist> {
        if (offlineModeEnabled) {
            val cachedArtists: List<Artist> = withContext(Dispatchers.IO) {
                metadataCache.loadArtistList()
            }
            if (cachedArtists.isEmpty()) {
                throw IOException("ThumpData offline mode: no cached artists")
            }
            return cachedArtists
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshArtists: List<Artist> = protocol.getAllArtists()
            withContext(Dispatchers.IO) {
                metadataCache.writeArtistList(freshArtists)
            }
            return freshArtists
        } catch (networkFailure: IOException) {
            val cachedArtists: List<Artist> = withContext(Dispatchers.IO) {
                metadataCache.loadArtistList()
            }
            if (cachedArtists.isEmpty()) {
                throw networkFailure
            }
            return cachedArtists
        }
    }

    suspend fun getArtist(artistId: String): Artist {
        if (offlineModeEnabled) {
            val cachedArtist: Artist? = withContext(Dispatchers.IO) {
                metadataCache.loadArtist(artistId)
            }
            if (cachedArtist == null) {
                throw IOException(
                    "ThumpData offline mode: no cached artist for id=" + artistId
                )
            }
            return cachedArtist
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshArtist: Artist = protocol.getArtist(artistId)
            withContext(Dispatchers.IO) {
                metadataCache.writeArtistWithAlbums(freshArtist)
            }
            return freshArtist
        } catch (networkFailure: IOException) {
            val cachedArtist: Artist? = withContext(Dispatchers.IO) {
                metadataCache.loadArtist(artistId)
            }
            if (cachedArtist == null) {
                throw networkFailure
            }
            return cachedArtist
        }
    }

    suspend fun getArtistTracks(artistId: String): List<Track> {
        if (offlineModeEnabled) {
            val cachedTracks: List<Track> = withContext(Dispatchers.IO) {
                metadataCache.loadArtistTracks(artistId)
            }
            if (cachedTracks.isEmpty()) {
                throw IOException(
                    "ThumpData offline mode: no cached tracks for artist id=" + artistId
                )
            }
            return cachedTracks
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshTracks: List<Track> = protocol.getArtistTracks(artistId)
            withContext(Dispatchers.IO) {
                metadataCache.writeArtistTracks(artistId, freshTracks)
            }
            return freshTracks
        } catch (networkFailure: IOException) {
            val cachedTracks: List<Track> = withContext(Dispatchers.IO) {
                metadataCache.loadArtistTracks(artistId)
            }
            if (cachedTracks.isEmpty()) {
                throw networkFailure
            }
            return cachedTracks
        }
    }

    suspend fun getAlbum(albumId: String): Album {
        if (offlineModeEnabled) {
            val cachedAlbum: Album? = withContext(Dispatchers.IO) {
                metadataCache.loadAlbum(albumId)
            }
            if (cachedAlbum == null) {
                throw IOException(
                    "ThumpData offline mode: no cached album for id=" + albumId
                )
            }
            return cachedAlbum
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshAlbum: Album = protocol.getAlbum(albumId)
            withContext(Dispatchers.IO) {
                metadataCache.writeAlbumWithTracks(freshAlbum)
            }
            return freshAlbum
        } catch (networkFailure: IOException) {
            val cachedAlbum: Album? = withContext(Dispatchers.IO) {
                metadataCache.loadAlbum(albumId)
            }
            if (cachedAlbum == null) {
                throw networkFailure
            }
            return cachedAlbum
        }
    }

    suspend fun getAllAlbums(sort: AlbumSort, limit: Int, offset: Int): List<Album> {
        if (offlineModeEnabled) {
            val cachedAlbums: List<Album> = withContext(Dispatchers.IO) {
                metadataCache.loadAlbumList(sort, limit, offset)
            }
            if (cachedAlbums.isEmpty()) {
                throw IOException(
                    "ThumpData offline mode: no cached albums for sort=" + sort.name
                )
            }
            return cachedAlbums
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshAlbums: List<Album> = protocol.getAllAlbums(sort, limit, offset)
            withContext(Dispatchers.IO) {
                metadataCache.writeAlbumList(freshAlbums)
            }
            return freshAlbums
        } catch (networkFailure: IOException) {
            val cachedAlbums: List<Album> = withContext(Dispatchers.IO) {
                metadataCache.loadAlbumList(sort, limit, offset)
            }
            if (cachedAlbums.isEmpty()) {
                throw networkFailure
            }
            return cachedAlbums
        }
    }

    suspend fun getGenres(): List<Genre> {
        if (offlineModeEnabled) {
            val cachedGenres: List<Genre> = withContext(Dispatchers.IO) {
                metadataCache.loadGenreList()
            }
            if (cachedGenres.isEmpty()) {
                throw IOException("ThumpData offline mode: no cached genres")
            }
            return cachedGenres
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshGenres: List<Genre> = protocol.getGenres()
            withContext(Dispatchers.IO) {
                metadataCache.writeGenreList(freshGenres)
            }
            return freshGenres
        } catch (networkFailure: IOException) {
            val cachedGenres: List<Genre> = withContext(Dispatchers.IO) {
                metadataCache.loadGenreList()
            }
            if (cachedGenres.isEmpty()) {
                throw networkFailure
            }
            return cachedGenres
        }
    }

    suspend fun getTracksByGenre(genre: String, limit: Int, offset: Int): List<Track> {
        if (offlineModeEnabled) {
            val cachedTracks: List<Track> = withContext(Dispatchers.IO) {
                metadataCache.loadTracksByGenre(genre, limit, offset)
            }
            if (cachedTracks.isEmpty()) {
                throw IOException(
                    "ThumpData offline mode: no cached tracks for genre=" + genre
                )
            }
            return cachedTracks
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshTracks: List<Track> = protocol.getTracksByGenre(genre, limit, offset)
            withContext(Dispatchers.IO) {
                metadataCache.writeTrackList(freshTracks)
            }
            return freshTracks
        } catch (networkFailure: IOException) {
            val cachedTracks: List<Track> = withContext(Dispatchers.IO) {
                metadataCache.loadTracksByGenre(genre, limit, offset)
            }
            if (cachedTracks.isEmpty()) {
                throw networkFailure
            }
            return cachedTracks
        }
    }

    // -- Playlists -----------------------------------------------------------------------------

    suspend fun getAllPlaylists(): List<Playlist> {
        if (offlineModeEnabled) {
            val cachedPlaylists: List<Playlist> = withContext(Dispatchers.IO) {
                metadataCache.loadPlaylistList()
            }
            if (cachedPlaylists.isEmpty()) {
                throw IOException("ThumpData offline mode: no cached playlists")
            }
            return cachedPlaylists
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshPlaylists: List<Playlist> = protocol.getAllPlaylists()
            withContext(Dispatchers.IO) {
                metadataCache.writePlaylistList(freshPlaylists)
            }
            return freshPlaylists
        } catch (networkFailure: IOException) {
            val cachedPlaylists: List<Playlist> = withContext(Dispatchers.IO) {
                metadataCache.loadPlaylistList()
            }
            if (cachedPlaylists.isEmpty()) {
                throw networkFailure
            }
            return cachedPlaylists
        }
    }

    suspend fun getPlaylist(playlistId: String): Playlist {
        if (offlineModeEnabled) {
            val cachedPlaylist: Playlist? = withContext(Dispatchers.IO) {
                metadataCache.loadPlaylist(playlistId)
            }
            if (cachedPlaylist == null) {
                throw IOException(
                    "ThumpData offline mode: no cached playlist for id=" + playlistId
                )
            }
            return cachedPlaylist
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshPlaylist: Playlist = protocol.getPlaylist(playlistId)
            withContext(Dispatchers.IO) {
                metadataCache.writePlaylistWithTracks(freshPlaylist)
            }
            return freshPlaylist
        } catch (networkFailure: IOException) {
            val cachedPlaylist: Playlist? = withContext(Dispatchers.IO) {
                metadataCache.loadPlaylist(playlistId)
            }
            if (cachedPlaylist == null) {
                throw networkFailure
            }
            return cachedPlaylist
        }
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
        if (offlineModeEnabled) {
            val cachedStarred: StarredCollection? = withContext(Dispatchers.IO) {
                metadataCache.loadStarred()
            }
            if (cachedStarred == null) {
                throw IOException("ThumpData offline mode: no cached starred collection")
            }
            return cachedStarred
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshStarred: StarredCollection = protocol.getStarred()
            withContext(Dispatchers.IO) {
                metadataCache.writeStarred(freshStarred)
            }
            return freshStarred
        } catch (networkFailure: IOException) {
            val cachedStarred: StarredCollection? = withContext(Dispatchers.IO) {
                metadataCache.loadStarred()
            }
            if (cachedStarred == null) {
                throw networkFailure
            }
            return cachedStarred
        }
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
        val sectionKey: String = ThumpMetadataCache.recentlyPlayedSectionKey(types)
        if (offlineModeEnabled) {
            val cachedItems: List<HomeItem>? = withContext(Dispatchers.IO) {
                metadataCache.loadHomeShelf(sectionKey)
            }
            if (cachedItems == null) {
                throw IOException(
                    "ThumpData offline mode: no cached recents for key=" + sectionKey
                )
            }
            return cachedItems
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshItems: List<HomeItem> = protocol.getRecentlyPlayed(limit, types)
            withContext(Dispatchers.IO) {
                metadataCache.writeHomeShelf(sectionKey, freshItems)
            }
            return freshItems
        } catch (networkFailure: IOException) {
            val cachedItems: List<HomeItem>? = withContext(Dispatchers.IO) {
                metadataCache.loadHomeShelf(sectionKey)
            }
            if (cachedItems == null) {
                throw networkFailure
            }
            return cachedItems
        }
    }

    suspend fun getPopularArtists(limit: Int): List<HomeItem> {
        val sectionKey: String = ThumpMetadataCache.SECTION_KEY_POPULAR_ARTISTS
        if (offlineModeEnabled) {
            val cachedItems: List<HomeItem>? = withContext(Dispatchers.IO) {
                metadataCache.loadHomeShelf(sectionKey)
            }
            if (cachedItems == null) {
                throw IOException("ThumpData offline mode: no cached popular artists")
            }
            return cachedItems
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshItems: List<HomeItem> = protocol.getPopularArtists(limit)
            withContext(Dispatchers.IO) {
                metadataCache.writeHomeShelf(sectionKey, freshItems)
            }
            return freshItems
        } catch (networkFailure: IOException) {
            val cachedItems: List<HomeItem>? = withContext(Dispatchers.IO) {
                metadataCache.loadHomeShelf(sectionKey)
            }
            if (cachedItems == null) {
                throw networkFailure
            }
            return cachedItems
        }
    }

    suspend fun getTopPlaylists(limit: Int): List<HomeItem> {
        val sectionKey: String = ThumpMetadataCache.SECTION_KEY_TOP_PLAYLISTS
        if (offlineModeEnabled) {
            val cachedItems: List<HomeItem>? = withContext(Dispatchers.IO) {
                metadataCache.loadHomeShelf(sectionKey)
            }
            if (cachedItems == null) {
                throw IOException("ThumpData offline mode: no cached top playlists")
            }
            return cachedItems
        }
        val protocol: IProtocol = ensureActiveProtocol()
        try {
            val freshItems: List<HomeItem> = protocol.getTopPlaylists(limit)
            withContext(Dispatchers.IO) {
                metadataCache.writeHomeShelf(sectionKey, freshItems)
            }
            return freshItems
        } catch (networkFailure: IOException) {
            val cachedItems: List<HomeItem>? = withContext(Dispatchers.IO) {
                metadataCache.loadHomeShelf(sectionKey)
            }
            if (cachedItems == null) {
                throw networkFailure
            }
            return cachedItems
        }
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

    // -- Audio prefetch ------------------------------------------------------------------------

    /**
     * Download a track's full audio body into the on-disk blob store ahead of playback. The
     * playback service drives this with a lookahead window so by the time ExoPlayer asks the
     * DataSource to open a track the bytes are already on disk.
     *
     * Idempotent — a no-op when the blob is already cached. Coalesces concurrent calls for the
     * same `trackId` behind a single in-flight Deferred so a duplicate request only triggers
     * one network download. Cancellation-aware: callers that get cancelled while awaiting do
     * not cancel the underlying download (siblings may still need the bytes); cancelling the
     * Deferred itself runs the finally block that drops the partial `.tmp` file.
     */
    suspend fun prefetchAudio(trackId: String): Unit {
        val cacheKey: String = ThumpBlobStore.trackBlobKey(trackId)
        val existingFile: File? = withContext(Dispatchers.IO) {
            blobStore.openBlobFile(cacheKey)
        }
        Log.d("ThumpRecovery", "prefetchAudio entry trackId=" + trackId + " cacheKey=" + cacheKey + " alreadyCached=" + (existingFile != null))
        if (existingFile != null) {
            return
        }
        val deferredToAwait: Deferred<Unit>
        prefetchInFlightMutex.withLock {
            val existingDeferred: Deferred<Unit>? = prefetchInFlightDeferreds[trackId]
            if (existingDeferred != null) {
                deferredToAwait = existingDeferred
            } else {
                val createdDeferred: Deferred<Unit> = prefetchCoroutineScope.async {
                    executePrefetchDownload(trackId)
                }
                prefetchInFlightDeferreds[trackId] = createdDeferred
                createdDeferred.invokeOnCompletion { completionThrowable: Throwable? ->
                    val ignoredCompletionThrowable: Throwable? = completionThrowable
                    removePrefetchDeferredEntry(trackId, createdDeferred)
                }
                deferredToAwait = createdDeferred
            }
        }
        deferredToAwait.await()
    }

    /**
     * Body of the in-flight prefetch Deferred. Resolves the active protocol, opens the audio
     * stream, and copies it to a temp file via `ThumpBlobStore.createTemporaryBlobFile` /
     * `commitTemporaryBlobFile`. On any failure (including cancellation) the temp file is
     * dropped in a NonCancellable cleanup so a half-written `.tmp` never lingers in the blob
     * directory.
     */
    private suspend fun executePrefetchDownload(trackId: String): Unit {
        Log.d("ThumpRecovery", "executePrefetchDownload start trackId=" + trackId)
        val cacheKey: String = ThumpBlobStore.trackBlobKey(trackId)
        val protocol: IProtocol = ensureActiveProtocol()
        val streamResponse: AudioStreamResponse = protocol.openAudioStream(trackId)
        val temporaryHandle: ThumpBlobStore.TemporaryBlobHandle = withContext(Dispatchers.IO) {
            blobStore.createTemporaryBlobFile(cacheKey)
        }
        try {
            val totalBytesWritten: Long = withContext(Dispatchers.IO) {
                copyNetworkStreamToTemporaryFile(
                    networkInputStream = streamResponse.inputStream,
                    expectedBytes = streamResponse.totalBytesAvailable,
                    temporaryFile = temporaryHandle.temporaryFile,
                    trackId = trackId,
                )
            }
            Log.d("ThumpRecovery", "executePrefetchDownload commit trackId=" + trackId + " bytes=" + totalBytesWritten)
            withContext(Dispatchers.IO) {
                blobStore.commitTemporaryBlobFile(
                    handle = temporaryHandle,
                    sizeBytes = totalBytesWritten,
                    contentType = streamResponse.contentType,
                )
            }
        } catch (downloadThrowable: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                blobStore.discardTemporaryBlobFile(temporaryHandle)
            }
            throw downloadThrowable
        }
    }

    private fun copyNetworkStreamToTemporaryFile(
        networkInputStream: InputStream,
        expectedBytes: Long,
        temporaryFile: File,
        trackId: String,
    ): Long {
        // Bytes drive termination, not iteration count. Network reads usually return one TCP
        // segment at a time (1–8 KiB), so an iteration cap derived from (expectedBytes / bufferSize)
        // truncates downloads long before EOF. The for-loop here carries an absolute structural
        // ceiling so the Vibratron "no while loops" rule still holds, while the real exit is the
        // explicit break/throw inside the loop body.
        val bufferSize: Int = AUDIO_PREFETCH_COPY_BUFFER_BYTES
        val copyBuffer: ByteArray = ByteArray(bufferSize)
        val unknownLengthByteCap: Long = AUDIO_PREFETCH_MAX_UNKNOWN_LENGTH_BYTES
        var totalBytesWritten: Long = 0L
        val fileOutputStream: FileOutputStream = FileOutputStream(temporaryFile)
        try {
            try {
                if (expectedBytes > 0L) {
                    for (chunkIndex in 0 until AUDIO_PREFETCH_ABSOLUTE_ITERATION_CEILING) {
                        if (totalBytesWritten >= expectedBytes) {
                            break
                        }
                        val remainingBytes: Long = expectedBytes - totalBytesWritten
                        val bytesToRequestThisChunk: Int
                        if (remainingBytes < bufferSize.toLong()) {
                            bytesToRequestThisChunk = remainingBytes.toInt()
                        } else {
                            bytesToRequestThisChunk = bufferSize
                        }
                        val bytesReadThisChunk: Int = networkInputStream.read(
                            copyBuffer,
                            0,
                            bytesToRequestThisChunk,
                        )
                        if (bytesReadThisChunk < 0) {
                            throw IOException(
                                "Audio stream ended at " + totalBytesWritten + " of "
                                    + expectedBytes + " bytes for trackId=" + trackId
                            )
                        }
                        if (bytesReadThisChunk == 0) {
                            continue
                        }
                        fileOutputStream.write(copyBuffer, 0, bytesReadThisChunk)
                        totalBytesWritten += bytesReadThisChunk.toLong()
                    }
                } else {
                    for (chunkIndex in 0 until AUDIO_PREFETCH_ABSOLUTE_ITERATION_CEILING) {
                        if (totalBytesWritten > unknownLengthByteCap) {
                            throw IOException(
                                "Audio stream exceeded " + unknownLengthByteCap
                                    + "-byte unknown-length cap at " + totalBytesWritten
                                    + " bytes for trackId=" + trackId
                            )
                        }
                        val bytesReadThisChunk: Int = networkInputStream.read(
                            copyBuffer,
                            0,
                            bufferSize,
                        )
                        if (bytesReadThisChunk < 0) {
                            break
                        }
                        if (bytesReadThisChunk == 0) {
                            continue
                        }
                        fileOutputStream.write(copyBuffer, 0, bytesReadThisChunk)
                        totalBytesWritten += bytesReadThisChunk.toLong()
                    }
                }
                fileOutputStream.flush()
                fileOutputStream.fd.sync()
            } finally {
                fileOutputStream.close()
            }
        } finally {
            try {
                networkInputStream.close()
            } catch (closeFailure: IOException) {
                // Best-effort — the body was either drained or aborted; either way the
                // underlying socket should be released to the pool.
                val ignoredCloseFailure: IOException = closeFailure
            }
        }
        if (expectedBytes > 0L && totalBytesWritten != expectedBytes) {
            throw IOException(
                "Audio stream wrote " + totalBytesWritten + " bytes but expected "
                    + expectedBytes + " for trackId=" + trackId
            )
        }
        return totalBytesWritten
    }

    /**
     * Cheap synchronous check for whether the audio body for [trackId] is already on disk.
     * Backed by the same `blobStore.openBlobFile` call the DataSource uses, so a `true` return
     * here means a subsequent `DataSource.open` for the same track will not throw the
     * "audio blob not cached" IOException. Safe to call from the main thread — the underlying
     * lookup is a single SQLite row read plus a `File.exists()` check, both negligible at the
     * sizes the blob index runs at.
     *
     * Used by the playback service's onMediaItemTransition gate to decide whether to pause and
     * drive `prefetchAudio` before letting ExoPlayer attempt to load the new current track.
     */
    fun isAudioBlobCached(trackId: String): Boolean {
        val cacheKey: String = ThumpBlobStore.trackBlobKey(trackId)
        val cachedFile: File? = blobStore.openBlobFile(cacheKey)
        return cachedFile != null
    }

    private fun removePrefetchDeferredEntry(trackId: String, expectedDeferred: Deferred<Unit>): Unit {
        // invokeOnCompletion runs synchronously off the completing Deferred — drop the
        // coalescing entry through an async best-effort path so we do not block the completion
        // callback on the mutex.
        prefetchCoroutineScope.launch {
            prefetchInFlightMutex.withLock {
                val currentDeferred: Deferred<Unit>? = prefetchInFlightDeferreds[trackId]
                if (currentDeferred === expectedDeferred) {
                    prefetchInFlightDeferreds.remove(trackId)
                }
            }
        }
    }

    // -- DataSource (ExoPlayer integration) ----------------------------------------------------

    /**
     * Open a `thump://track/<id>` URI for ExoPlayer. Cache-only: serves bytes from the audio
     * blob already on disk. On a miss this throws — the playback service is responsible for
     * driving `prefetchAudio` before invoking ExoPlayer. There is no network branch here, no
     * tee/pass-through, no write-through. ExoPlayer reads from disk and only from disk.
     *
     * Blocking by Media3 contract — but the implementation is pure synchronous file IO so no
     * coroutine bridge is required.
     */
    override fun open(dataSpec: DataSpec): Long {
        val parsedTrackId: String = parseTrackIdFromUri(dataSpec.uri)
        notifyTransferListenersOnTransferInitializing(dataSpec)
        val cacheKey: String = ThumpBlobStore.trackBlobKey(parsedTrackId)
        val cachedFile: File? = blobStore.openBlobFile(cacheKey)
        if (cachedFile == null) {
            throw IOException(
                "ThumpData: audio blob not cached for trackId=" + parsedTrackId
                    + " — playback service must prefetchAudio before invoking ExoPlayer"
            )
        }
        val totalFileBytes: Long = cachedFile.length()
        val seekPosition: Long = dataSpec.position
        if (seekPosition < 0L) {
            throw IOException(
                "ThumpData: dataSpec.position=" + seekPosition + " is negative for trackId="
                    + parsedTrackId
            )
        }
        if (seekPosition > totalFileBytes) {
            throw IOException(
                "ThumpData: dataSpec.position=" + seekPosition + " past blob end="
                    + totalFileBytes + " for trackId=" + parsedTrackId
            )
        }
        val bytesAvailableFromPosition: Long = totalFileBytes - seekPosition
        val requestedLength: Long = dataSpec.length
        val bytesToServe: Long
        if (requestedLength == C.LENGTH_UNSET.toLong()) {
            bytesToServe = bytesAvailableFromPosition
        } else if (requestedLength > bytesAvailableFromPosition) {
            bytesToServe = bytesAvailableFromPosition
        } else {
            bytesToServe = requestedLength
        }
        val cachedFileInputStream: FileInputStream = FileInputStream(cachedFile)
        seekFileInputStream(cachedFileInputStream, seekPosition, parsedTrackId)
        openDataSpec = dataSpec
        openInputStream = cachedFileInputStream
        openBytesRemaining = bytesToServe
        notifyTransferListenersOnTransferStarted(dataSpec)
        return bytesToServe
    }

    private fun seekFileInputStream(
        cachedFileInputStream: FileInputStream,
        seekPosition: Long,
        trackIdForErrorMessage: String,
    ): Unit {
        if (seekPosition <= 0L) {
            return
        }
        var bytesRemainingToSkip: Long = seekPosition
        val maxSkipIterations: Int = AUDIO_PREFETCH_MAX_SKIP_ITERATIONS
        for (skipIteration in 0 until maxSkipIterations) {
            if (bytesRemainingToSkip <= 0L) {
                return
            }
            val skippedThisCall: Long = cachedFileInputStream.skip(bytesRemainingToSkip)
            if (skippedThisCall <= 0L) {
                throw IOException(
                    "ThumpData: could not seek to position=" + seekPosition
                        + " (remaining=" + bytesRemainingToSkip + ") for trackId="
                        + trackIdForErrorMessage
                )
            }
            bytesRemainingToSkip -= skippedThisCall
        }
        if (bytesRemainingToSkip > 0L) {
            throw IOException(
                "ThumpData: exhausted skip iterations seeking to position=" + seekPosition
                    + " (remaining=" + bytesRemainingToSkip + ") for trackId="
                    + trackIdForErrorMessage
            )
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val activeStream: InputStream? = openInputStream
        if (activeStream == null) {
            throw IOException("ThumpData.read called without an open stream")
        }
        if (length == 0) {
            return 0
        }
        if (openBytesRemaining <= 0L) {
            return C.RESULT_END_OF_INPUT
        }
        val maxBytesToReadThisCall: Int
        if (length.toLong() > openBytesRemaining) {
            maxBytesToReadThisCall = openBytesRemaining.toInt()
        } else {
            maxBytesToReadThisCall = length
        }
        val bytesRead: Int = activeStream.read(buffer, offset, maxBytesToReadThisCall)
        if (bytesRead < 0) {
            return C.RESULT_END_OF_INPUT
        }
        openBytesRemaining -= bytesRead.toLong()
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
        // TODO(Flatline #243): Transitional bridge for legacy Android Auto playback.
        // ThumpMediaLibraryCallback still constructs http(s)://<host>/rest/stream?id=<trackId>
        // MediaItem URIs via the pre-#236 SubsonicClient path. Accept that shape here until
        // the Auto callback ports to ThumpData (separate larger follow-up bug). Delete this
        // bridge once only thump://track/<id> URIs reach open().
        val scheme: String? = uri.scheme
        if (scheme == "http" || scheme == "https")
        {
            val legacyPath: String? = uri.path
            if (legacyPath != null && legacyPath.endsWith("/rest/stream"))
            {
                val legacyTrackId: String? = uri.getQueryParameter("id")
                if (legacyTrackId != null)
                {
                    return legacyTrackId
                }
            }
        }
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

    private suspend fun <T> executeNetworkFirstSingle(
        networkCall: suspend () -> T,
    ): T {
        if (offlineModeEnabled) {
            throw IOException(
                "ThumpData offline mode: no cache available for this call"
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

        // 64KiB chunk size for the prefetch copy loop. Matches OkHttp's source-buffer chunking
        // and keeps per-iteration allocation cheap.
        private const val AUDIO_PREFETCH_COPY_BUFFER_BYTES: Int = 64 * 1024
        // Hard byte ceiling for unknown-length downloads (no Content-Length from the server).
        // 512 MiB is far above any realistic audio file but bounds a runaway response so it can't
        // fill the disk.
        private const val AUDIO_PREFETCH_MAX_UNKNOWN_LENGTH_BYTES: Long = 512L * 1024L * 1024L
        // Structural upper bound on the copy for-loop so the "no while loops" rule still holds.
        // The actual termination is byte-driven (totalBytesWritten reaching expectedBytes, or
        // read() returning -1 for unknown-length downloads); this ceiling is set high enough that
        // it can never be reached at the 64 KiB chunk size on any realistic stream.
        private const val AUDIO_PREFETCH_ABSOLUTE_ITERATION_CEILING: Int = Int.MAX_VALUE / 2
        // FileInputStream.skip on a regular file returns the full requested skip in a single
        // call on every Android filesystem we ship to, so a small safety bound is plenty.
        private const val AUDIO_PREFETCH_MAX_SKIP_ITERATIONS: Int = 64
    }
}
