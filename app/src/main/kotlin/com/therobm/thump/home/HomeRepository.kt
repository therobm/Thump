package com.therobm.thump.home

import com.therobm.thump.subsonic.PulsePopularArtist
import com.therobm.thump.subsonic.PulseRecentlyPlayedTrack
import com.therobm.thump.subsonic.PulseTopPlaylist
import com.therobm.thump.subsonic.StandardAlbumSummary
import com.therobm.thump.subsonic.StandardPlaylistSummary
import com.therobm.thump.subsonic.StandardStarred2Payload
import com.therobm.thump.subsonic.StandardStarredArtist
import com.therobm.thump.subsonic.StandardStarredSong
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicResult

/**
 * Loads the five home-screen carousels from whatever server flavor the client is talking to.
 *
 * Sources differ per server: a Pulse server gets the personalized Pulse endpoints for the first
 * three sections, a vanilla OpenSubsonic server gets the corresponding standard fallbacks. The
 * last two sections (Recently Added albums, Favorites) come from standard endpoints either way.
 *
 * Each section is fetched and mapped independently so a partial failure renders the rest of the
 * screen instead of blanking it.
 */
class HomeRepository(
    private val subsonicClient: SubsonicClient,
    private val isPulseServer: Boolean,
) {

    /**
     * Fetch one section. Returns a HomeSection ready to drop into the UI state.
     *
     * Callers run these in parallel via launches on their own coroutine scope. The repository
     * does not couple the fetches together so a slow endpoint cannot starve a fast one.
     */
    suspend fun loadSection(key: HomeSectionKey): HomeSection {
        when (key) {
            HomeSectionKey.RecentlyPlayed -> {
                return loadRecentlyPlayedSection()
            }
            HomeSectionKey.Playlists -> {
                return loadPlaylistsSection()
            }
            HomeSectionKey.PopularOrFrequent -> {
                return loadPopularOrFrequentSection()
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
        if (isPulseServer) {
            val result = subsonicClient.getPulseRecentlyPlayed(HOME_CAROUSEL_FETCH_LIMIT)
            return buildSectionFromResult(
                key = HomeSectionKey.RecentlyPlayed,
                title = "Recently Played",
                result = result,
                mapItems = { tracks: List<PulseRecentlyPlayedTrack> ->
                    mapPulseTracks(tracks)
                },
            )
        }
        val result = subsonicClient.getAlbumList2("recent", HOME_CAROUSEL_FETCH_LIMIT)
        return buildSectionFromResult(
            key = HomeSectionKey.RecentlyPlayed,
            title = "Recently Played",
            result = result,
            mapItems = { albums: List<StandardAlbumSummary> ->
                mapStandardAlbums(albums)
            },
        )
    }

    private suspend fun loadPlaylistsSection(): HomeSection {
        if (isPulseServer) {
            val result = subsonicClient.getPulseTopPlaylists(HOME_CAROUSEL_FETCH_LIMIT)
            return buildSectionFromResult(
                key = HomeSectionKey.Playlists,
                title = "Your Playlists",
                result = result,
                mapItems = { playlists: List<PulseTopPlaylist> ->
                    mapPulsePlaylists(playlists)
                },
            )
        }
        val result = subsonicClient.getPlaylists()
        return buildSectionFromResult(
            key = HomeSectionKey.Playlists,
            title = "Playlists",
            result = result,
            mapItems = { playlists: List<StandardPlaylistSummary> ->
                mapStandardPlaylists(playlists)
            },
        )
    }

    private suspend fun loadPopularOrFrequentSection(): HomeSection {
        if (isPulseServer) {
            val result = subsonicClient.getPulsePopularArtists(HOME_CAROUSEL_FETCH_LIMIT)
            return buildSectionFromResult(
                key = HomeSectionKey.PopularOrFrequent,
                title = "Popular Artists",
                result = result,
                mapItems = { artists: List<PulsePopularArtist> ->
                    mapPulseArtists(artists)
                },
            )
        }
        val result = subsonicClient.getAlbumList2("frequent", HOME_CAROUSEL_FETCH_LIMIT)
        return buildSectionFromResult(
            key = HomeSectionKey.PopularOrFrequent,
            title = "Most Played",
            result = result,
            mapItems = { albums: List<StandardAlbumSummary> ->
                mapStandardAlbums(albums)
            },
        )
    }

    private suspend fun loadRecentlyAddedSection(): HomeSection {
        val result = subsonicClient.getAlbumList2("newest", HOME_CAROUSEL_FETCH_LIMIT)
        return buildSectionFromResult(
            key = HomeSectionKey.RecentlyAdded,
            title = "Recently Added",
            result = result,
            mapItems = { albums: List<StandardAlbumSummary> ->
                mapStandardAlbums(albums)
            },
        )
    }

    private suspend fun loadFavoritesSection(): HomeSection {
        val result = subsonicClient.getStarred2()
        return buildSectionFromResult(
            key = HomeSectionKey.Favorites,
            title = "Favorites",
            result = result,
            mapItems = { starred: StandardStarred2Payload ->
                mapStarredMix(starred)
            },
        )
    }

    private fun <P> buildSectionFromResult(
        key: HomeSectionKey,
        title: String,
        result: SubsonicResult<P>,
        mapItems: (P) -> List<HomeCarouselItem>,
    ): HomeSection {
        val loadState: HomeSectionLoadState
        when (result) {
            is SubsonicResult.Ok -> {
                loadState = HomeSectionLoadState.Loaded(mapItems(result.value))
            }
            is SubsonicResult.ServerError -> {
                loadState = HomeSectionLoadState.Failed(
                    "Server error " + result.code + ": " + result.message
                )
            }
            is SubsonicResult.TransportError -> {
                loadState = HomeSectionLoadState.Failed(
                    "Network error: " + result.cause.javaClass.simpleName
                )
            }
            is SubsonicResult.MalformedResponse -> {
                loadState = HomeSectionLoadState.Failed(
                    "Bad response: " + result.cause.javaClass.simpleName
                )
            }
        }
        return HomeSection(key = key, title = title, loadState = loadState)
    }

    private fun mapPulseTracks(tracks: List<PulseRecentlyPlayedTrack>): List<HomeCarouselItem> {
        val mapped = ArrayList<HomeCarouselItem>(tracks.size)
        val trackCount = tracks.size
        for (trackIndex in 0 until trackCount) {
            val track = tracks[trackIndex]
            mapped.add(
                HomeCarouselItem(
                    id = track.id,
                    kind = HomeItemKind.Track,
                    title = track.title,
                    subtitle = textOrEmpty(track.artist),
                    coverArtId = track.coverArt,
                )
            )
        }
        return mapped
    }

    private fun mapPulseArtists(artists: List<PulsePopularArtist>): List<HomeCarouselItem> {
        val mapped = ArrayList<HomeCarouselItem>(artists.size)
        val artistCount = artists.size
        for (artistIndex in 0 until artistCount) {
            val artist = artists[artistIndex]
            val subtitle: String
            if (artist.albumCount == null) {
                subtitle = ""
            } else {
                subtitle = artist.albumCount.toString() + " albums"
            }
            mapped.add(
                HomeCarouselItem(
                    id = artist.id,
                    kind = HomeItemKind.Artist,
                    title = artist.name,
                    subtitle = subtitle,
                    coverArtId = artist.coverArt,
                )
            )
        }
        return mapped
    }

    private fun mapPulsePlaylists(playlists: List<PulseTopPlaylist>): List<HomeCarouselItem> {
        val mapped = ArrayList<HomeCarouselItem>(playlists.size)
        val playlistCount = playlists.size
        for (playlistIndex in 0 until playlistCount) {
            val playlist = playlists[playlistIndex]
            val subtitle: String
            if (playlist.songCount == null) {
                subtitle = ""
            } else {
                subtitle = playlist.songCount.toString() + " tracks"
            }
            // pulse/topPlaylists carries the pl-<id> composite coverArt directly (Pulse PR
             // #34), so consume it as-is. The phone carousel tile builder feeds whatever
             // coverArtId we hand it into getCoverArt.
            mapped.add(
                HomeCarouselItem(
                    id = playlist.id,
                    kind = HomeItemKind.Playlist,
                    title = playlist.name,
                    subtitle = subtitle,
                    coverArtId = playlist.coverArt,
                )
            )
        }
        return mapped
    }

    private fun mapStandardAlbums(albums: List<StandardAlbumSummary>): List<HomeCarouselItem> {
        val mapped = ArrayList<HomeCarouselItem>(albums.size)
        val albumCount = albums.size
        for (albumIndex in 0 until albumCount) {
            val album = albums[albumIndex]
            mapped.add(
                HomeCarouselItem(
                    id = album.id,
                    kind = HomeItemKind.Album,
                    title = album.name,
                    subtitle = textOrEmpty(album.artist),
                    coverArtId = album.coverArt,
                )
            )
        }
        return mapped
    }

    private fun mapStandardPlaylists(playlists: List<StandardPlaylistSummary>): List<HomeCarouselItem> {
        val mapped = ArrayList<HomeCarouselItem>(playlists.size)
        val playlistCount = playlists.size
        for (playlistIndex in 0 until playlistCount) {
            val playlist = playlists[playlistIndex]
            val subtitle: String
            if (playlist.songCount == null) {
                subtitle = ""
            } else {
                subtitle = playlist.songCount.toString() + " tracks"
            }
            mapped.add(
                HomeCarouselItem(
                    id = playlist.id,
                    kind = HomeItemKind.Playlist,
                    title = playlist.name,
                    subtitle = subtitle,
                    coverArtId = playlist.coverArt,
                )
            )
        }
        return mapped
    }

    private fun mapStarredMix(starred: StandardStarred2Payload): List<HomeCarouselItem> {
        val mapped = ArrayList<HomeCarouselItem>()
        val albumCount = starred.album.size
        for (albumIndex in 0 until albumCount) {
            val album = starred.album[albumIndex]
            mapped.add(
                HomeCarouselItem(
                    id = album.id,
                    kind = HomeItemKind.Album,
                    title = album.name,
                    subtitle = textOrEmpty(album.artist),
                    coverArtId = album.coverArt,
                )
            )
        }
        val artistCount = starred.artist.size
        for (artistIndex in 0 until artistCount) {
            val artist: StandardStarredArtist = starred.artist[artistIndex]
            val subtitle: String
            if (artist.albumCount == null) {
                subtitle = ""
            } else {
                subtitle = artist.albumCount.toString() + " albums"
            }
            mapped.add(
                HomeCarouselItem(
                    id = artist.id,
                    kind = HomeItemKind.Artist,
                    title = artist.name,
                    subtitle = subtitle,
                    coverArtId = artist.coverArt,
                )
            )
        }
        val songCount = starred.song.size
        for (songIndex in 0 until songCount) {
            val song: StandardStarredSong = starred.song[songIndex]
            mapped.add(
                HomeCarouselItem(
                    id = song.id,
                    kind = HomeItemKind.Track,
                    title = song.title,
                    subtitle = textOrEmpty(song.artist),
                    coverArtId = song.coverArt,
                )
            )
        }
        return mapped
    }

    private fun textOrEmpty(input: String?): String {
        if (input == null) {
            return ""
        }
        return input
    }

    companion object {
        const val HOME_CAROUSEL_FETCH_LIMIT: Int = 20
    }
}
