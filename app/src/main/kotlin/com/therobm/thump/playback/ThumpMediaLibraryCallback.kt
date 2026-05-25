package com.therobm.thump.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.therobm.thump.subsonic.StandardAlbumDetailPayload
import com.therobm.thump.subsonic.StandardAlbumSummary
import com.therobm.thump.subsonic.StandardArtistDetailPayload
import com.therobm.thump.subsonic.StandardLibraryArtist
import com.therobm.thump.subsonic.StandardPlaylistDetailPayload
import com.therobm.thump.subsonic.StandardPlaylistSummary
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.guava.future

private const val MEDIA_ID_ROOT: String = "thump-root"
private const val MEDIA_ID_HOME: String = "thump-root/home"
private const val MEDIA_ID_RECENTS: String = "thump-root/recents"
private const val MEDIA_ID_PLAYLISTS: String = "thump-root/playlists"
private const val MEDIA_ID_ARTISTS: String = "thump-root/artists"
private const val MEDIA_ID_PREFIX_PLAYLIST: String = "thump-root/playlist/"
private const val MEDIA_ID_PREFIX_ALBUM: String = "thump-root/album/"
private const val MEDIA_ID_PREFIX_ARTIST: String = "thump-root/artist/"
private const val MEDIA_ID_PREFIX_TRACK: String = "thump-track/"

private const val HOME_SECTION_ITEM_LIMIT: Int = 15
private const val RECENTS_TOTAL_LIMIT: Int = 15
private const val RECENTS_TRACK_SCAN_COUNT: Int = 60
private const val RECENTS_FALLBACK_ALBUM_COUNT: Int = 15
private const val PLAYLISTS_FETCH_LIMIT: Int = 500
private const val COVER_ART_REQUEST_SIZE_PX: Int = 400

// Android Auto content-style hints. Attached to every browseable item's MediaMetadata extras so
// Auto knows how to render that item's children. We use grid for everything browseable
// (playlists / albums / artists / shelves) and list for playable children (tracks inside an
// album or playlist). Constants intentionally inlined as Strings/Ints — the canonical Media3
// names are not stable across versions.
private const val CONTENT_STYLE_BROWSABLE_HINT_KEY: String = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
private const val CONTENT_STYLE_PLAYABLE_HINT_KEY: String = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
private const val CONTENT_STYLE_LIST_ITEM: Int = 1
private const val CONTENT_STYLE_GRID_ITEM: Int = 2
// When set on a child, Auto renders a section header with this title above that child (and
// groups consecutive children sharing the title under one header). This is how the Home folder
// shows its five sections inline instead of forcing the user to drill into sub-folders.
private const val CONTENT_STYLE_GROUP_TITLE_HINT_KEY: String = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"

/**
 * Browse-tree callback for the MediaLibrarySession used by Android Auto and any other
 * MediaBrowser client. All async work is bridged from suspend code via kotlinx-coroutines-guava.
 *
 * The tree is intentionally flat one level deep — Auto users are driving, so deep nesting is a
 * non-starter. Search and per-item playback context (siblings auto-queue) are deferred follow-
 * ups.
 */
