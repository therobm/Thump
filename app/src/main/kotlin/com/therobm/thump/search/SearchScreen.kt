package com.therobm.thump.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.therobm.thump.ThumpColors
import com.therobm.thump.art.ArtImage
import com.therobm.thump.data.Album
import com.therobm.thump.data.Artist
import com.therobm.thump.data.SearchResult
import com.therobm.thump.data.ThumpData
import com.therobm.thump.data.ThumpDataNotConfigured
import com.therobm.thump.data.Track
import kotlinx.coroutines.delay
import java.io.IOException

private const val DEBOUNCE_MS: Long = 300L
private const val ROW_THUMB_SIZE_DP: Int = 56
private const val ROW_ART_REQUEST_SIZE_PX: Int = 150
private const val NO_SERVER_CONFIGURED_MESSAGE: String = "No server configured"

/**
 * Real Search tab. Single text input at the top; below it, three sections (Artists / Albums /
 * Songs) populated by `thumpData.search`. Input is debounced 300ms so we don't fire a request on
 * every keystroke.
 *
 * Empty query renders the placeholder prompt. Tapping a row opens the matching detail screen,
 * except for songs which play immediately as a single-track queue. SearchScreen reports the
 * tapped track via `onTrackSelected` and lets MainActivity assemble the PlaybackQueueItem so
 * this screen stays free of stream-URL and cover-art-URL construction.
 */
@Composable
fun SearchScreen(
    thumpData: ThumpData,
    onArtistSelected: (String) -> Unit,
    onAlbumSelected: (String) -> Unit,
    onTrackSelected: (Track) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    var query: String by rememberSaveable { mutableStateOf("") }
    var loadState: SearchLoadState by remember(thumpData) {
        mutableStateOf<SearchLoadState>(SearchLoadState.Empty)
    }

    LaunchedEffect(query, thumpData) {
        if (query.isBlank()) {
            loadState = SearchLoadState.Empty
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS)
        loadState = SearchLoadState.Loading
        try {
            val result: SearchResult = thumpData.search(query.trim())
            loadState = SearchLoadState.Loaded(result)
        } catch (notConfigured: ThumpDataNotConfigured) {
            loadState = SearchLoadState.Failed(NO_SERVER_CONFIGURED_MESSAGE)
        } catch (transportFailure: IOException) {
            loadState = SearchLoadState.Failed("Network error: " + transportFailure.javaClass.simpleName)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ThumpColors.Background)
            .padding(contentPadding),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { newValue: String -> query = newValue },
            placeholder = { Text(text = "Search artists, albums, songs") },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ThumpColors.Surface,
                unfocusedContainerColor = ThumpColors.Surface,
                focusedTextColor = ThumpColors.OnSurface,
                unfocusedTextColor = ThumpColors.OnSurface,
                focusedPlaceholderColor = ThumpColors.TextSecondary,
                unfocusedPlaceholderColor = ThumpColors.TextSecondary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val currentLoadState: SearchLoadState = loadState
        when (currentLoadState) {
            is SearchLoadState.Empty -> {
                CenteredHint(message = "Start typing to search your library.")
            }
            is SearchLoadState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ThumpColors.Accent)
                }
            }
            is SearchLoadState.Failed -> {
                CenteredHint(message = currentLoadState.message)
            }
            is SearchLoadState.Loaded -> {
                val payload: SearchResult = currentLoadState.value
                if (payload.artists.isEmpty() && payload.albums.isEmpty() && payload.tracks.isEmpty()) {
                    CenteredHint(message = "No matches.")
                } else {
                    SearchResultsList(
                        payload = payload,
                        thumpData = thumpData,
                        onArtistSelected = onArtistSelected,
                        onAlbumSelected = onAlbumSelected,
                        onTrackSelected = onTrackSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultsList(
    payload: SearchResult,
    thumpData: ThumpData,
    onArtistSelected: (String) -> Unit,
    onAlbumSelected: (String) -> Unit,
    onTrackSelected: (Track) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (payload.artists.isNotEmpty()) {
            item(key = "header-artists") {
                SectionHeader(title = "Artists")
            }
            items(items = payload.artists, key = { artist: Artist -> "artist:" + artist.artistId }) { artist: Artist ->
                SearchArtistRow(
                    artist = artist,
                    thumpData = thumpData,
                    onTapped = { onArtistSelected(artist.artistId) },
                )
            }
        }
        if (payload.albums.isNotEmpty()) {
            item(key = "header-albums") {
                SectionHeader(title = "Albums")
            }
            items(items = payload.albums, key = { album: Album -> "album:" + album.albumId }) { album: Album ->
                SearchAlbumRow(
                    album = album,
                    thumpData = thumpData,
                    onTapped = { onAlbumSelected(album.albumId) },
                )
            }
        }
        if (payload.tracks.isNotEmpty()) {
            item(key = "header-songs") {
                SectionHeader(title = "Songs")
            }
            items(items = payload.tracks, key = { track: Track -> "track:" + track.trackId }) { track: Track ->
                SearchTrackRow(
                    track = track,
                    thumpData = thumpData,
                    onTapped = { onTrackSelected(track) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = ThumpColors.OnBackground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SearchArtistRow(
    artist: Artist,
    thumpData: ThumpData,
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
        val thumbModifier: Modifier = Modifier
            .size(ROW_THUMB_SIZE_DP.dp)
            .clip(CircleShape)
        ArtImage(
            thumpData = thumpData,
            artId = artist.coverArtId,
            sizePx = ROW_ART_REQUEST_SIZE_PX,
            contentDescription = null,
            modifier = thumbModifier,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.bodyMedium,
                color = ThumpColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle: String
            if (artist.albumCount > 0) {
                subtitle = artist.albumCount.toString() + " albums"
            } else {
                subtitle = ""
            }
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
private fun SearchAlbumRow(
    album: Album,
    thumpData: ThumpData,
    onTapped: () -> Unit,
) {
    val artistText: String
    if (album.artistName == null) {
        artistText = ""
    } else {
        artistText = album.artistName
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTapped)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val thumbModifier: Modifier = Modifier
            .size(ROW_THUMB_SIZE_DP.dp)
            .clip(RoundedCornerShape(8.dp))
        ArtImage(
            thumpData = thumpData,
            artId = album.coverArtId,
            sizePx = ROW_ART_REQUEST_SIZE_PX,
            contentDescription = null,
            modifier = thumbModifier,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyMedium,
                color = ThumpColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artistText.isNotEmpty()) {
                Text(
                    text = artistText,
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
private fun SearchTrackRow(
    track: Track,
    thumpData: ThumpData,
    onTapped: () -> Unit,
) {
    val artistText: String
    if (track.artistName == null) {
        artistText = ""
    } else {
        artistText = track.artistName
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTapped)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val thumbModifier: Modifier = Modifier
            .size(ROW_THUMB_SIZE_DP.dp)
            .clip(RoundedCornerShape(8.dp))
        ArtImage(
            thumpData = thumpData,
            artId = track.coverArtId,
            sizePx = ROW_ART_REQUEST_SIZE_PX,
            contentDescription = null,
            modifier = thumbModifier,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = ThumpColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artistText.isNotEmpty()) {
                Text(
                    text = artistText,
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
private fun CenteredHint(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, color = ThumpColors.TextSecondary)
    }
}

private sealed interface SearchLoadState {
    object Empty : SearchLoadState
    object Loading : SearchLoadState
    data class Loaded(val value: SearchResult) : SearchLoadState
    data class Failed(val message: String) : SearchLoadState
}
