package com.therobm.thump.data

/**
 * Kinds the caller can ask `getRecentlyPlayed` to return. Pulse honours this via the
 * `pulse/recentlyPlayed?types=` query param (currently being added in Flatline bug #223).
 * SubsonicProtocol ignores it and returns whatever its fallback endpoint yields.
 */
enum class HomeItemKind {
    Track,
    Artist,
    Album,
    Playlist,
}
