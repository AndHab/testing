package com.andhab.cubelens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private val CubeLensColorScheme = darkColorScheme(
    primary = Brand.Magenta,
    onPrimary = Brand.TextPrimary,
    primaryContainer = Brand.SurfaceHigh,
    onPrimaryContainer = Brand.TextPrimary,
    secondary = Brand.Tangerine,
    onSecondary = Brand.Ink,
    secondaryContainer = Brand.SurfaceHigh,
    onSecondaryContainer = Brand.TextPrimary,
    tertiary = Brand.Mint,
    onTertiary = Brand.Ink,
    background = Brand.Ink,
    onBackground = Brand.TextPrimary,
    surface = Brand.Surface,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Brand.SurfaceHigh,
    onSurfaceVariant = Brand.TextSecondary,
    surfaceContainerLowest = Brand.Ink,
    surfaceContainerLow = Brand.InkElevated,
    surfaceContainer = Brand.Surface,
    surfaceContainerHigh = Brand.SurfaceHigh,
    surfaceContainerHighest = Brand.SurfaceHighest,
    outline = Brand.HairlineStrong,
    outlineVariant = Brand.Hairline,
    error = Brand.Danger,
    onError = Brand.TextPrimary,
    scrim = Brand.Ink,
)

val CubeLensShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val CubeLensSelectionColors = TextSelectionColors(
    handleColor = Brand.Tangerine,
    backgroundColor = Brand.Tangerine.copy(alpha = 0.35f),
)

/**
 * The app is dark-only by design: the brand lives on an ink background.
 *
 * Besides the Material theme this provides [Brand.TextPrimary] as the default content color, so
 * plain `Text` placed directly on an [com.andhab.cubelens.ui.components.AuroraBackground] is
 * readable without wrapping it in a Material `Surface`.
 */
@Composable
fun CubeLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CubeLensColorScheme,
        typography = CubeLensTypography,
        shapes = CubeLensShapes,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides Brand.TextPrimary,
            LocalTextSelectionColors provides CubeLensSelectionColors,
            content = content,
        )
    }
}
