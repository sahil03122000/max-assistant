package com.max.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Hamesha dark, Max ke brand rang — voice assistant ke liye premium look (Google Assistant / ChatGPT jaisa). */
private val MaxColors = darkColorScheme(
    primary = MaxViolet,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2E2063),
    onPrimaryContainer = MaxText,
    secondary = MaxCyan,
    onSecondary = MaxInk,
    tertiary = MaxPink,
    tertiaryContainer = Color(0xFF4A2A63),
    background = MaxInk,
    onBackground = MaxText,
    surface = MaxSurface,
    onSurface = MaxText,
    surfaceVariant = MaxSurfaceHigh,
    onSurfaceVariant = MaxTextDim,
    surfaceContainer = MaxSurface,
    surfaceContainerHigh = MaxSurfaceHigh,
    surfaceContainerHighest = MaxSurfaceHigh,
    outline = MaxLine,
    outlineVariant = MaxLine,
    error = Color(0xFFF87171),
)

@Composable
fun MaxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MaxColors, typography = Typography, content = content)
}
