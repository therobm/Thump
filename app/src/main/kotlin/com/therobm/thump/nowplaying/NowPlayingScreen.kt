package com.therobm.thump.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.therobm.thump.ThumpColors
import com.therobm.thump.art.ArtImage
import com.therobm.thump.data.ThumpData
import com.therobm.thump.playback.NowPlaying
import com.therobm.thump.playback.PlaybackController
import com.therobm.thump.playback.PlaybackSource
import com.therobm.thump.playback.PlaybackSourceKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val POSITION_POLL_INTERVAL_MS: Long = 500L
private const val LARGE_PLAY_BUTTON_SIZE_DP: Int = 72
private const val SIDE_TRANSPORT_BUTTON_SIZE_DP: Int = 56
private const val NOW_PLAYING_ART_REQUEST_SIZE_PX: Int = 600

// Muted red used to flag the unavailable banner. Local because this colour is only used by the
// unavailable state in the playback surfaces.
private val UnavailableAccent: Color = Color(0xFFD08080)

/**
 * The full-screen Now Playing view. Reached by tapping the mini player.
 *
 * Layout: cover art anchored under the top bar; transport controls anchored to the bottom of
 * the screen; track info sits just above the controls; flexible space between art and info.
 * Position is polled from the player on a short interval since ExoPlayer does not expose a
 * flow for the play head.
 *
 * Shuffle, repeat, favorite, and queue list are deferred to follow-up PRs.
 */
