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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.therobm.thump.ThumpColors
import com.therobm.thump.subsonic.StandardArtistAlbum
import com.therobm.thump.subsonic.StandardArtistDetailPayload
import com.therobm.thump.subsonic.SubsonicClient
import com.therobm.thump.subsonic.SubsonicResult

private const val ARTIST_ART_REQUEST_SIZE: Int = 400
private const val ALBUM_ROW_ART_REQUEST_SIZE: Int = 200

@Composable
fun ArtistDetailScreen(
    artistId: String,
    subsonicClient: SubsonicClient,
    onBackPressed: () -> Unit,
    onAlbumSelected: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    var loadState: DetailLoadState<StandardArtistDetailPayload> by remember(artistId) {
        mutableStateOf(DetailLoadState.Loading)
    }

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
                    onAlbumSelected = onAlbumSelected,
                )
            }
        }
    }
}

@Composable
private fun ArtistDetailContent(
    artist: StandardArtistDetailPayload,
    subsonicClient: SubsonicClient,
    onAlbumSelected: (String) -> Unit,
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

