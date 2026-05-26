package com.therobm.thump.data

/**
 * Sort options for `getAllAlbums`. Maps to the server's `getAlbumList2` type values; the
 * IProtocol implementations translate the enum into the wire string they need.
 */
enum class AlbumSort {
    AlphabeticalByName,
    AlphabeticalByArtist,
    Newest,
    Recent,
    Frequent,
    Random,
}
