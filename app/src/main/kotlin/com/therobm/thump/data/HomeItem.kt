package com.therobm.thump.data

/**
 * A single tile in one of the mixed-type home shelves. The active IProtocol decides which
 * variant it returns for any given call; the home screen renders whatever's in the list.
 *
 * SubsonicProtocol returns AlbumItem / PlaylistItem substitutions when its standard endpoints
 * cannot deliver the literal intent (e.g. "popular artists" backed by getAlbumList2(frequent)).
 */
sealed class HomeItem {
    data class TrackItem(val track: Track) : HomeItem()
    data class ArtistItem(val artist: Artist) : HomeItem()
    data class AlbumItem(val album: Album) : HomeItem()
    data class PlaylistItem(val playlist: Playlist) : HomeItem()
}
