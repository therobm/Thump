package com.therobm.thump.home

import com.therobm.thump.data.Album
import com.therobm.thump.data.AlbumSort
import com.therobm.thump.data.Artist
import com.therobm.thump.data.HomeItem
import com.therobm.thump.data.Playlist
import com.therobm.thump.data.StarredCollection
import com.therobm.thump.data.ThumpData
import com.therobm.thump.data.ThumpDataNotConfigured
import com.therobm.thump.data.Track
import java.io.IOException
import com.therobm.thump.data.HomeItemKind as DataHomeItemKind

/**
 * Maps ThumpData's home-shelf calls into the screen's HomeCarouselItem shape. One call per
 * section; ThumpData hides the Pulse-vs-Subsonic branching behind the active IProtocol so the
 * repository (and the screen above it) no longer knows or cares which server is in play.
 *
 * Sections fetched independently by the screen so a slow shelf cannot starve a fast one; this
 * class only translates one shelf at a time.
 */
class HomeRepository(
    private val thumpData: ThumpData,
) {

    suspend fun loadSection(key: HomeSectionKey): HomeSection {
        when (key) {
            HomeSectionKey.RecentlyPlayed -> {
                return loadRecentlyPlayedSection()
            }
            HomeSectionKey.Playlists -> {
                return loadTopPlaylistsSection()
            }
            HomeSectionKey.PopularOrFrequent -> {
                return loadPopularArtistsSection()
            }
            HomeSectionKey.RecentlyAdded -> {
                return loadRecentlyAddedSection()
            }
            HomeSectionKey.Favorites -> {
                return loadFavoritesSection()
            }
        }
    }

    private suspend fun loadRecentlyPlayedSection(): HomeSection {
        val items: List<HomeItem>
        try {
            items = thumpData.getRecentlyPlayed(
                limit = HOME_CAROUSEL_FETCH_LIMIT,
                types = ALL_HOME_ITEM_KINDS,
            )
        } catch (notConfigured: ThumpDataNotConfigured) {
            return HomeSection(
                key = HomeSectionKey.RecentlyPlayed,
                title = "Recently Played",
                loadState = HomeSectionLoadState.Failed(NO_SERVER_CONFIGURED_MESSAGE),
            )
        }
        return HomeSection(
            key = HomeSectionKey.RecentlyPlayed,
            title = "Recently Played",
            loadState = HomeSectionLoadState.Loaded(mapHomeItems(items)),
        )
    }

    private suspend fun loadTopPlaylistsSection(): HomeSection {
        val items: List<HomeItem>
        try {
            items = thumpData.getTopPlaylists(HOME_CAROUSEL_FETCH_LIMIT)
        } catch (notConfigured: ThumpDataNotConfigured) {
            return HomeSection(
                key = HomeSectionKey.Playlists,
                title = "Your Playlists",
                loadState = HomeSectionLoadState.Failed(NO_SERVER_CONFIGURED_MESSAGE),
            )
        }
        return HomeSection(
            key = HomeSectionKey.Playlists,
            title = "Your Playlists",
            loadState = HomeSectionLoadState.Loaded(mapHomeItems(items)),
        )
    }

    private suspend fun loadPopularArtistsSection(): HomeSection {
        val items: List<HomeItem>
        try {
            items = thumpData.getPopularArtists(HOME_CAROUSEL_FETCH_LIMIT)
        } catch (notConfigured: ThumpDataNotConfigured) {
            return HomeSection(
                key = HomeSectionKey.PopularOrFrequent,
                title = "Popular Artists",
                loadState = HomeSectionLoadState.Failed(NO_SERVER_CONFIGURED_MESSAGE),
            )
        }
        return HomeSection(
            key = HomeSectionKey.PopularOrFrequent,
            title = "Popular Artists",
            loadState = HomeSectionLoadState.Loaded(mapHomeItems(items)),
        )
    }

    private suspend fun loadRecentlyAddedSection(): HomeSection {
        val albums: List<Album>
        try {
            albums = thumpData.getAllAlbums(
                sort = AlbumSort.Newest,
                limit = HOME_CAROUSEL_FETCH_LIMIT,
                offset = 0,
            )
        } catch (notConfigured: ThumpDataNotConfigured) {
            return HomeSection(
                key = HomeSectionKey.RecentlyAdded,
                title = "Recently Added",
                loadState = HomeSectionLoadState.Failed(NO_SERVER_CONFIGURED_MESSAGE),
            )
        }
        val mapped: ArrayList<HomeCarouselItem> = ArrayList<HomeCarouselItem>(albums.size)
        val albumCount: Int = albums.size
        for (albumIndex in 0 until albumCount) {
            mapped.add(mapAlbumToCarouselItem(albums[albumIndex]))
        }
        return HomeSection(
            key = HomeSectionKey.RecentlyAdded,
            title = "Recently Added",
            loadState = HomeSectionLoadState.Loaded(mapped),
        )
    }

    private suspend fun loadFavoritesSection(): HomeSection {
        val starred: StarredCollection
        try {
            starred = thumpData.getStarred()
        } catch (notConfigured: ThumpDataNotConfigured) {
            return HomeSection(
                key = HomeSectionKey.Favorites,
                title = "Favorites",
                loadState = HomeSectionLoadState.Failed(NO_SERVER_CONFIGURED_MESSAGE),
            )
        } catch (loadFailure: IOException) {
            return HomeSection(
                key = HomeSectionKey.Favorites,
                title = "Favorites",
                loadState = HomeSectionLoadState.Failed("Network error"),
            )
        }
        val totalSize: Int = starred.albums.size + starred.artists.size + starred.tracks.size
        val mapped: ArrayList<HomeCarouselItem> = ArrayList<HomeCarouselItem>(totalSize)
        val albumCount: Int = starred.albums.size
        for (albumIndex in 0 until albumCount) {
            mapped.add(mapAlbumToCarouselItem(starred.albums[albumIndex]))
        }
        val artistCount: Int = starred.artists.size
        for (artistIndex in 0 until artistCount) {
            mapped.add(mapArtistToCarouselItem(starred.artists[artistIndex]))
        }
        val trackCount: Int = starred.tracks.size
        for (trackIndex in 0 until trackCount) {
            mapped.add(mapTrackToCarouselItem(starred.tracks[trackIndex]))
        }
        return HomeSection(
            key = HomeSectionKey.Favorites,
            title = "Favorites",
            loadState = HomeSectionLoadState.Loaded(mapped),
        )
    }

    private fun mapHomeItems(items: List<HomeItem>): List<HomeCarouselItem> {
        val mapped: ArrayList<HomeCarouselItem> = ArrayList<HomeCarouselItem>(items.size)
        val itemCount: Int = items.size
        for (itemIndex in 0 until itemCount) {
            val current: HomeItem = items[itemIndex]
            when (current) {
                is HomeItem.TrackItem -> {
                    mapped.add(mapTrackToCarouselItem(current.track))
                }
                is HomeItem.ArtistItem -> {
                    mapped.add(mapArtistToCarouselItem(current.artist))
                }
                is HomeItem.AlbumItem -> {
                    mapped.add(mapAlbumToCarouselItem(current.album))
                }
                is HomeItem.PlaylistItem -> {
                    mapped.add(mapPlaylistToCarouselItem(current.playlist))
                }
            }
        }
        return mapped
    }

    private fun mapTrackToCarouselItem(track: Track): HomeCarouselItem {
        return HomeCarouselItem(
            id = track.trackId,
            kind = HomeItemKind.Track,
            title = track.title,
            subtitle = textOrEmpty(track.artistName),
            coverArtId = track.coverArtId,
        )
    }

    private fun mapArtistToCarouselItem(artist: Artist): HomeCarouselItem {
        val subtitle: String
        if (artist.albumCount > 0) {
            subtitle = artist.albumCount.toString() + " albums"
        } else {
            subtitle = ""
        }
        return HomeCarouselItem(
            id = artist.artistId,
            kind = HomeItemKind.Artist,
            title = artist.name,
            subtitle = subtitle,
            coverArtId = artist.coverArtId,
        )
    }

    private fun mapAlbumToCarouselItem(album: Album): HomeCarouselItem {
        return HomeCarouselItem(
            id = album.albumId,
            kind = HomeItemKind.Album,
            title = album.name,
            subtitle = textOrEmpty(album.artistName),
            coverArtId = album.coverArtId,
        )
    }

    private fun mapPlaylistToCarouselItem(playlist: Playlist): HomeCarouselItem {
        val subtitle: String
        if (playlist.songCount == null) {
            subtitle = ""
        } else {
            subtitle = playlist.songCount.toString() + " tracks"
        }
        return HomeCarouselItem(
            id = playlist.playlistId,
            kind = HomeItemKind.Playlist,
            title = playlist.name,
            subtitle = subtitle,
            coverArtId = playlist.coverArtId,
        )
    }

    private fun textOrEmpty(input: String?): String {
        if (input == null) {
            return ""
        }
        return input
    }

    companion object {
        const val HOME_CAROUSEL_FETCH_LIMIT: Int = 20
        private const val NO_SERVER_CONFIGURED_MESSAGE: String = "No server configured"
        private val ALL_HOME_ITEM_KINDS: Set<DataHomeItemKind> = setOf<DataHomeItemKind>(
            DataHomeItemKind.Track,
            DataHomeItemKind.Artist,
            DataHomeItemKind.Album,
            DataHomeItemKind.Playlist,
        )
    }
}
