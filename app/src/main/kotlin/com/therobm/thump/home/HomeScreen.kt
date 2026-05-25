package com.therobm.thump.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import com.therobm.thump.subsonic.SubsonicClient
import kotlinx.coroutines.launch

/**
 * The home screen: a vertical scroll of horizontal carousels.
 *
 * Sections load in parallel and render independently. A failed section shows its error message
 * inline so the rest of the screen stays usable.
 */
@Composable
fun HomeScreen(
    subsonicClient: SubsonicClient,
    isPulseServer: Boolean,
    contentPadding: PaddingValues,
    onItemTapped: (HomeCarouselItem) -> Unit,
    modifier: Modifier,
) {
    val repository = remember(subsonicClient, isPulseServer) {
        HomeRepository(subsonicClient, isPulseServer)
    }

    val initialSections = remember(isPulseServer) {
        buildInitialSections(isPulseServer)
    }
    var sections by remember(repository) { mutableStateOf(initialSections) }

    LaunchedEffect(repository) {
        val sectionKeys = HomeSectionKey.values()
        val keyCount = sectionKeys.size
        for (keyIndex in 0 until keyCount) {
            val key = sectionKeys[keyIndex]
            launch {
                val loaded = repository.loadSection(key)
                sections = replaceSection(sections, loaded)
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ThumpColors.Background),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
        }
        items(items = sections, key = { section -> section.key.name }) { section ->
            HomeSectionView(
                section = section,
                subsonicClient = subsonicClient,
                onItemTapped = onItemTapped,
            )
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HomeSectionView(
    section: HomeSection,
    subsonicClient: SubsonicClient,
    onItemTapped: (HomeCarouselItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = section.title,
            style = MaterialTheme.typography.titleLarge,
            color = ThumpColors.OnBackground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val loadState = section.loadState
        when (loadState) {
            is HomeSectionLoadState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = ThumpColors.Accent)
                }
            }
            is HomeSectionLoadState.Failed -> {
                Text(
                    text = loadState.message,
                    color = ThumpColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            is HomeSectionLoadState.Loaded -> {
                if (loadState.items.isEmpty()) {
                    Text(
                        text = "Nothing here yet",
                        color = ThumpColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else {
                    HomeCarouselRow(
                        items = loadState.items,
                        subsonicClient = subsonicClient,
                        onItemTapped = onItemTapped,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeCarouselRow(
    items: List<HomeCarouselItem>,
    subsonicClient: SubsonicClient,
    onItemTapped: (HomeCarouselItem) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = items, key = { item -> item.kind.name + ":" + item.id }) { item ->
            HomeCarouselTile(
                item = item,
                subsonicClient = subsonicClient,
                onTapped = { onItemTapped(item) },
            )
        }
    }
}

@Composable
private fun HomeCarouselTile(
    item: HomeCarouselItem,
    subsonicClient: SubsonicClient,
    onTapped: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onTapped),
    ) {
        val coverArtId = item.coverArtId
        val artUrl: String?
        if (coverArtId == null) {
            artUrl = null
        } else {
            artUrl = subsonicClient.buildCoverArtUrl(coverArtId, COVER_ART_REQUEST_SIZE)
        }

        if (item.kind == HomeItemKind.Artist) {
            ArtistTileArt(artUrl = artUrl)
        } else {
            RectangularTileArt(artUrl = artUrl)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyMedium,
            color = ThumpColors.OnBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item.subtitle.isNotEmpty()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = ThumpColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RectangularTileArt(artUrl: String?) {
    val tileModifier = Modifier
        .size(140.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(ThumpColors.Surface)
    if (artUrl == null) {
        Box(modifier = tileModifier)
    } else {
        AsyncImage(
            model = artUrl,
            contentDescription = null,
            modifier = tileModifier,
        )
    }
}

@Composable
private fun ArtistTileArt(artUrl: String?) {
    val tileModifier = Modifier
        .size(140.dp)
        .clip(CircleShape)
        .background(ThumpColors.Surface)
    if (artUrl == null) {
        Box(modifier = tileModifier)
    } else {
        AsyncImage(
            model = artUrl,
            contentDescription = null,
            modifier = tileModifier,
        )
    }
}

private fun buildInitialSections(isPulseServer: Boolean): List<HomeSection> {
    val sections = ArrayList<HomeSection>(5)
    sections.add(HomeSection(HomeSectionKey.RecentlyPlayed, "Recently Played", HomeSectionLoadState.Loading))
    if (isPulseServer) {
        sections.add(HomeSection(HomeSectionKey.Playlists, "Your Playlists", HomeSectionLoadState.Loading))
        sections.add(HomeSection(HomeSectionKey.PopularOrFrequent, "Popular Artists", HomeSectionLoadState.Loading))
    } else {
        sections.add(HomeSection(HomeSectionKey.Playlists, "Playlists", HomeSectionLoadState.Loading))
        sections.add(HomeSection(HomeSectionKey.PopularOrFrequent, "Most Played", HomeSectionLoadState.Loading))
    }
    sections.add(HomeSection(HomeSectionKey.RecentlyAdded, "Recently Added", HomeSectionLoadState.Loading))
    sections.add(HomeSection(HomeSectionKey.Favorites, "Favorites", HomeSectionLoadState.Loading))
    return sections
}

private fun replaceSection(current: List<HomeSection>, replacement: HomeSection): List<HomeSection> {
    val result = ArrayList<HomeSection>(current.size)
    val currentCount = current.size
    for (sectionIndex in 0 until currentCount) {
        val existing = current[sectionIndex]
        if (existing.key == replacement.key) {
            result.add(replacement)
        } else {
            result.add(existing)
        }
    }
    return result
}

private const val COVER_ART_REQUEST_SIZE: Int = 300