class ThumpMediaLibraryCallback(
    private val applicationCoroutineScope: CoroutineScope,
    private val credentialsLoader: PlaybackCredentialsLoader,
) : MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val rootItem = buildBrowseableItem(
            mediaId = MEDIA_ID_ROOT,
            title = "Thump",
        )
        return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        // Auto sometimes asks for a single item by id (especially playables). Build a stub that
        // setMediaItem can immediately consume. For browseable shelves we return their header.
        return Futures.immediateFuture(LibraryResult.ofItem(stubForMediaId(mediaId), null))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        return applicationCoroutineScope.future {
            val subsonicClient = credentialsLoader.loadSubsonicClient()
            if (subsonicClient == null) {
                return@future LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
            }

            if (parentId == MEDIA_ID_ROOT) {
                return@future LibraryResult.ofItemList(buildRootChildren(), params)
            }
            if (parentId == MEDIA_ID_HOME) {
                return@future buildHomeChildren(subsonicClient, params)
            }
            if (parentId == MEDIA_ID_RECENTS) {
                return@future buildRecentsChildren(subsonicClient, params)
            }
            if (parentId == MEDIA_ID_PLAYLISTS) {
                return@future buildPlaylistsChildren(subsonicClient, params)
            }
            if (parentId == MEDIA_ID_ARTISTS) {
                return@future buildArtistsChildren(subsonicClient, params)
            }
            if (parentId.startsWith(MEDIA_ID_PREFIX_PLAYLIST)) {
                val playlistId = parentId.removePrefix(MEDIA_ID_PREFIX_PLAYLIST)
                return@future buildPlaylistChildren(subsonicClient, playlistId, params)
            }
            if (parentId.startsWith(MEDIA_ID_PREFIX_ALBUM)) {
                val albumId = parentId.removePrefix(MEDIA_ID_PREFIX_ALBUM)
                return@future buildAlbumChildren(subsonicClient, albumId, params)
            }
            if (parentId.startsWith(MEDIA_ID_PREFIX_ARTIST)) {
                val artistId = parentId.removePrefix(MEDIA_ID_PREFIX_ARTIST)
                return@future buildArtistChildren(subsonicClient, artistId, params)
            }
            LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        }
    }

    /**
     * Auto resolves a tapped track by sending the original (browseable / pre-resolved) MediaItem
     * back through onAddMediaItems. We rewrite each entry to carry the actual stream URI so the
     * underlying player can fetch it.
     */
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        return applicationCoroutineScope.future {
            val subsonicClient = credentialsLoader.loadSubsonicClient()
            if (subsonicClient == null) {
                return@future mediaItems
            }
            val resolved = ArrayList<MediaItem>(mediaItems.size)
            val count = mediaItems.size
            for (index in 0 until count) {
                val incoming = mediaItems[index]
                val trackId = trackIdFromMediaId(incoming.mediaId)
                if (trackId == null) {
                    resolved.add(incoming)
                } else {
                    resolved.add(buildPlayableMediaItem(subsonicClient, trackId, incoming.mediaMetadata))
                }
            }
            resolved
        }
    }

    private fun buildRootChildren(): ImmutableList<MediaItem> {
        val children = ImmutableList.builder<MediaItem>()
        // Home gets a custom hint set: both browseable AND playable children render as grid,
        // so the inline Recently Played track tiles match the rest of the shelves instead of
        // collapsing into a list. Other top-level entries keep the default playable=list so
        // tracks inside playlists/albums still show as a list when the user drills in.
        children.add(buildHomeShelfItem())
        children.add(buildBrowseableItem(MEDIA_ID_RECENTS, "Recents"))
        children.add(buildBrowseableItem(MEDIA_ID_PLAYLISTS, "Playlists"))
        children.add(buildBrowseableItem(MEDIA_ID_ARTISTS, "Artists"))
        return children.build()
    }

    private fun buildHomeShelfItem(): MediaItem {
        val extras = android.os.Bundle()
        extras.putInt(CONTENT_STYLE_BROWSABLE_HINT_KEY, CONTENT_STYLE_GRID_ITEM)
        extras.putInt(CONTENT_STYLE_PLAYABLE_HINT_KEY, CONTENT_STYLE_GRID_ITEM)
        val metadata = MediaMetadata.Builder()
            .setTitle("Home")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(MEDIA_ID_HOME)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Home: a flat folder whose children carry the section's name in their group-title extra,
     * so Auto renders "Recently Played" / "Your Playlists" / etc. as inline section headers
     * inside the Home tab (the way Spotify and YouTube Music do) instead of forcing the user
     * to drill into sub-folders.
     *
     * All five sections are fetched in parallel and concatenated in display order.
     */
    private suspend fun buildHomeChildren(
        subsonicClient: SubsonicClient,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val isPulse = credentialsLoader.loadIsPulseDetected()
        val playlistsTitle: String
        val popularOrFrequentTitle: String
        if (isPulse) {
            playlistsTitle = "Your Playlists"
            popularOrFrequentTitle = "Popular Artists"
        } else {
            playlistsTitle = "Playlists"
            popularOrFrequentTitle = "Most Played"
        }

        val sections: List<List<MediaItem>> = coroutineScope {
            val playlistsDeferred = async {
                fetchPlaylistsSection(subsonicClient, isPulse, playlistsTitle)
            }
            val recentlyPlayedDeferred = async {
                fetchRecentlyPlayedSection(subsonicClient, isPulse, "Recently Played")
            }
            val popularOrFrequentDeferred = async {
                fetchPopularOrFrequentSection(subsonicClient, isPulse, popularOrFrequentTitle)
            }
            val recentlyAddedDeferred = async {
                fetchRecentlyAddedSection(subsonicClient, "Recently Added")
            }
            val favoritesDeferred = async {
                fetchFavoritesSection(subsonicClient, "Favorites")
            }
            listOf(
                playlistsDeferred.await(),
                recentlyPlayedDeferred.await(),
                popularOrFrequentDeferred.await(),
                recentlyAddedDeferred.await(),
                favoritesDeferred.await(),
            )
        }

        val combined = ImmutableList.builder<MediaItem>()
        val sectionCount = sections.size
        for (sectionIndex in 0 until sectionCount) {
            combined.addAll(sections[sectionIndex])
        }
        return LibraryResult.ofItemList(combined.build(), params)
    }

    private suspend fun fetchRecentlyPlayedSection(
        subsonicClient: SubsonicClient,
        isPulse: Boolean,
        sectionTitle: String,
    ): List<MediaItem> {
        if (isPulse) {
            val pulseResult = subsonicClient.getPulseRecentlyPlayed(HOME_SECTION_ITEM_LIMIT)
            if (pulseResult !is SubsonicResult.Ok) {
                return emptyList()
            }
            val tracks = pulseResult.value
            val out = ArrayList<MediaItem>(tracks.size)
            val trackCount = tracks.size
            for (index in 0 until trackCount) {
                val track = tracks[index]
                val artistText: String
                if (track.artist == null) {
                    artistText = ""
                } else {
                    artistText = track.artist
                }
                val tile = buildPlayableStub(
                    trackId = track.id,
                    title = track.title,
                    artist = artistText,
                    album = track.album,
                    coverArtId = track.coverArt,
                    subsonicClient = subsonicClient,
                )
                out.add(withGroupTitle(tile, sectionTitle))
            }
            return out
        }
        val albumResult = subsonicClient.getAlbumList2("recent", HOME_SECTION_ITEM_LIMIT)
        if (albumResult !is SubsonicResult.Ok) {
            return emptyList()
        }
        return tagWithGroupTitle(albumsToBrowseable(albumResult.value, subsonicClient), sectionTitle)
    }

    private suspend fun fetchPlaylistsSection(
        subsonicClient: SubsonicClient,
        isPulse: Boolean,
        sectionTitle: String,
    ): List<MediaItem> {
        // On Pulse "Your Playlists" follows the score-ranked order from pulse/topPlaylists,
        // matching the phone Home carousel. Standard servers use getPlaylists order.
        val summariesInDisplayOrder = ArrayList<StandardPlaylistSummary>()
        if (isPulse) {
            val topResult = subsonicClient.getPulseTopPlaylists(HOME_SECTION_ITEM_LIMIT)
            if (topResult !is SubsonicResult.Ok) {
                return emptyList()
            }
            // pulse/topPlaylists now carries the pl-<id> coverArt directly (Pulse PR #34) and
            // its other fields (songCount, duration) match what the tile needs. No cross-
            // reference to /rest/getPlaylists needed any more.
            val ranked = topResult.value
            val rankedCount = ranked.size
            for (index in 0 until rankedCount) {
                summariesInDisplayOrder.add(pulsePlaylistToSummary(ranked[index]))
            }
        } else {
            val result = subsonicClient.getPlaylists()
            if (result !is SubsonicResult.Ok) {
                return emptyList()
            }
            val takeCount: Int
            if (result.value.size < HOME_SECTION_ITEM_LIMIT) {
                takeCount = result.value.size
            } else {
                takeCount = HOME_SECTION_ITEM_LIMIT
            }
            for (index in 0 until takeCount) {
                summariesInDisplayOrder.add(result.value[index])
            }
        }
        val tiles = ArrayList<MediaItem>(summariesInDisplayOrder.size)
        val summaryCount = summariesInDisplayOrder.size
        for (index in 0 until summaryCount) {
            tiles.add(playlistToBrowseable(summariesInDisplayOrder[index], subsonicClient))
        }
        return tagWithGroupTitle(tiles, sectionTitle)
    }

    private fun pulsePlaylistToSummary(pulsePlaylist: PulseTopPlaylist): StandardPlaylistSummary {
        return StandardPlaylistSummary(
            id = pulsePlaylist.id,
            name = pulsePlaylist.name,
            songCount = pulsePlaylist.songCount,
            duration = pulsePlaylist.duration,
            coverArt = pulsePlaylist.coverArt,
        )
    }

    private suspend fun fetchPopularOrFrequentSection(
        subsonicClient: SubsonicClient,
        isPulse: Boolean,
        sectionTitle: String,
    ): List<MediaItem> {
        if (isPulse) {
            val pulseResult = subsonicClient.getPulsePopularArtists(HOME_SECTION_ITEM_LIMIT)
            if (pulseResult !is SubsonicResult.Ok) {
                return emptyList()
            }
            val artists = pulseResult.value
            val out = ArrayList<MediaItem>(artists.size)
            val artistCount = artists.size
            for (index in 0 until artistCount) {
                val popularArtist = artists[index]
                val tile = artistToBrowseable(
                    StandardLibraryArtist(
                        id = popularArtist.id,
                        name = popularArtist.name,
                        albumCount = popularArtist.albumCount,
                        coverArt = popularArtist.coverArt,
                    ),
                    subsonicClient,
                )
                out.add(withGroupTitle(tile, sectionTitle))
            }
            return out
        }
        val albumResult = subsonicClient.getAlbumList2("frequent", HOME_SECTION_ITEM_LIMIT)
        if (albumResult !is SubsonicResult.Ok) {
            return emptyList()
        }
        return tagWithGroupTitle(albumsToBrowseable(albumResult.value, subsonicClient), sectionTitle)
    }

    private suspend fun fetchRecentlyAddedSection(
        subsonicClient: SubsonicClient,
        sectionTitle: String,
    ): List<MediaItem> {
        val albumResult = subsonicClient.getAlbumList2("newest", HOME_SECTION_ITEM_LIMIT)
        if (albumResult !is SubsonicResult.Ok) {
            return emptyList()
        }
        return tagWithGroupTitle(albumsToBrowseable(albumResult.value, subsonicClient), sectionTitle)
    }

    private suspend fun fetchFavoritesSection(
        subsonicClient: SubsonicClient,
        sectionTitle: String,
    ): List<MediaItem> {
        val starredResult = subsonicClient.getStarred2()
        if (starredResult !is SubsonicResult.Ok) {
            return emptyList()
        }
        val starred = starredResult.value
        val out = ArrayList<MediaItem>()
        val albumCount = starred.album.size
        for (index in 0 until albumCount) {
            val album = starred.album[index]
            val coverArtUrl: String?
            val coverArtId = album.coverArt
            if (coverArtId == null) {
                coverArtUrl = null
            } else {
                coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, COVER_ART_REQUEST_SIZE_PX)
            }
            val tile = buildBrowseableItem(
                mediaId = MEDIA_ID_PREFIX_ALBUM + album.id,
                title = album.name,
                subtitle = album.artist,
                artUri = coverArtUrl,
            )
            out.add(withGroupTitle(tile, sectionTitle))
        }
        val starredArtistCount = starred.artist.size
        for (index in 0 until starredArtistCount) {
            val artist = starred.artist[index]
            val tile = artistToBrowseable(
                StandardLibraryArtist(
                    id = artist.id,
                    name = artist.name,
                    albumCount = artist.albumCount,
                    coverArt = artist.coverArt,
                ),
                subsonicClient,
            )
            out.add(withGroupTitle(tile, sectionTitle))
        }
        val starredSongCount = starred.song.size
        for (index in 0 until starredSongCount) {
            val song = starred.song[index]
            val artistText: String
            if (song.artist == null) {
                artistText = ""
            } else {
                artistText = song.artist
            }
            val tile = buildPlayableStub(
                trackId = song.id,
                title = song.title,
                artist = artistText,
                album = song.album,
                coverArtId = song.coverArt,
                subsonicClient = subsonicClient,
            )
            out.add(withGroupTitle(tile, sectionTitle))
        }
        return out
    }


    /**
     * Recents: mixed recently-touched playlists and artists.
     *
     * On Pulse: walk pulse/recentlyPlayed tracks to collect distinct artists in first-seen order,
     * pull recent playlists from pulse/topPlaylists sorted by `lastPlayed`, then stack them
     * (playlists first because the user clicked into them deliberately; artists second as a
     * heard-but-not-chosen tail). Cap the combined list at RECENTS_TOTAL_LIMIT.
     *
     * On standard servers: getAlbumList2?type=recent as the closest proxy.
     */
    private suspend fun buildRecentsChildren(
        subsonicClient: SubsonicClient,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val isPulse = credentialsLoader.loadIsPulseDetected()
        if (!isPulse) {
            val albumResult = subsonicClient.getAlbumList2("recent", RECENTS_FALLBACK_ALBUM_COUNT)
            if (albumResult !is SubsonicResult.Ok) {
                return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
            }
            return LibraryResult.ofItemList(
                albumsToBrowseable(albumResult.value, subsonicClient),
                params,
            )
        }

        val recentTracksResult = subsonicClient.getPulseRecentlyPlayed(RECENTS_TRACK_SCAN_COUNT)
        val orderedRecentArtists = ArrayList<RecentArtistRef>()
        if (recentTracksResult is SubsonicResult.Ok) {
            val seenArtistIds = HashSet<String>()
            val tracks = recentTracksResult.value
            val trackCount = tracks.size
            for (index in 0 until trackCount) {
                val track = tracks[index]
                val artistId = track.artistId
                val artistName = track.artist
                if (artistId == null || artistId.isEmpty()) {
                    continue
                }
                if (artistName == null || artistName.isEmpty()) {
                    continue
                }
                if (seenArtistIds.contains(artistId)) {
                    continue
                }
                seenArtistIds.add(artistId)
                orderedRecentArtists.add(
                    RecentArtistRef(id = artistId, name = artistName, coverArt = track.coverArt)
                )
            }
        }

        val recentPlaylists = ArrayList<StandardPlaylistSummary>()
        val recentPlaylistsResult = subsonicClient.getPulseRecentPlaylists(RECENTS_TOTAL_LIMIT)
        if (recentPlaylistsResult is SubsonicResult.Ok) {
            // pulse/recentPlaylists sorts by lastPlayed and carries the pl-<id> coverArt
            // already, so the tile builder doesn't need to fetch playlist details to produce
            // an image (Pulse PR #34, Flatline #151 + #143).
            val rankedPlaylists = recentPlaylistsResult.value
            val rankedCount = rankedPlaylists.size
            for (index in 0 until rankedCount) {
                recentPlaylists.add(pulsePlaylistToSummary(rankedPlaylists[index]))
            }
        }

        val combined = ImmutableList.builder<MediaItem>()
        var emitted = 0
        val playlistEmitCount = recentPlaylists.size
        for (index in 0 until playlistEmitCount) {
            if (emitted >= RECENTS_TOTAL_LIMIT) {
                break
            }
            combined.add(playlistToBrowseable(recentPlaylists[index], subsonicClient))
            emitted++
        }
        val artistEmitCount = orderedRecentArtists.size
        for (index in 0 until artistEmitCount) {
            if (emitted >= RECENTS_TOTAL_LIMIT) {
                break
            }
            val artist = orderedRecentArtists[index]
            combined.add(
                artistToBrowseable(
                    StandardLibraryArtist(
                        id = artist.id,
                        name = artist.name,
                        albumCount = null,
                        coverArt = artist.coverArt,
                    ),
                    subsonicClient,
                )
            )
            emitted++
        }
        return LibraryResult.ofItemList(combined.build(), params)
    }

    /**
     * Playlists: every playlist, sorted alphabetically by name (case-insensitive).
     */
    private suspend fun buildPlaylistsChildren(
        subsonicClient: SubsonicClient,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val result = subsonicClient.getPlaylists()
        if (result !is SubsonicResult.Ok) {
            return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        }
        val playlists = ArrayList(result.value)
        if (playlists.size > PLAYLISTS_FETCH_LIMIT) {
            playlists.subList(PLAYLISTS_FETCH_LIMIT, playlists.size).clear()
        }
        playlists.sortWith(Comparator { left: StandardPlaylistSummary, right: StandardPlaylistSummary ->
            left.name.compareTo(right.name, ignoreCase = true)
        })
        val tiles = ImmutableList.builder<MediaItem>()
        val playlistCount = playlists.size
        for (index in 0 until playlistCount) {
            tiles.add(playlistToBrowseable(playlists[index], subsonicClient))
        }
        return LibraryResult.ofItemList(tiles.build(), params)
    }

    private suspend fun buildArtistsChildren(
        subsonicClient: SubsonicClient,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val result = subsonicClient.getArtists()
        if (result !is SubsonicResult.Ok) {
            return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        }
        val flattened = ArrayList<StandardLibraryArtist>()
        val indexes = result.value.index
        val indexCount = indexes.size
        for (i in 0 until indexCount) {
            flattened.addAll(indexes[i].artist)
        }
        val out = ImmutableList.builder<MediaItem>()
        val flatCount = flattened.size
        for (i in 0 until flatCount) {
            out.add(artistToBrowseable(flattened[i], subsonicClient))
        }
        return LibraryResult.ofItemList(out.build(), params)
    }

    private suspend fun buildPlaylistChildren(
        subsonicClient: SubsonicClient,
        playlistId: String,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val result = subsonicClient.getPlaylist(playlistId)
        if (result !is SubsonicResult.Ok) {
            return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        }
        val detail: StandardPlaylistDetailPayload = result.value
        val out = ImmutableList.builder<MediaItem>()
        val entryCount = detail.entry.size
        for (i in 0 until entryCount) {
            val song = detail.entry[i]
            val artistText: String
            if (song.artist == null) {
                artistText = ""
            } else {
                artistText = song.artist
            }
            out.add(
                buildPlayableStub(
                    trackId = song.id,
                    title = song.title,
                    artist = artistText,
                    album = song.album,
                    coverArtId = song.coverArt,
                    subsonicClient = subsonicClient,
                )
            )
        }
        return LibraryResult.ofItemList(out.build(), params)
    }

    private suspend fun buildAlbumChildren(
        subsonicClient: SubsonicClient,
        albumId: String,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val result = subsonicClient.getAlbum(albumId)
        if (result !is SubsonicResult.Ok) {
            return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        }
        val detail: StandardAlbumDetailPayload = result.value
        val out = ImmutableList.builder<MediaItem>()
        val songCount = detail.song.size
        for (i in 0 until songCount) {
            val song = detail.song[i]
            val artistText: String
            if (song.artist == null) {
                artistText = ""
            } else {
                artistText = song.artist
            }
            out.add(
                buildPlayableStub(
                    trackId = song.id,
                    title = song.title,
                    artist = artistText,
                    album = detail.name,
                    coverArtId = song.coverArt,
                    subsonicClient = subsonicClient,
                )
            )
        }
        return LibraryResult.ofItemList(out.build(), params)
    }

    private suspend fun buildArtistChildren(
        subsonicClient: SubsonicClient,
        artistId: String,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val result = subsonicClient.getArtist(artistId)
        if (result !is SubsonicResult.Ok) {
            return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        }
        val detail: StandardArtistDetailPayload = result.value
        val out = ImmutableList.builder<MediaItem>()
        val albumCount = detail.album.size
        for (i in 0 until albumCount) {
            val album = detail.album[i]
            val coverArtUrl: String?
            val coverArtId = album.coverArt
            if (coverArtId == null) {
                coverArtUrl = null
            } else {
                coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, COVER_ART_REQUEST_SIZE_PX)
            }
            out.add(
                buildBrowseableItem(
                    mediaId = MEDIA_ID_PREFIX_ALBUM + album.id,
                    title = album.name,
                    subtitle = detail.name,
                    artUri = coverArtUrl,
                )
            )
        }
        return LibraryResult.ofItemList(out.build(), params)
    }

    private fun albumsToBrowseable(
        albums: List<StandardAlbumSummary>,
        subsonicClient: SubsonicClient,
    ): ImmutableList<MediaItem> {
        val out = ImmutableList.builder<MediaItem>()
        val albumCount = albums.size
        for (i in 0 until albumCount) {
            val album = albums[i]
            val coverArtUrl: String?
            val coverArtId = album.coverArt
            if (coverArtId == null) {
                coverArtUrl = null
            } else {
                coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, COVER_ART_REQUEST_SIZE_PX)
            }
            out.add(
                buildBrowseableItem(
                    mediaId = MEDIA_ID_PREFIX_ALBUM + album.id,
                    title = album.name,
                    subtitle = album.artist,
                    artUri = coverArtUrl,
                )
            )
        }
        return out.build()
    }

    /**
     * Build the browseable tile for a playlist.
     *
     * All Pulse playlist endpoints (/rest/getPlaylists, /rest/getPlaylist, pulse/topPlaylists,
     * pulse/recentPlaylists) carry the pl-<id> server-generated composite in `coverArt`. Other
     * OpenSubsonic servers that don't populate `coverArt` get a blank tile until they expose
     * their own composite mechanism.
     */
    private fun playlistToBrowseable(
        playlist: StandardPlaylistSummary,
        subsonicClient: SubsonicClient,
    ): MediaItem {
        val coverArtUrl: String?
        val coverArtId = playlist.coverArt
        if (coverArtId == null) {
            coverArtUrl = null
        } else {
            coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, COVER_ART_REQUEST_SIZE_PX)
        }
        return buildBrowseableItem(
            mediaId = MEDIA_ID_PREFIX_PLAYLIST + playlist.id,
            title = playlist.name,
            subtitle = null,
            artUri = coverArtUrl,
        )
    }

    private fun artistToBrowseable(
        artist: StandardLibraryArtist,
        subsonicClient: SubsonicClient,
    ): MediaItem {
        val coverArtUrl: String?
        val coverArtId = artist.coverArt
        if (coverArtId == null) {
            coverArtUrl = null
        } else {
            coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, COVER_ART_REQUEST_SIZE_PX)
        }
        return buildBrowseableItem(
            mediaId = MEDIA_ID_PREFIX_ARTIST + artist.id,
            title = artist.name,
            subtitle = null,
            artUri = coverArtUrl,
        )
    }

    private fun buildPlayableStub(
        trackId: String,
        title: String,
        artist: String,
        album: String?,
        coverArtId: String?,
        subsonicClient: SubsonicClient,
    ): MediaItem {
        val coverArtUrl: String?
        if (coverArtId == null) {
            coverArtUrl = null
        } else {
            coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, COVER_ART_REQUEST_SIZE_PX)
        }
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        if (album != null) {
            metadataBuilder.setAlbumTitle(album)
        }
        if (coverArtUrl != null) {
            metadataBuilder.setArtworkUri(android.net.Uri.parse(coverArtUrl))
        }
        return MediaItem.Builder()
            .setMediaId(MEDIA_ID_PREFIX_TRACK + trackId)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun buildPlayableMediaItem(
        subsonicClient: SubsonicClient,
        trackId: String,
        passedThroughMetadata: MediaMetadata,
    ): MediaItem {
        val streamUrl = subsonicClient.buildStreamUrl(trackId)
        return MediaItem.Builder()
            .setMediaId(MEDIA_ID_PREFIX_TRACK + trackId)
            .setUri(streamUrl)
            .setMediaMetadata(passedThroughMetadata)
            .build()
    }

    private fun buildBrowseableItem(
        mediaId: String,
        title: String,
    ): MediaItem {
        return buildBrowseableItem(mediaId = mediaId, title = title, subtitle = null, artUri = null)
    }

    private fun buildBrowseableItem(
        mediaId: String,
        title: String,
        subtitle: String?,
        artUri: String?,
    ): MediaItem {
        val contentStyleExtras = android.os.Bundle()
        contentStyleExtras.putInt(CONTENT_STYLE_BROWSABLE_HINT_KEY, CONTENT_STYLE_GRID_ITEM)
        contentStyleExtras.putInt(CONTENT_STYLE_PLAYABLE_HINT_KEY, CONTENT_STYLE_LIST_ITEM)
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setExtras(contentStyleExtras)
        if (subtitle != null) {
            metadataBuilder.setSubtitle(subtitle)
        }
        if (artUri != null) {
            metadataBuilder.setArtworkUri(android.net.Uri.parse(artUri))
        }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(metadataBuilder.build())
            .build()
    }

    private fun stubForMediaId(mediaId: String): MediaItem {
        if (mediaId.startsWith(MEDIA_ID_PREFIX_TRACK)) {
            return MediaItem.Builder()
                .setMediaId(mediaId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
        return buildBrowseableItem(mediaId = mediaId, title = mediaId)
    }

    private fun trackIdFromMediaId(mediaId: String): String? {
        if (mediaId.startsWith(MEDIA_ID_PREFIX_TRACK)) {
            return mediaId.removePrefix(MEDIA_ID_PREFIX_TRACK)
        }
        return null
    }

    /**
     * Returns a new MediaItem identical to [item] except the section's group title is merged
     * into its MediaMetadata extras. Auto reads this and renders a section header above the
     * first item that carries it.
     */
    private fun withGroupTitle(item: MediaItem, sectionTitle: String): MediaItem {
        val newExtras = android.os.Bundle()
        val existingExtras = item.mediaMetadata.extras
        if (existingExtras != null) {
            newExtras.putAll(existingExtras)
        }
        newExtras.putString(CONTENT_STYLE_GROUP_TITLE_HINT_KEY, sectionTitle)
        val newMetadata = item.mediaMetadata.buildUpon().setExtras(newExtras).build()
        return item.buildUpon().setMediaMetadata(newMetadata).build()
    }

    private fun tagWithGroupTitle(items: List<MediaItem>, sectionTitle: String): List<MediaItem> {
        val out = ArrayList<MediaItem>(items.size)
        val itemCount = items.size
        for (index in 0 until itemCount) {
            out.add(withGroupTitle(items[index], sectionTitle))
        }
        return out
    }

    /**
     * Local helper bag for the recents builder. Carries the minimum we need to render an artist
     * row (id, name, optional cover) without making a getArtist call per recent track.
     */
    private data class RecentArtistRef(
        val id: String,
        val name: String,
        val coverArt: String?,
    )
}
