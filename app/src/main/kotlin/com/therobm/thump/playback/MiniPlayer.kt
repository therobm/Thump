package com.therobm.thump.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.therobm.thump.ThumpColors
import com.therobm.thump.art.ArtImage
import com.therobm.thump.data.ThumpData

private const val MINI_PLAYER_ART_REQUEST_SIZE_PX: Int = 150

// Muted red used to flag the unavailable state without clashing with the cool palette. Kept
// local to the mini player + now-playing screens since it is the only place this colour appears.
private val UnavailableAccent: Color = Color(0xFFD08080)

/**
 * Bar pinned above the bottom navigation that shows whatever is currently loaded into the
 * player.
 *
 * Tapping the play/pause button toggles state. When the track is unavailable (offline / load
 * failure), the play button becomes a retry affordance: tap fires `onRetryClicked` which the
 * caller routes to PlaybackController.retryCurrentTrack(). Tapping the rest of the bar will
 * open the full now-playing screen.
 */
@Composable
fun MiniPlayer(
    nowPlaying: NowPlaying,
    thumpData: ThumpData,
    onPlayPauseClicked: () -> Unit,
    onRetryClicked: () -> Unit,
    onExpandClicked: () -> Unit,
) {
    val unavailableReason: String? = nowPlaying.unavailableReason
    // LOADING is a transient state during the service's onPlayerError prefetch recovery and
    // should render like a normal row (expandable, neutral colours) except that the play button
    // slot shows a progress indicator. Only true failure states (offline, generic load failure,
    // not configured) lock down the row and turn the affordance into a retry.
    val isLoadingState: Boolean = unavailableReason == PlaybackController.UNAVAILABLE_REASON_LOADING
    val isFailureState: Boolean = unavailableReason != null && !isLoadingState
    val rowModifier: Modifier
    if (isFailureState) {
        // Tap-to-expand is disabled in the failure state — only the play button is hot, and it
        // acts as a retry. The row still renders so the user sees what they tapped and the
        // reason text.
        rowModifier = Modifier
            .fillMaxWidth()
            .background(ThumpColors.SurfaceElevated)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    } else {
        rowModifier = Modifier
            .fillMaxWidth()
            .background(ThumpColors.SurfaceElevated)
            .clickable(onClick = onExpandClicked)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val artModifier: Modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(ThumpColors.Surface)
        ArtImage(
            thumpData = thumpData,
            artId = nowPlaying.coverArtId,
            sizePx = MINI_PLAYER_ART_REQUEST_SIZE_PX,
            contentDescription = null,
            modifier = artModifier,
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = nowPlaying.title,
                style = MaterialTheme.typography.bodyMedium,
                color = ThumpColors.OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (unavailableReason != null) {
                val reasonColor: Color
                if (isFailureState) {
                    reasonColor = UnavailableAccent
                } else {
                    reasonColor = ThumpColors.TextSecondary
                }
                Text(
                    text = unavailableReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = reasonColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else if (nowPlaying.artist.isNotEmpty()) {
                Text(
                    text = nowPlaying.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = ThumpColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (isLoadingState) {
            // Loading state is non-interactive: the prefetch is already in flight. Match the
            // play-icon footprint so the row height stays stable across state transitions.
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = ThumpColors.OnSurface,
                strokeWidth = 2.dp,
            )
        } else {
            val effectiveClick: () -> Unit
            if (isFailureState) {
                effectiveClick = onRetryClicked
            } else {
                effectiveClick = onPlayPauseClicked
            }
            IconButton(onClick = effectiveClick) {
                if (!isFailureState && nowPlaying.isPlaying) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = "Pause",
                        tint = ThumpColors.OnSurface,
                    )
                } else {
                    val iconTint: Color
                    val iconDescription: String
                    if (isFailureState) {
                        iconTint = UnavailableAccent
                        iconDescription = "Retry"
                    } else {
                        iconTint = ThumpColors.OnSurface
                        iconDescription = "Play"
                    }
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = iconDescription,
                        tint = iconTint,
                    )
                }
            }
        }
    }
}
