package com.therobm.thump

import androidx.compose.ui.graphics.Color

/**
 * The Thump palette, picked to match the Pulse web UI. Dark theme only.
 *
 * Defined as plain Color objects so they can be referenced from both the Material theme and any
 * one-off composable that needs a brand surface. Nothing here changes at runtime.
 */
object ThumpColors {
    val Background: Color = Color(0xFF1A1D2E)
    val Surface: Color = Color(0xFF252840)
    val SurfaceElevated: Color = Color(0xFF2E3252)
    val Accent: Color = Color(0xFF4A7CF7)
    val OnBackground: Color = Color(0xFFF5F5F8)
    val OnSurface: Color = Color(0xFFF5F5F8)
    val TextSecondary: Color = Color(0xFF9999AA)
    val Divider: Color = Color(0xFF353958)
}
