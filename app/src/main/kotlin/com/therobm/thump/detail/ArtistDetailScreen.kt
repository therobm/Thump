package com.therobm.thump.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.therobm.thump.ThumpColors
import com.therobm.thump.playback.PlaybackQueueItem
import com.therobm.thump.playback.PlaybackSource
import com.therobm.thump.playback.PlaybackSourceKind
import com.therobm.thump.subsonic.PulseRecentlyPlayedTrack
import com.therobm.thump.subsonic.StandardArtistAlbum
import com.therobm.thump.subsonic.StandardArtistDetailPayload
import com.therobm.thump.subsonic.StandardSongDetail
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicResult
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val ARTIST_ART_REQUEST_SIZE: Int = 400
private const val ALBUM_ROW_ART_REQUEST_SIZE: Int = 200
private const val QUEUE_ITEM_ART_REQUEST_SIZE: Int = 200

@Composable
fun ArtistDetailScreen(
    artistId: String,
    subsonicClient: SubsonicClient,
    isPulseServer: Boolean,
    onBackPressed: () -> Unit,
    onAlbumSelected: (String) -> Unit,
    onPlayQueue: (List<PlaybackQueueItem>, Int, PlaybackSource?) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    var loadState: DetailLoadState<StandardArtistDetailPayload> by remember(artistId) {
        mutableStateOf(DetailLoadState.Loading)
    }
    var isLoadingTracks by remember(artistId) { mutableStateOf(false) }
    var loadTracksError: String? by remember(artistId) { mutableStateOf(null) }

    LaunchedEffect(artistId, subsonicClient) {
        val result = subsonicClient.getArtist(artistId)
        when (result) {
            is SubsonicResult.Ok -> {
                loadState = DetailLoadState.Loaded(result.value)
            }
            else -> {
                loadState = DetailLoadState.Failed(describeSubsonicFailure(result))
            }
        }
    }

    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ThumpColors.Background)
            .padding(contentPadding),
    ) {
        DetailTopBar(title = "Artist", onBackPressed = onBackPressed)

        val currentLoadState = loadState
        when (currentLoadState) {
            is DetailLoadState.Loading -> {
                CenteredSpinner()
            }
            is DetailLoadState.Failed -> {
                Text(
                    text = currentLoadState.message,
                    color = ThumpColors.TextSecondary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            is DetailLoadState.Loaded -> {
                ArtistDetailContent(
                    artist = currentLoadState.value,
                    subsonicClient = subsonicClient,
                    isLoadingTracks = isLoadingTracks,
                    loadTracksError = loadTracksError,
                    onAlbumSelected = onAlbumSelected,
                    onPlayClicked = {
                        loadTracksError = null
                        isLoadingTracks = true
                        coroutineScope.launch {
                            val queueResult = buildArtistQueue(
                                artist = currentLoadState.value,
                                subsonicClient = subsonicClient,
                                isPulseServer = isPulseServer,
                            )
                            when (queueResult) {
                                is QueueBuildResult.Ok -> {
                                    if (queueResult.items.isNotEmpty()) {
                                        onPlayQueue(
                                            queueResult.items,
                                            0,
                                            PlaybackSource(PlaybackSourceKind.Artist, currentLoadState.value.name),
                                        )
                                    }
                                }
                                is QueueBuildResult.Failed -> {
                                    loadTracksError = queueResult.message
                                }
                            }
                            isLoadingTracks = false
                        }
                    },
                    onShuffleClicked = {
                        loadTracksError = null
                        isLoadingTracks = true
                        coroutineScope.launch {
                            val queueResult = buildArtistQueue(
                                artist = currentLoadState.value,
                                subsonicClient = subsonicClient,
                                isPulseServer = isPulseServer,
                            )
                            when (queueResult) {
                                is QueueBuildResult.Ok -> {
                                    if (queueResult.items.isNotEmpty()) {
                                        onPlayQueue(
                                            queueResult.items.shuffled(),
                                            0,
                                            PlaybackSource(PlaybackSourceKind.Artist, currentLoadState.value.name),
                                        )
                                    }
                                }
                                is QueueBuildResult.Failed -> {
                                    loadTracksError = queueResult.message
                                }
                            }
                            isLoadingTracks = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtistDetailContent(
    artist: StandardArtistDetailPayload,
    subsonicClient: SubsonicClient,
    isLoadingTracks: Boolean,
    loadTracksError: String?,
    onAlbumSelected: (String) -> Unit,
    onPlayClicked: () -> Unit,
    onShuffleClicked: () -> Unit,
) {
    val artistArtId = artist.coverArt
    val artistArtUrl: String?
    if (artistArtId == null) {
        artistArtUrl = null
    } else {
        artistArtUrl = subsonicClient.buildCoverArtUrl(artistArtId, ARTIST_ART_REQUEST_SIZE)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val portraitModifier = Modifier
                    .size(180.dp)
                    .clip(CircleShape)
                    .background(ThumpColors.Surface)
                if (artistArtUrl == null) {
                    Box(modifier = portraitModifier)
                } else {
                    AsyncImage(
                        model = artistArtUrl,
                        contentDescription = null,
                        modifier = portraitModifier,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = ThumpColors.OnBackground,
                    textAlign = TextAlign.Center,
                )
                if (artist.albumCount != null && artist.albumCount > 0) {
                    Text(
                        text = artist.albumCount.toString() + " albums",
                        style = MaterialTheme.typography.bodySmall,
                        color = ThumpColors.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onPlayClicked,
                    enabled = !isLoadingTracks,
                    colors = ButtonDefaults.buttonColors(containerColor = ThumpColors.Accent),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Play")
                }
                OutlinedButton(
                    onClick = onShuffleClicked,
                    enabled = !isLoadingTracks,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(imageVector = Icons.Filled.Shuffle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Shuffle")
                }
            }
        }

        if (loadTracksError != null) {
            item {
                Text(
                    text = loadTracksError,
                    color = ThumpColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        items(items = artist.album, key = { album -> album.id }) { album: StandardArtistAlbum ->
            ArtistAlbumRow(
                album = album,
                subsonicClient = subsonicClient,
                onTapped = { onAlbumSelected(album.id) },
            )
        }
    }
}

@Composable
private fun ArtistAlbumRow(
    album: StandardArtistAlbum,
    subsonicClient: SubsonicClient,
    onTapped: () -> Unit,
) {
    val coverArtUrl: String?
    val coverArtId = album.coverArt
    if (coverArtId == null) {
        coverArtUrl = null
    } else {
        coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, ALBUM_ROW_ART_REQUEST_SIZE)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTapped)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val artModifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ThumpColors.Surface)
        if (coverArtUrl == null) {
            Box(modifier = artModifier)
        } else {
            AsyncImage(
                model = coverArtUrl,
                contentDescription = null,
                modifier = artModifier,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyMedium,
                color = ThumpColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildAlbumRowSubtitle(album)
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

private fun buildAlbumRowSubtitle(album: StandardArtistAlbum): String {
    val parts = ArrayList<String>(2)
    if (album.year != null) {
        parts.add(album.year.toString())
    }
    if (album.songCount != null && album.songCount > 0) {
        parts.add(album.songCount.toString() + " tracks")
    }
    return parts.joinToString(separator = " • ")
}

/**
 * Result of an artist-tracks load. Held internally to keep the call sites readable; only the
 * QueueBuildResult.Ok branch carries a queue worth playing.
 */
private sealed interface QueueBuildResult {
    data class Ok(val items: List<PlaybackQueueItem>) : QueueBuildResult
    data class Failed(val message: String) : QueueBuildResult
}

/**
 * Load every track for the artist. On Pulse, one call to pulse/artistTracks. On standard
 * OpenSubsonic, fan out getAlbum per album in parallel and concatenate in the order the
 * albums were returned by getArtist.
 */
private suspend fun buildArtistQueue(
    artist: StandardArtistDetailPayload,
    subsonicClient: SubsonicClient,
    isPulseServer: Boolean,
): QueueBuildResult {
    if (isPulseServer) {
        val pulseResult = subsonicClient.getPulseArtistTracks(artist.id)
        return when (pulseResult) {
            is SubsonicResult.Ok -> {
                QueueBuildResult.Ok(mapPulseTracksToQueue(pulseResult.value, subsonicClient))
            }
            else -> {
                QueueBuildResult.Failed(describeSubsonicFailure(pulseResult))
            }
        }
    }

    val albums = artist.album
    val albumCount = albums.size
    if (albumCount == 0) {
        return QueueBuildResult.Ok(emptyList())
    }
    val combined = ArrayList<PlaybackQueueItem>()
    val albumResults: List<SubsonicResult<com.therobm.thump.subsonic.StandardAlbumDetailPayload>> = coroutineScope {
        val deferreds = ArrayList<Deferred<SubsonicResult<com.therobm.thump.subsonic.StandardAlbumDetailPayload>>>(albumCount)
        for (albumIndex in 0 until albumCount) {
            val albumIdForFetch = albums[albumIndex].id
            deferreds.add(async { subsonicClient.getAlbum(albumIdForFetch) })
        }
        val collected = ArrayList<SubsonicResult<com.therobm.thump.subsonic.StandardAlbumDetailPayload>>(albumCount)
        val deferredCount = deferreds.size
        for (deferredIndex in 0 until deferredCount) {
            collected.add(deferreds[deferredIndex].await())
        }
        collected
    }

    val resultCount = albumResults.size
    for (resultIndex in 0 until resultCount) {
        val singleResult = albumResults[resultIndex]
        if (singleResult is SubsonicResult.Ok) {
            val payload = singleResult.value
            combined.addAll(mapStandardSongsToQueue(payload.song, payload.name, subsonicClient))
        }
    }
    return QueueBuildResult.Ok(combined)
}

private fun mapPulseTracksToQueue(
    tracks: List<PulseRecentlyPlayedTrack>,
    subsonicClient: SubsonicClient,
): List<PlaybackQueueItem> {
    val result = ArrayList<PlaybackQueueItem>(tracks.size)
    val trackCount = tracks.size
    for (trackIndex in 0 until trackCount) {
        val track = tracks[trackIndex]
        val coverArtUrl: String?
        val coverArtId = track.coverArt
        if (coverArtId == null) {
            coverArtUrl = null
        } else {
            coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, QUEUE_ITEM_ART_REQUEST_SIZE)
        }
        val artistText: String
        if (track.artist == null) {
            artistText = ""
        } else {
            artistText = track.artist
        }
        result.add(
            PlaybackQueueItem(
                trackId = track.id,
                streamUrl = subsonicClient.buildStreamUrl(track.id),
                title = track.title,
                artist = artistText,
                album = track.album,
                coverArtUrl = coverArtUrl,
            )
        )
    }
    return result
}

private fun mapStandardSongsToQueue(
    songs: List<StandardSongDetail>,
    albumName: String,
    subsonicClient: SubsonicClient,
): List<PlaybackQueueItem> {
    val result = ArrayList<PlaybackQueueItem>(songs.size)
    val songCount = songs.size
    for (songIndex in 0 until songCount) {
        val song = songs[songIndex]
        val coverArtUrl: String?
        val coverArtId = song.coverArt
        if (coverArtId == null) {
            coverArtUrl = null
        } else {
            coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, QUEUE_ITEM_ART_REQUEST_SIZE)
        }
        val artistText: String
        if (song.artist == null) {
            artistText = ""
        } else {
            artistText = song.artist
        }
        result.add(
            PlaybackQueueItem(
                trackId = song.id,
                streamUrl = subsonicClient.buildStreamUrl(song.id),
                title = song.title,
                artist = artistText,
                album = albumName,
                coverArtUrl = coverArtUrl,
            )
        )
    }
    return result
}
