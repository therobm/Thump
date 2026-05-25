package com.therobm.thump

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Wraps Material3 with the Thump palette.
 *
 * Only a dark color scheme is provided — Thump never renders a light theme. The light slots in
 * Material3 fall back to the same colors so any composable that asks for them still gets brand
 * surfaces instead of Material defaults.
 */
@Composable
fun ThumpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = ThumpColors.Background,
            surface = ThumpColors.Surface,
            surfaceVariant = ThumpColors.SurfaceElevated,
            primary = ThumpColors.Accent,
            secondary = ThumpColors.Accent,
            tertiary = ThumpColors.Accent,
            onBackground = ThumpColors.OnBackground,
            onSurface = ThumpColors.OnSurface,
            onSurfaceVariant = ThumpColors.TextSecondary,
            onPrimary = ThumpColors.OnBackground,
            outline = ThumpColors.Divider,
        ),
        content = content,
    )
}
