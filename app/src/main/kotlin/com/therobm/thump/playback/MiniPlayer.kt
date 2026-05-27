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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.therobm.thump.ThumpColors
import com.therobm.thump.art.ArtImage
import com.therobm.thump.data.ThumpData

private const val MINI_PLAYER_ART_REQUEST_SIZE_PX: Int = 150

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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ThumpColors.SurfaceElevated)
            .clickable(onClick = onExpandClicked)
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
            if (nowPlaying.artist.isNotEmpty()) {
                Text(
                    text = nowPlaying.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = ThumpColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

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