@Composable
fun NowPlayingScreen(
    nowPlaying: NowPlaying?,
    thumpData: ThumpData,
    playbackController: PlaybackController,
    onBackPressed: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    if (nowPlaying == null) {
        LaunchedEffect(Unit) {
            onBackPressed()
        }
        return
    }

    var actualPositionMs by remember { mutableStateOf(0L) }
    var trackDurationMs by remember { mutableStateOf(0L) }
    var dragPositionMs: Long? by remember(nowPlaying.trackId) { mutableStateOf(null) }

    LaunchedEffect(playbackController, nowPlaying.trackId) {
        while (isActive) {
            actualPositionMs = playbackController.currentPositionMs()
            trackDurationMs = playbackController.durationMs()
            delay(POSITION_POLL_INTERVAL_MS)
        }
    }

    val displayPositionMs: Long
    if (dragPositionMs != null) {
        displayPositionMs = dragPositionMs as Long
    } else {
        displayPositionMs = actualPositionMs
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ThumpColors.Background)
            .padding(contentPadding),
    ) {
        NowPlayingTopBar(source = nowPlaying.source, onBackPressed = onBackPressed)

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            val artModifier: Modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(ThumpColors.Surface)
            ArtImage(
                thumpData = thumpData,
                artId = nowPlaying.coverArtId,
                sizePx = NOW_PLAYING_ART_REQUEST_SIZE_PX,
                contentDescription = null,
                modifier = artModifier,
            )
        }

        // Flexible space between art (top) and the rest (bottom-anchored).
        Spacer(modifier = Modifier.weight(1f))

        TrackInfoBlock(nowPlaying = nowPlaying)

        val unavailableReason: String? = nowPlaying.unavailableReason
        // LOADING is a transient state during the service's onPlayerError prefetch recovery —
        // banner stays neutral so the user reads it as progress, not failure. Other non-null
        // reasons mean the load actually failed; banner switches to muted-red to flag it.
        val isLoadingState: Boolean = unavailableReason == PlaybackController.UNAVAILABLE_REASON_LOADING
        val isFailureState: Boolean = unavailableReason != null && !isLoadingState
        if (unavailableReason != null) {
            Spacer(modifier = Modifier.height(12.dp))
            UnavailableBanner(reason = unavailableReason, isFailure = isFailureState)
        }

        Spacer(modifier = Modifier.height(12.dp))

        SeekBarBlock(
            displayPositionMs = displayPositionMs,
            trackDurationMs = trackDurationMs,
            onValueChange = { newValue: Long -> dragPositionMs = newValue },
            onValueChangeFinished = {
                val pendingDrag = dragPositionMs
                if (pendingDrag != null) {
                    playbackController.seekTo(pendingDrag)
                    actualPositionMs = pendingDrag
                    dragPositionMs = null
                }
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Skip controls stay live regardless of the unavailable state. Manual queue navigation
        // is always the user's escape hatch — even from a failed track, they should be able to
        // step past it without first hitting retry. Auto-advance is a separate code path on the
        // service side and is unaffected by this.
        val onPlayPauseClicked: () -> Unit
        if (isFailureState) {
            onPlayPauseClicked = { playbackController.retryCurrentTrack() }
        } else {
            onPlayPauseClicked = {
                if (nowPlaying.isPlaying) {
                    playbackController.pause()
                } else {
                    playbackController.resume()
                }
            }
        }
        TransportControlsRow(
            isPlaying = nowPlaying.isPlaying,
            playPauseEnabled = !isLoadingState,
            isLoadingState = isLoadingState,
            isRetryMode = isFailureState,
            onPreviousClicked = { playbackController.skipToPrevious() },
            onPlayPauseClicked = onPlayPauseClicked,
            onNextClicked = { playbackController.skipToNext() },
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun UnavailableBanner(reason: String, isFailure: Boolean) {
    val bannerColor: Color
    if (isFailure) {
        bannerColor = UnavailableAccent
    } else {
        bannerColor = ThumpColors.OnBackground
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            color = bannerColor,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NowPlayingTopBar(source: PlaybackSource?, onBackPressed: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackPressed) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Collapse",
                tint = ThumpColors.OnBackground,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Playing from",
                style = MaterialTheme.typography.labelSmall,
                color = ThumpColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildSourceLine(source),
                style = MaterialTheme.typography.titleSmall,
                color = ThumpColors.OnBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrackInfoBlock(nowPlaying: NowPlaying) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = nowPlaying.title,
            style = MaterialTheme.typography.titleLarge,
            color = ThumpColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (nowPlaying.artist.isNotEmpty()) {
            Text(
                text = nowPlaying.artist,
                style = MaterialTheme.typography.bodyLarge,
                color = ThumpColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        val albumText = nowPlaying.album
        if (albumText != null && albumText.isNotEmpty()) {
            Text(
                text = albumText,
                style = MaterialTheme.typography.bodyMedium,
                color = ThumpColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBarBlock(
    displayPositionMs: Long,
    trackDurationMs: Long,
    onValueChange: (Long) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        val sliderMax: Float
        if (trackDurationMs > 0L) {
            sliderMax = trackDurationMs.toFloat()
        } else {
            sliderMax = 1f
        }
        val sliderInteractionSource = remember { MutableInteractionSource() }
        val sliderColors = SliderDefaults.colors(
            thumbColor = ThumpColors.Accent,
            activeTrackColor = ThumpColors.Accent,
            inactiveTrackColor = ThumpColors.Divider,
        )
        Slider(
            value = displayPositionMs.toFloat().coerceIn(0f, sliderMax),
            valueRange = 0f..sliderMax,
            onValueChange = { newValue: Float -> onValueChange(newValue.toLong()) },
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = sliderInteractionSource,
            colors = sliderColors,
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = sliderInteractionSource,
                    colors = sliderColors,
                    thumbSize = DpSize(width = 12.dp, height = 12.dp),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    colors = sliderColors,
                    modifier = Modifier.height(2.dp),
                )
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatPositionMs(displayPositionMs),
                style = MaterialTheme.typography.bodySmall,
                color = ThumpColors.TextSecondary,
            )
            Text(
                text = formatPositionMs(trackDurationMs),
                style = MaterialTheme.typography.bodySmall,
                color = ThumpColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun TransportControlsRow(
    isPlaying: Boolean,
    playPauseEnabled: Boolean,
    isLoadingState: Boolean,
    isRetryMode: Boolean,
    onPreviousClicked: () -> Unit,
    onPlayPauseClicked: () -> Unit,
    onNextClicked: () -> Unit,
) {
    val sideTint: Color = ThumpColors.OnBackground
    val centerTint: Color
    if (!playPauseEnabled) {
        centerTint = ThumpColors.TextSecondary
    } else if (isRetryMode) {
        centerTint = UnavailableAccent
    } else {
        centerTint = ThumpColors.Accent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPreviousClicked,
            modifier = Modifier.size(SIDE_TRANSPORT_BUTTON_SIZE_DP.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                tint = sideTint,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(modifier = Modifier.size(16.dp))
        if (isLoadingState) {
            // Recovery prefetch is in flight — render a progress indicator in the play-button
            // slot so the user gets explicit "we're loading this" feedback instead of staring
            // at a frozen play icon. Sized to match the large play button so the surrounding
            // layout does not shift between states.
            Box(
                modifier = Modifier.size(LARGE_PLAY_BUTTON_SIZE_DP.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = ThumpColors.Accent,
                    strokeWidth = 3.dp,
                )
            }
        } else {
            IconButton(
                onClick = onPlayPauseClicked,
                enabled = playPauseEnabled,
                modifier = Modifier.size(LARGE_PLAY_BUTTON_SIZE_DP.dp),
            ) {
                if (isPlaying && !isRetryMode) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = "Pause",
                        tint = centerTint,
                        modifier = Modifier.size(56.dp),
                    )
                } else {
                    val description: String
                    if (isRetryMode) {
                        description = "Retry"
                    } else {
                        description = "Play"
                    }
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = description,
                        tint = centerTint,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.size(16.dp))
        IconButton(
            onClick = onNextClicked,
            modifier = Modifier.size(SIDE_TRANSPORT_BUTTON_SIZE_DP.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next",
                tint = sideTint,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

private fun buildSourceLine(source: PlaybackSource?): String {
    if (source == null) {
        return "current track"
    }
    val kindLabel: String
    when (source.kind) {
        PlaybackSourceKind.Album -> {
            kindLabel = "album"
        }
        PlaybackSourceKind.Playlist -> {
            kindLabel = "playlist"
        }
        PlaybackSourceKind.Artist -> {
            kindLabel = "artist"
        }
        PlaybackSourceKind.Genre -> {
            kindLabel = "genre"
        }
    }
    return kindLabel + ": " + source.name
}

private fun formatPositionMs(positionMs: Long): String {
    if (positionMs <= 0L) {
        return "0:00"
    }
    val totalSeconds = positionMs / 1000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val builder = StringBuilder()
    builder.append(minutes)
    builder.append(':')
    if (seconds < 10L) {
        builder.append('0')
    }
    builder.append(seconds)
    return builder.toString()
}
