package com.andhab.cubelens.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.andhab.cubelens.core.cube.CubeColor

/**
 * CubeLens brand palette: "sunset on ink". Near-black backgrounds, a hot magenta → orange → gold
 * accent gradient, and mint for success. The cube's own sticker colors are the stars; the chrome
 * stays dark and warm so they pop. Deliberately avoids the stock blue/purple Material look.
 */
object Brand {
    // Backgrounds & surfaces
    val Ink = Color(0xFF0A0A10)
    val InkElevated = Color(0xFF111119)
    val Surface = Color(0xFF17171F)
    val SurfaceHigh = Color(0xFF1F1F2A)
    val SurfaceHighest = Color(0xFF292936)
    val Hairline = Color(0x1AFFFFFF)
    val HairlineStrong = Color(0x33FFFFFF)

    /** Fill of dark-glass surfaces (cards, secondary buttons) floating over the aurora. */
    val Glass = Color(0xB817171F)

    /** Slightly brighter glass for pressed or selected glass surfaces. */
    val GlassHigh = Color(0xCC24242F)

    // Text
    val TextPrimary = Color(0xFFF6F3FF)
    val TextSecondary = Color(0xFFADA8C2)

    /** Lowest-emphasis text (overlines, captions, step numbers): still ≥ 5:1 on Ink and Surface. */
    val TextTertiary = Color(0xFF8A85A3)

    /**
     * Text and icons placed on the sunset gradient. A deep wine-black rather than white: it keeps
     * at least 5:1 contrast across the whole gradient, including the gold end.
     */
    val OnAccent = Color(0xFF1C0812)

    // Accents
    val Magenta = Color(0xFFFF2E63)
    val Coral = Color(0xFFFF5A4E)
    val Tangerine = Color(0xFFFF7A18)
    val Gold = Color(0xFFFFC93C)
    val Mint = Color(0xFF2EE6A6)
    val Amber = Color(0xFFFFB547)
    val Danger = Color(0xFFFF4D6A)

    /** Primary accent gradient (left → right). */
    val SunsetBrush: Brush = Brush.linearGradient(listOf(Magenta, Tangerine, Gold))
    val SunsetColors: List<Color> = listOf(Magenta, Tangerine, Gold)

    /** Strictly horizontal sunset, for wide shapes such as buttons and progress bars. */
    val SunsetHorizontalBrush: Brush = Brush.horizontalGradient(SunsetColors)

    /** Hairline for glass edges: brighter at the top, as if lit from above. */
    val GlassBorderBrush: Brush = Brush.verticalGradient(listOf(Color(0x2EFFFFFF), Color(0x0FFFFFFF)))
}

/** Sticker colors used everywhere a cube is drawn. */
object CubePalette {
    val Body = Color(0xFF0D0D13)
    val BodyEdge = Color(0xFF1A1A22)
    val Unknown = Color(0xFF3A3A48)

    /** Display color of a sticker; null means "not set yet". */
    fun color(c: CubeColor?): Color = when (c) {
        null -> Unknown
        CubeColor.WHITE -> Color(0xFFF4F4F7)
        CubeColor.YELLOW -> Color(0xFFFFD500)
        CubeColor.GREEN -> Color(0xFF14C95E)
        CubeColor.BLUE -> Color(0xFF1F6BFF)
        CubeColor.RED -> Color(0xFFF2243C)
        CubeColor.ORANGE -> Color(0xFFFF7B00)
    }
}
