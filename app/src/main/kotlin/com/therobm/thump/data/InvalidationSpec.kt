package com.therobm.thump.data

/**
 * Describes which slice of the local cache the caller wants forgotten. Mostly used by the
 * Settings screen ("clear cache") and by background invalidations the app triggers when it
 * knows the server has changed. The IProtocol layer never sees this — it only describes
 * ThumpData's local storage.
 */
sealed class InvalidationSpec {
    object EverythingMetadata : InvalidationSpec()
    object EverythingBlobs : InvalidationSpec()
    object Everything : InvalidationSpec()
    data class HomeSections(val sectionKey: String) : InvalidationSpec()
    data class Track(val trackId: String) : InvalidationSpec()
    data class Album(val albumId: String) : InvalidationSpec()
    data class Artist(val artistId: String) : InvalidationSpec()
    data class Playlist(val playlistId: String) : InvalidationSpec()
}
