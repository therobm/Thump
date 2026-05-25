package com.therobm.thump.home

/**
 * The kind of media a carousel item represents.
 *
 * Used to render the right tile shape (circle for artists, rounded square for everything else)
 * and to decide what to do on tap once detail screens exist.
 */
enum class HomeItemKind {
    Track,
    Album,
    Artist,
    Playlist,
}

/**
 * A single tile inside a home-screen carousel.
 *
 * Carries only the fields the carousel needs to render. Detail screens fetch the full record
 * by id on demand rather than relying on data passed through this lightweight type.
 */
data class HomeCarouselItem(
    val id: String,
    val kind: HomeItemKind,
    val title: String,
    val subtitle: String,
    val coverArtId: String?,
)

/**
 * One section on the home screen — a title and the items below it.
 *
 * The loadState lets each section render independently: one section's network failure must not
 * blank the others, and the section can show a spinner while its fetch is in flight.
 */
data class HomeSection(
    val key: HomeSectionKey,
    val title: String,
    val loadState: HomeSectionLoadState,
)

enum class HomeSectionKey {
    RecentlyPlayed,
    Playlists,
    PopularOrFrequent,
    RecentlyAdded,
    Favorites,
}

sealed interface HomeSectionLoadState {
    object Loading : HomeSectionLoadState
    data class Loaded(val items: List<HomeCarouselItem>) : HomeSectionLoadState
    data class Failed(val message: String) : HomeSectionLoadState
}

/**
 * The complete state the HomeScreen renders from.
 */
data class HomeUiState(
    val isPulseServer: Boolean,
    val sections: List<HomeSection>,
)
