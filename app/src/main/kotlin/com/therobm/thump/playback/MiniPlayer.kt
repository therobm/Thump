package com.therobm.thump.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
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
 * Tapping the play/pause button toggles state. Tapping the rest of the bar will open the full
 * now-playing screen once that exists; for now the row just renders metadata.
 */
@Composable
fun MiniPlayer(
    nowPlaying: NowPlaying,
    thumpData: ThumpData,
    onPlayPauseClicked: () -> Unit,
    onExpandClicked: () -> Unit,
) {
    val unavailableReason: String? = nowPlaying.unavailableReason
    val rowModifier: Modifier
    if (unavailableReason == null) {
        rowModifier = Modifier
            .fillMaxWidth()
            .background(ThumpColors.SurfaceElevated)
            .clickable(onClick = onExpandClicked)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    } else {
        // No expand affordance — the track cannot play, so opening the full screen would offer
        // controls that don't do anything. The row still renders so the user sees what they
        // tapped and the reason text.
        rowModifier = Modifier
            .fillMaxWidth()
            .background(ThumpColors.SurfaceElevated)
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
                Text(
                    text = unavailableReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = UnavailableAccent,
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

        if (unavailableReason != null) {
            // IconButton-sized box so the row height matches the playable variant and the icon
            // sits where the play/pause button would normally sit. Not clickable — tapping it
            // would just retrigger the failing prefetch.
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = unavailableReason,
                    tint = UnavailableAccent,
                )
            }
        } else {
            IconButton(onClick = onPlayPauseClicked) {
                if (nowPlaying.isPlaying) {
                    Icon(
                        imageVector = Icons.Filled.Pause,
                        contentDescription = "Pause",
                        tint = ThumpColors.OnSurface,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = ThumpColors.OnSurface,
                    )
                }
            }
        }
    }
}
