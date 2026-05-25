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
import kotlinx.coroutines.guava.future

private const val MEDIA_ID_ROOT: String = "thump-root"
private const val MEDIA_ID_RECENTLY: String = "thump-root/recently"
private const val MEDIA_ID_PLAYLISTS: String = "thump-root/playlists"
private const val MEDIA_ID_ALBUMS: String = "thump-root/albums"
private const val MEDIA_ID_ARTISTS: String = "thump-root/artists"
private const val MEDIA_ID_PREFIX_PLAYLIST: String = "thump-root/playlist/"
private const val MEDIA_ID_PREFIX_ALBUM: String = "thump-root/album/"
private const val MEDIA_ID_PREFIX_ARTIST: String = "thump-root/artist/"
private const val MEDIA_ID_PREFIX_TRACK: String = "thump-track/"

private const val HOME_RECENTS_COUNT: Int = 40
private const val LIBRARY_PAGE_SIZE: Int = 200
private const val COVER_ART_REQUEST_SIZE_PX: Int = 400

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
            if (parentId == MEDIA_ID_RECENTLY) {
                return@future buildRecentlyPlayedChildren(subsonicClient, params)
            }
            if (parentId == MEDIA_ID_PLAYLISTS) {
                return@future buildPlaylistsChildren(subsonicClient, params)
            }
            if (parentId == MEDIA_ID_ALBUMS) {
                return@future buildAlbumsChildren(subsonicClient, params)
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
        children.add(buildBrowseableItem(MEDIA_ID_RECENTLY, "Recently Played"))
        children.add(buildBrowseableItem(MEDIA_ID_PLAYLISTS, "Playlists"))
        children.add(buildBrowseableItem(MEDIA_ID_ALBUMS, "Albums"))
        children.add(buildBrowseableItem(MEDIA_ID_ARTISTS, "Artists"))
        return children.build()
    }

    private suspend fun buildRecentlyPlayedChildren(
        subsonicClient: SubsonicClient,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val isPulse = credentialsLoader.loadIsPulseDetected()
        if (isPulse) {
            val pulseResult = subsonicClient.getPulseRecentlyPlayed(HOME_RECENTS_COUNT)
            if (pulseResult !is SubsonicResult.Ok) {
                return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
            }
            val tracks = pulseResult.value
            val out = ImmutableList.builder<MediaItem>()
            val trackCount = tracks.size
            for (index in 0 until trackCount) {
                val track = tracks[index]
                val artistText: String
                if (track.artist == null) {
                    artistText = ""
                } else {
                    artistText = track.artist
                }
                out.add(
                    buildPlayableStub(
                        trackId = track.id,
                        title = track.title,
                        artist = artistText,
                        album = track.album,
                        coverArtId = track.coverArt,
                        subsonicClient = subsonicClient,
                    )
                )
            }
            return LibraryResult.ofItemList(out.build(), params)
        }
        // Standard fallback: recent albums (no per-track recents endpoint exists in vanilla
        // Subsonic, so the Auto user browses by recently-added album instead).
        val albumResult = subsonicClient.getAlbumList2("recent", HOME_RECENTS_COUNT)
        if (albumResult !is SubsonicResult.Ok) {
            return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        }
        return LibraryResult.ofItemList(albumsToBrowseable(albumResult.value, subsonicClient), params)
    }

    private suspend fun buildPlaylistsChildren(
        subsonicClient: SubsonicClient,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val result = subsonicClient.getPlaylists()
        if (result !is SubsonicResult.Ok) {
            return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        }
        val playlists = result.value
        val out = ImmutableList.builder<MediaItem>()
        val playlistCount = playlists.size
        for (index in 0 until playlistCount) {
            val playlist: StandardPlaylistSummary = playlists[index]
            out.add(playlistToBrowseable(playlist, subsonicClient))
        }
        return LibraryResult.ofItemList(out.build(), params)
    }

    private suspend fun buildAlbumsChildren(
        subsonicClient: SubsonicClient,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val result = subsonicClient.getAlbumList2("alphabeticalByName", LIBRARY_PAGE_SIZE)
        if (result !is SubsonicResult.Ok) {
            return LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params)
        }
        return LibraryResult.ofItemList(albumsToBrowseable(result.value, subsonicClient), params)
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
        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
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
}
