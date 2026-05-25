package com.therobm.thump.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.therobm.thump.ThumpColors
import com.therobm.thump.art.CompositeArtTile
import com.therobm.thump.playback.PlaybackQueueItem
import com.therobm.thump.playback.PlaybackSource
import com.therobm.thump.playback.PlaybackSourceKind
import com.therobm.thump.subsonic.StandardPlaylistSummary
import com.therobm.thump.subsonic.StandardSongDetail
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicResult
import kotlinx.coroutines.launch

private const val QUICK_GRID_PLAYLIST_COUNT: Int = 8
private const val QUICK_TILE_ART_SIZE_DP: Int = 56
private const val QUICK_TILE_MIN_HEIGHT_DP: Int = 56
private const val QUICK_TILE_ART_REQUEST_SIZE_PX: Int = 112
private const val QUICK_PLAY_BUTTON_SIZE_DP: Int = 36
private const val QUEUE_ITEM_ART_REQUEST_SIZE_PX: Int = 200

/**
 * A 2-column grid of up to 8 playlists, pinned to the top of Home above the carousels.
 *
 * Mirrors the Pulse web client's quick grid: art on the left (composite of up to 4 unique
 * entry covers), name in the middle, instant-play button on the right. Tapping the row
 * navigates to the playlist detail; tapping the play button starts the playlist in place.
 */
@Composable
fun QuickPlaylistsGrid(
    subsonicClient: SubsonicClient,
    onPlaylistSelected: (playlistId: String, playlistName: String) -> Unit,
    onPlayQueue: (List<PlaybackQueueItem>, Int, PlaybackSource?) -> Unit,
) {
    var playlists: List<StandardPlaylistSummary> by remember(subsonicClient) {
        mutableStateOf(emptyList())
    }
    var hasLoaded by remember(subsonicClient) { mutableStateOf(false) }

    LaunchedEffect(subsonicClient) {
        val result = subsonicClient.getPlaylists()
        if (result is SubsonicResult.Ok) {
            val takeCount: Int
            if (result.value.size < QUICK_GRID_PLAYLIST_COUNT) {
                takeCount = result.value.size
            } else {
                takeCount = QUICK_GRID_PLAYLIST_COUNT
            }
            val firstN = ArrayList<StandardPlaylistSummary>(takeCount)
            for (playlistIndex in 0 until takeCount) {
                firstN.add(result.value[playlistIndex])
            }
            playlists = firstN
        }
        hasLoaded = true
    }

    if (!hasLoaded) {
        return
    }
    if (playlists.isEmpty()) {
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val rowCount = (playlists.size + 1) / 2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (rowIndex in 0 until rowCount) {
            val leftPlaylistIndex = rowIndex * 2
            val rightPlaylistIndex = leftPlaylistIndex + 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickPlaylistTile(
                    playlist = playlists[leftPlaylistIndex],
                    subsonicClient = subsonicClient,
                    onTapped = {
                        val tappedPlaylist = playlists[leftPlaylistIndex]
                        onPlaylistSelected(tappedPlaylist.id, tappedPlaylist.name)
                    },
                    onQuickPlayClicked = {
                        val targetPlaylist = playlists[leftPlaylistIndex]
                        coroutineScope.launch {
                            startPlaylistPlayback(
                                playlistId = targetPlaylist.id,
                                playlistName = targetPlaylist.name,
                                subsonicClient = subsonicClient,
                                onPlayQueue = onPlayQueue,
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (rightPlaylistIndex < playlists.size) {
                    QuickPlaylistTile(
                        playlist = playlists[rightPlaylistIndex],
                        subsonicClient = subsonicClient,
                        onTapped = {
                            val tappedPlaylist = playlists[rightPlaylistIndex]
                            onPlaylistSelected(tappedPlaylist.id, tappedPlaylist.name)
                        },
                        onQuickPlayClicked = {
                            val targetPlaylist = playlists[rightPlaylistIndex]
                            coroutineScope.launch {
                                startPlaylistPlayback(
                                    playlistId = targetPlaylist.id,
                                    playlistName = targetPlaylist.name,
                                    subsonicClient = subsonicClient,
                                    onPlayQueue = onPlayQueue,
                                )
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    // Empty right cell when the row has an odd remainder so the grid keeps
                    // its column alignment instead of stretching the lone left tile.
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QuickPlaylistTile(
    playlist: StandardPlaylistSummary,
    subsonicClient: SubsonicClient,
    onTapped: () -> Unit,
    onQuickPlayClicked: () -> Unit,
    modifier: Modifier,
) {
    var entryCoverArtIds: List<String> by remember(playlist.id) { mutableStateOf(emptyList()) }

    LaunchedEffect(playlist.id, subsonicClient) {
        val result = subsonicClient.getPlaylist(playlist.id)
        if (result is SubsonicResult.Ok) {
            val entries = result.value.entry
            val ids = ArrayList<String>(entries.size)
            val entryCount = entries.size
            for (entryIndex in 0 until entryCount) {
                val candidate = entries[entryIndex].coverArt
                if (candidate != null) {
                    ids.add(candidate)
                }
            }
            entryCoverArtIds = ids
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(QUICK_TILE_MIN_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ThumpColors.Surface)
            .clickable(onClick = onTapped),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(QUICK_TILE_ART_SIZE_DP.dp)) {
            CompositeArtTile(
                coverArtIds = entryCoverArtIds,
                subsonicClient = subsonicClient,
                requestSizePx = QUICK_TILE_ART_REQUEST_SIZE_PX,
                modifier = Modifier.size(QUICK_TILE_ART_SIZE_DP.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            color = ThumpColors.OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onQuickPlayClicked,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(QUICK_PLAY_BUTTON_SIZE_DP.dp)
                .clip(CircleShape),
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = ThumpColors.Accent,
                contentColor = ThumpColors.Background,
            ),
        ) {
            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Play")
        }
    }
}

/**
 * Fetch the playlist's entries, map them into PlaybackQueueItems, and start playback. Shared
 * by both tiles in the grid so the same source label appears in Now Playing whichever side
 * of the row the user tapped.
 */
private suspend fun startPlaylistPlayback(
    playlistId: String,
    playlistName: String,
    subsonicClient: SubsonicClient,
    onPlayQueue: (List<PlaybackQueueItem>, Int, PlaybackSource?) -> Unit,
) {
    val result = subsonicClient.getPlaylist(playlistId)
    if (result !is SubsonicResult.Ok) {
        return
    }
    val songs = result.value.entry
    val queue = ArrayList<PlaybackQueueItem>(songs.size)
    val songCount = songs.size
    for (songIndex in 0 until songCount) {
        val song: StandardSongDetail = songs[songIndex]
        val coverArtUrl: String?
        val coverArtId = song.coverArt
        if (coverArtId == null) {
            coverArtUrl = null
        } else {
            coverArtUrl = subsonicClient.buildCoverArtUrl(coverArtId, QUEUE_ITEM_ART_REQUEST_SIZE_PX)
        }
        val artistText: String
        if (song.artist == null) {
            artistText = ""
        } else {
            artistText = song.artist
        }
        queue.add(
            PlaybackQueueItem(
                trackId = song.id,
                streamUrl = subsonicClient.buildStreamUrl(song.id),
                title = song.title,
                artist = artistText,
                album = song.album,
                coverArtUrl = coverArtUrl,
            )
        )
    }
    if (queue.isEmpty()) {
        return
    }
    onPlayQueue(queue, 0, PlaybackSource(PlaybackSourceKind.Playlist, playlistName))
}
