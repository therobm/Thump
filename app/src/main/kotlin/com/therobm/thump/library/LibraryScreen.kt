package com.therobm.thump.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.therobm.thump.ThumpColors
import com.therobm.thump.art.CompositeArtTile
import com.therobm.thump.subsonic.StandardAlbumSummary
import com.therobm.thump.subsonic.StandardArtistsPayload
import com.therobm.thump.subsonic.StandardGenre
import com.therobm.thump.subsonic.StandardLibraryArtist
import com.therobm.thump.subsonic.StandardPlaylistSummary
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicResult

private const val ALBUM_LIST_PAGE_SIZE: Int = 500
private const val ROW_ART_REQUEST_SIZE_PX: Int = 150
private const val PLAYLIST_ROW_ART_REQUEST_SIZE_PX: Int = 120
private const val ROW_THUMB_SIZE_DP: Int = 56

/**
 * The Library tab. Chip row at the top picks between Artists, Albums, Playlists, and Genres;
 * the selected chip drives the list below. Each tab's data loads lazily on first selection
 * and stays cached for the lifetime of the screen.
 *
 * Skipped for this iteration (will come in follow-ups): alphabetical section headers, fast
 * scroll for long lists, a Genre detail screen.
 */
@Composable
fun LibraryScreen(
    subsonicClient: SubsonicClient,
    onArtistSelected: (String) -> Unit,
    onAlbumSelected: (String) -> Unit,
    onPlaylistSelected: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    var selectedChip by remember { mutableStateOf(LibraryChip.Artists) }

    var artistsState: LibraryLoadState<StandardArtistsPayload> by remember(subsonicClient) {
        mutableStateOf(LibraryLoadState.Idle)
    }
    var albumsState: LibraryLoadState<List<StandardAlbumSummary>> by remember(subsonicClient) {
        mutableStateOf(LibraryLoadState.Idle)
    }
    var playlistsState: LibraryLoadState<List<StandardPlaylistSummary>> by remember(subsonicClient) {
        mutableStateOf(LibraryLoadState.Idle)
    }
    var genresState: LibraryLoadState<List<StandardGenre>> by remember(subsonicClient) {
        mutableStateOf(LibraryLoadState.Idle)
    }

    LaunchedEffect(selectedChip, subsonicClient) {
        when (selectedChip) {
            LibraryChip.Artists -> {
                if (artistsState is LibraryLoadState.Idle) {
                    artistsState = LibraryLoadState.Loading
                    val result = subsonicClient.getArtists()
                    artistsState = when (result) {
                        is SubsonicResult.Ok -> {
                            LibraryLoadState.Loaded(result.value)
                        }
                        else -> {
                            LibraryLoadState.Failed(describeFailure(result))
                        }
                    }
                }
            }
            LibraryChip.Albums -> {
                if (albumsState is LibraryLoadState.Idle) {
                    albumsState = LibraryLoadState.Loading
                    val result = subsonicClient.getAlbumList2("alphabeticalByName", ALBUM_LIST_PAGE_SIZE)
                    albumsState = when (result) {
                        is SubsonicResult.Ok -> {
                            LibraryLoadState.Loaded(result.value)
                        }
                        else -> {
                            LibraryLoadState.Failed(describeFailure(result))
                        }
                    }
                }
            }
            LibraryChip.Playlists -> {
                if (playlistsState is LibraryLoadState.Idle) {
                    playlistsState = LibraryLoadState.Loading
                    val result = subsonicClient.getPlaylists()
                    playlistsState = when (result) {
                        is SubsonicResult.Ok -> {
                            LibraryLoadState.Loaded(result.value)
                        }
                        else -> {
                            LibraryLoadState.Failed(describeFailure(result))
                        }
                    }
                }
            }
            LibraryChip.Genres -> {
                if (genresState is LibraryLoadState.Idle) {
                    genresState = LibraryLoadState.Loading
                    val result = subsonicClient.getGenres()
                    genresState = when (result) {
                        is SubsonicResult.Ok -> {
                            LibraryLoadState.Loaded(result.value)
                        }
                        else -> {
                            LibraryLoadState.Failed(describeFailure(result))
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ThumpColors.Background)
            .padding(contentPadding),
    ) {
        LibraryChipRow(
            selectedChip = selectedChip,
            onChipSelected = { newChip: LibraryChip -> selectedChip = newChip },
        )
        when (selectedChip) {
            LibraryChip.Artists -> {
                ArtistsList(
                    state = artistsState,
                    subsonicClient = subsonicClient,
                    onArtistSelected = onArtistSelected,
                )
            }
            LibraryChip.Albums -> {
                AlbumsList(
                    state = albumsState,
                    subsonicClient = subsonicClient,
                    onAlbumSelected = onAlbumSelected,
                )
            }
            LibraryChip.Playlists -> {
                PlaylistsList(
                    state = playlistsState,
                    subsonicClient = subsonicClient,
                    onPlaylistSelected = onPlaylistSelected,
                )
            }
            LibraryChip.Genres -> {
                GenresList(state = genresState)
            }
        }
    }
}

@Composable
private fun LibraryChipRow(
    selectedChip: LibraryChip,
    onChipSelected: (LibraryChip) -> Unit,
) {
    val chips = LibraryChip.values()
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = chips.toList(), key = { chip -> chip.name }) { chip ->
            val isSelected = chip == selectedChip
            FilterChip(
                selected = isSelected,
                onClick = { onChipSelected(chip) },
                label = { Text(text = chip.label) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = ThumpColors.Surface,
                    labelColor = ThumpColors.OnSurface,
                    selectedContainerColor = ThumpColors.Accent,
                    selectedLabelColor = ThumpColors.OnBackground,
                ),
            )
        }
    }
}

@Composable
private fun ArtistsList(
    state: LibraryLoadState<StandardArtistsPayload>,
    subsonicClient: SubsonicClient,
    onArtistSelected: (String) -> Unit,
) {
    when (state) {
        is LibraryLoadState.Idle, LibraryLoadState.Loading -> {
            CenteredSpinner()
        }
        is LibraryLoadState.Failed -> {
            ErrorText(message = state.message)
        }
        is LibraryLoadState.Loaded -> {
            val flattened = ArrayList<StandardLibraryArtist>()
            val indexes = state.value.index
            val indexCount = indexes.size
            for (indexIndex in 0 until indexCount) {
                flattened.addAll(indexes[indexIndex].artist)
            }
            if (flattened.isEmpty()) {
                EmptyText(message = "No artists yet")
                return
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = flattened, key = { artist -> artist.id }) { artist ->
                    LibraryArtistRow(
                        artist = artist,
                        subsonicClient = subsonicClient,
                        onTapped = { onArtistSelected(artist.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumsList(
    state: LibraryLoadState<List<StandardAlbumSummary>>,
    subsonicClient: SubsonicClient,
    onAlbumSelected: (String) -> Unit,
) {
    when (state) {
        is LibraryLoadState.Idle, LibraryLoadState.Loading -> {
            CenteredSpinner()
        }
        is LibraryLoadState.Failed -> {
            ErrorText(message = state.message)
        }
        is LibraryLoadState.Loaded -> {
            if (state.value.isEmpty()) {
                EmptyText(message = "No albums yet")
                return
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = state.value, key = { album -> album.id }) { album ->
                    LibraryAlbumRow(
                        album = album,
                        subsonicClient = subsonicClient,
                        onTapped = { onAlbumSelected(album.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistsList(
    state: LibraryLoadState<List<StandardPlaylistSummary>>,
    subsonicClient: SubsonicClient,
    onPlaylistSelected: (String) -> Unit,
) {
    when (state) {
        is LibraryLoadState.Idle, LibraryLoadState.Loading -> {
            CenteredSpinner()
        }
        is LibraryLoadState.Failed -> {
            ErrorText(message = state.message)
        }
        is LibraryLoadState.Loaded -> {
            if (state.value.isEmpty()) {
                EmptyText(message = "No playlists yet")
                return
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = state.value, key = { playlist -> playlist.id }) { playlist ->
                    LibraryPlaylistRow(
                        playlist = playlist,
                        subsonicClient = subsonicClient,
                        onTapped = { onPlaylistSelected(playlist.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GenresList(state: LibraryLoadState<List<StandardGenre>>) {
    when (state) {
        is LibraryLoadState.Idle, LibraryLoadState.Loading -> {
            CenteredSpinner()
        }
        is LibraryLoadState.Failed -> {
            ErrorText(message = state.message)
        }
        is LibraryLoadState.Loaded -> {
            if (state.value.isEmpty()) {
                EmptyText(message = "No genres yet")
                return
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items = state.value, key = { genre -> genre.value }) { genre ->
                    LibraryGenreRow(genre = genre)
                }
            }
        }
    }
}

@Composable
private fun LibraryArtistRow(
    artist: StandardLibraryArtist,
    subsonicClient: SubsonicClient,
    onTapped: () -> Unit,
) {
    val coverArtId = artist.coverArt
    val coverArtUrl: String?
    if (coverArtId == null) {
        coverArtUrl = null
    } else {
        coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, ROW_ART_REQUEST_SIZE_PX)
    }
    LibraryListRow(
        title = artist.name,
        subtitle = buildArtistSubtitle(artist),
        leading = {
            val thumbModifier = Modifier
                .size(ROW_THUMB_SIZE_DP.dp)
                .clip(CircleShape)
                .background(ThumpColors.Surface)
            if (coverArtUrl == null) {
                Box(modifier = thumbModifier)
            } else {
                AsyncImage(
                    model = coverArtUrl,
                    contentDescription = null,
                    modifier = thumbModifier,
                )
            }
        },
        onTapped = onTapped,
    )
}

@Composable
private fun LibraryAlbumRow(
    album: StandardAlbumSummary,
    subsonicClient: SubsonicClient,
    onTapped: () -> Unit,
) {
    val coverArtId = album.coverArt
    val coverArtUrl: String?
    if (coverArtId == null) {
        coverArtUrl = null
    } else {
        coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, ROW_ART_REQUEST_SIZE_PX)
    }
    val artistText: String
    if (album.artist == null) {
        artistText = ""
    } else {
        artistText = album.artist
    }
    LibraryListRow(
        title = album.name,
        subtitle = artistText,
        leading = {
            val thumbModifier = Modifier
                .size(ROW_THUMB_SIZE_DP.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ThumpColors.Surface)
            if (coverArtUrl == null) {
                Box(modifier = thumbModifier)
            } else {
                AsyncImage(
                    model = coverArtUrl,
                    contentDescription = null,
                    modifier = thumbModifier,
                )
            }
        },
        onTapped = onTapped,
    )
}

@Composable
private fun LibraryPlaylistRow(
    playlist: StandardPlaylistSummary,
    subsonicClient: SubsonicClient,
    onTapped: () -> Unit,
) {
    LibraryListRow(
        title = playlist.name,
        subtitle = buildPlaylistSubtitle(playlist),
        leading = {
            val thumbModifier = Modifier
                .size(ROW_THUMB_SIZE_DP.dp)
                .clip(RoundedCornerShape(8.dp))
            CompositeArtTile(
                coverArtIds = playlistCoverArtIds(playlist),
                subsonicClient = subsonicClient,
                requestSizePx = PLAYLIST_ROW_ART_REQUEST_SIZE_PX,
                modifier = thumbModifier.size(ROW_THUMB_SIZE_DP.dp),
            )
        },
        onTapped = onTapped,
    )
}

@Composable
private fun LibraryGenreRow(genre: StandardGenre) {
    LibraryListRow(
        title = genre.value,
        subtitle = buildGenreSubtitle(genre),
        leading = {
            Box(
                modifier = Modifier
                    .size(ROW_THUMB_SIZE_DP.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(ThumpColors.Surface),
            )
        },
        onTapped = { /* Genre detail screen is a follow-up PR. */ },
    )
}

@Composable
private fun LibraryListRow(
    title: String,
    subtitle: String,
    leading: @Composable () -> Unit,
    onTapped: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTapped)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = ThumpColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = ThumpColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ThumpColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = ThumpColors.Accent)
    }
}

@Composable
private fun ErrorText(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = ThumpColors.TextSecondary)
    }
}

@Composable
private fun EmptyText(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = ThumpColors.TextSecondary)
    }
}

private fun describeFailure(result: SubsonicResult<*>): String {
    when (result) {
        is SubsonicResult.Ok -> {
            return "Unexpected success"
        }
        is SubsonicResult.ServerError -> {
            return "Server error " + result.code + ": " + result.message
        }
        is SubsonicResult.TransportError -> {
            return "Network error: " + result.cause.javaClass.simpleName
        }
        is SubsonicResult.MalformedResponse -> {
            return "Bad response: " + result.cause.javaClass.simpleName
        }
    }
}

private fun buildArtistSubtitle(artist: StandardLibraryArtist): String {
    if (artist.albumCount == null || artist.albumCount <= 0) {
        return ""
    }
    return artist.albumCount.toString() + " albums"
}

private fun buildPlaylistSubtitle(playlist: StandardPlaylistSummary): String {
    if (playlist.songCount == null || playlist.songCount <= 0) {
        return ""
    }
    return playlist.songCount.toString() + " tracks"
}

private fun buildGenreSubtitle(genre: StandardGenre): String {
    val parts = ArrayList<String>(2)
    if (genre.songCount != null && genre.songCount > 0) {
        parts.add(genre.songCount.toString() + " songs")
    }
    if (genre.albumCount != null && genre.albumCount > 0) {
        parts.add(genre.albumCount.toString() + " albums")
    }
    return parts.joinToString(separator = " • ")
}

/**
 * The playlist summaries returned by getPlaylists don't include entry-level cover art IDs, so
 * the Library playlist row can't show the 2x2 composite without a per-row getPlaylist fetch.
 * For now the row renders the empty composite (music-note glyph). Follow-up: lazy-fetch entries
 * the same way QuickPlaylistsGrid does.
 */
private fun playlistCoverArtIds(playlist: StandardPlaylistSummary): List<String> {
    val singleCover = playlist.coverArt
    if (singleCover == null) {
        return emptyList()
    }
    return listOf(singleCover)
}

private sealed interface LibraryLoadState<out T> {
    object Idle : LibraryLoadState<Nothing>
    object Loading : LibraryLoadState<Nothing>
    data class Loaded<T>(val value: T) : LibraryLoadState<T>
    data class Failed(val message: String) : LibraryLoadState<Nothing>
}

private enum class LibraryChip(val label: String) {
    Artists("Artists"),
    Albums("Albums"),
    Playlists("Playlists"),
    Genres("Genres"),
}
