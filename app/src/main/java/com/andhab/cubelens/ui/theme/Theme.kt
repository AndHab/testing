package com.andhab.cubelens.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
)

val CubeLensShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** The app is dark-only by design: the brand lives on an ink background. */
@Composable
fun CubeLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CubeLensColorScheme,
        typography = CubeLensTypography,
        shapes = CubeLensShapes,
        content = content,
    )
}
