package com.therobm.thump.playback

/**
 * Where the currently-loaded queue came from.
 *
 * Lets the Now Playing screen render the "Playing from {kind}: {name}" header so the user
 * always sees the context they entered playback from. Null when a queue was started from an
 * ad-hoc tap (e.g. a single track tile in a home carousel) where there is no container.
 */
data class PlaybackSource(
    val kind: PlaybackSourceKind,
    val name: String,
)

enum class PlaybackSourceKind {
    Album,
    Playlist,
    Artist,
}
