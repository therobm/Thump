package com.therobm.thump.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.therobm.thump.ThumpColors

/**
 * Placeholder for the Search tab.
 *
 * Real search will call search3 against the server. For now it shows a message so the bottom
 * nav has somewhere to go.
 */
@Composable
fun SearchScreen(contentPadding: PaddingValues, modifier: Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ThumpColors.Background)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Search — coming next",
            color = ThumpColors.TextSecondary,
        )
    }
}
