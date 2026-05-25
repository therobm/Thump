package com.therobm.thump.art

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import coil.compose.AsyncImage
import com.therobm.thump.ThumpColors
import com.therobm.thump.subsonic.SubsonicClient

/**
 * Render a square tile whose contents are a composite of the supplied cover-art IDs.
 *
 * Mirrors the Pulse web client's `buildCompositeArt`: empty list shows a music-note glyph,
 * one ID shows a single image, two or more IDs lay out as a 2x2 grid that cycles the available
 * IDs into four cells. The caller supplies the SubsonicClient so the tile can build the
 * authenticated /rest/getCoverArt URLs itself.
 *
 * `requestSizePx` is the per-quadrant size hint to ask the server for when the tile is showing
 * the 2x2 grid (each quadrant only paints half the tile so it does not need full-size art).
 * Single-image mode uses the same hint for the whole tile, which slightly overshoots but
 * keeps the call simple.
 */
@Composable
fun CompositeArtTile(
    coverArtIds: List<String>,
    subsonicClient: SubsonicClient,
    requestSizePx: Int,
    modifier: Modifier,
) {
    val uniqueIds = pickUniqueIds(coverArtIds, MAX_UNIQUE_IDS)

    if (uniqueIds.isEmpty()) {
        EmptyPlaceholder(modifier = modifier)
        return
    }

    if (uniqueIds.size == 1) {
        AsyncImage(
            model = subsonicClient.buildCoverArtUrl(uniqueIds[0], requestSizePx),
            contentDescription = null,
            modifier = modifier.background(ThumpColors.Surface),
        )
        return
    }

    val displayIds = ArrayList<String>(4)
    val uniqueCount = uniqueIds.size
    for (slotIndex in 0 until 4) {
        displayIds.add(uniqueIds[slotIndex % uniqueCount])
    }

    Column(modifier = modifier.background(ThumpColors.Surface)) {
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            QuadrantImage(
                coverArtId = displayIds[0],
                subsonicClient = subsonicClient,
                requestSizePx = requestSizePx,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
            QuadrantImage(
                coverArtId = displayIds[1],
                subsonicClient = subsonicClient,
                requestSizePx = requestSizePx,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            QuadrantImage(
                coverArtId = displayIds[2],
                subsonicClient = subsonicClient,
                requestSizePx = requestSizePx,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
            QuadrantImage(
                coverArtId = displayIds[3],
                subsonicClient = subsonicClient,
                requestSizePx = requestSizePx,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
    }
}

@Composable
private fun QuadrantImage(
    coverArtId: String,
    subsonicClient: SubsonicClient,
    requestSizePx: Int,
    modifier: Modifier,
) {
    AsyncImage(
        model = subsonicClient.buildCoverArtUrl(coverArtId, requestSizePx),
        contentDescription = null,
        modifier = modifier,
    )
}

@Composable
private fun EmptyPlaceholder(modifier: Modifier) {
    Box(
        modifier = modifier.background(ThumpColors.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "♫",
            style = MaterialTheme.typography.titleLarge,
            color = ThumpColors.TextSecondary,
        )
    }
}

/**
 * Walk the cover-art IDs in source order, returning the first `limit` distinct values. Null /
 * empty entries are skipped so a partial track list with missing cover art still produces a
 * usable composite.
 */
private fun pickUniqueIds(coverArtIds: List<String>, limit: Int): List<String> {
    val picked = ArrayList<String>(limit)
    val seen = HashSet<String>(limit * 2)
    val total = coverArtIds.size
    for (sourceIndex in 0 until total) {
        if (picked.size >= limit) {
            return picked
        }
        val candidate = coverArtIds[sourceIndex]
        if (candidate.isEmpty()) {
            continue
        }
        if (seen.contains(candidate)) {
            continue
        }
        seen.add(candidate)
        picked.add(candidate)
    }
    return picked
}

private const val MAX_UNIQUE_IDS: Int = 4
