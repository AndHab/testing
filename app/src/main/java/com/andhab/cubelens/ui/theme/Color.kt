package com.andhab.cubelens.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.andhab.cubelens.core.cube.CubeColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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

/**
 * The stock sticker colors and the cube body.
 *
 * Cube visuals read sticker colors through [LocalStickerPalette] (whose default,
 * [StickerPalette.Standard], uses these values), so a cube with non-standard stickers is drawn in
 * its own colors. Only the brand mark always uses these.
 */
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

/**
 * The display color of each sticker label of the cube being shown.
 *
 * A [CubeColor] is only a label: a knock-off cube may have pastel stickers where a standard cube
 * has vivid ones. Provide the user's colors through [LocalStickerPalette] and every cube visual (3D
 * cube, net, face thumbnails, sticker grids) draws in them. Labels missing from the map fall back
 * to [Standard]; `null` (a sticker not set yet) is always [CubePalette.Unknown].
 *
 * Palettes compare by value, so an equal palette never invalidates drawing caches.
 */
@Immutable
class StickerPalette(colors: Map<CubeColor, Color>) {
    private val table: Array<Color> = Array(CubeColor.entries.size) { i ->
        val label = CubeColor.entries[i]
        colors[label] ?: CubePalette.color(label)
    }
    private val finishes: Array<StickerFinish> = Array(table.size) { StickerFinish.of(table[it]) }

    /** Display color of a sticker; `null` means "not set yet". */
    fun color(c: CubeColor?): Color = if (c == null) CubePalette.Unknown else table[c.ordinal]

    /** How a sticker of label [c] is lit: gloss and shadow adapted to its lightness. */
    fun finish(c: CubeColor?): StickerFinish = if (c == null) UnknownFinish else finishes[c.ordinal]

    override fun equals(other: Any?): Boolean = other is StickerPalette && table.contentEquals(other.table)

    override fun hashCode(): Int = table.contentHashCode()

    override fun toString(): String =
        CubeColor.entries.joinToString(prefix = "StickerPalette(", postfix = ")") { "${it.letter}=${table[it.ordinal]}" }

    companion object {
        /** The stock vivid colors of [CubePalette]. */
        val Standard: StickerPalette = StickerPalette(CubeColor.entries.associateWith { CubePalette.color(it) })

        /** A palette from ARGB color ints (e.g. averaged from camera frames), one per label. */
        fun fromArgb(colors: Map<CubeColor, Int>): StickerPalette = StickerPalette(colors.mapValues { Color(it.value) })

        private val UnknownFinish = StickerFinish.of(CubePalette.Unknown)
    }
}

/**
 * The sticker colors cube visuals draw with. Defaults to [StickerPalette.Standard]; provide the
 * scanned cube's own colors for knock-off or pastel cubes. It is static: a palette change redraws
 * everything below the provider, which is what it needs anyway.
 */
val LocalStickerPalette = staticCompositionLocalOf { StickerPalette.Standard }

/**
 * How glossy sticker plastic of one [base] color is lit, so that every color reads as rich plastic
 * on the dark UI, vivid and pastel alike.
 *
 * Vivid colors and neutral white keep the classic recipe: a white gloss, a black corner shadow and
 * plain darkening on faces turned away from the light. Light, softly saturated colors (pastels)
 * would wash out under it: white gloss over pale pink reads as white, darkening turns it grey, and
 * darkened lemon or peach read as olive and brown. So in proportion to [pastel]:
 *  - the white gloss is gentler ([glossScale] < 1);
 *  - the corner shadow is a deep tint of the hue ([shadow]) and a little stronger ([shadeScale] > 1),
 *    which gives pale stickers shape instead of a grey smudge;
 *  - faces turned from the light darken less, deepen their saturation instead, and warm hues drift
 *    slightly towards red (lemon to gold, peach to coral) rather than to olive or brown
 *    ([shadeArgb]), like real pastel plastic in the shade.
 */
@Immutable
class StickerFinish private constructor(
    val base: Color,
    /** 0 for vivid or neutral colors, up to 1 for a light, softly saturated color. */
    val pastel: Float,
    /** Multiplier for the strength of the white gloss highlight. */
    val glossScale: Float,
    /** Multiplier for the strength of the corner shadow. */
    val shadeScale: Float,
    /** Color of the corner shadow: black for vivid colors, a deep tint of the hue for pastels. */
    val shadow: Color,
    private val hue: Float,
    private val saturation: Float,
    private val value: Float,
    /** Degrees a warm hue drifts towards red at full shade. */
    private val warmDrift: Float,
) {
    /**
     * [base] lit by [light] (0..1, 1 = fully lit) as packed ARGB: plain darkening (`rgb * light`) for
     * vivid colors; pastels darken less and grow richer instead of turning grey, olive or brown.
     * Allocation-free, so renderers may call it every frame.
     */
    fun shadeArgb(light: Float): Int {
        val dark = (1f - light.coerceIn(0f, 1f)) * (1f - PASTEL_LIGHT_KEPT * pastel)
        val s = min(1f, saturation * (1f + dark * PASTEL_SHADE_SATURATION * pastel))
        val h = (hue - warmDrift * dark + 360f) % 360f
        return hsvToArgb(h, s, value * (1f - dark), base.alpha)
    }

    /** [shadeArgb] as a [Color]. */
    fun shade(light: Float): Color = Color(shadeArgb(light))

    override fun equals(other: Any?): Boolean = other is StickerFinish && base == other.base

    override fun hashCode(): Int = base.hashCode()

    companion object {
        /**
         * Finishes of recently used colors, direct-mapped by color: per-frame callers (stickers drawn
         * every frame, even with animated colors) get them without recomputing or allocating. Races
         * are benign: entries are immutable and a lost write only costs a recomputation.
         */
        private val recent = arrayOfNulls<StickerFinish>(64)

        /** The finish for a sticker of [color]. */
        fun of(color: Color): StickerFinish {
            val slot = (color.value.hashCode() and Int.MAX_VALUE) % recent.size
            recent[slot]?.let { if (it.base == color) return it }
            return create(color).also { recent[slot] = it }
        }

        private fun create(color: Color): StickerFinish {
            val r = color.red
            val g = color.green
            val b = color.blue
            val maxC = max(r, max(g, b))
            val delta = maxC - min(r, min(g, b))
            val hue = when {
                delta == 0f -> 0f
                maxC == r -> 60f * (((g - b) / delta + 6f) % 6f)
                maxC == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            val saturation = if (maxC == 0f) 0f else delta / maxC
            val pastel = pastelness(color.luminance(), saturation)
            // Yellows and oranges (hue ~20..80) go olive or brown when darkened; let them warm up.
            val warm = smoothstep(15f, 30f, hue) * (1f - smoothstep(70f, 90f, hue))
            val warmDrift = WARM_DRIFT_DEGREES * pastel * warm
            val tintHue = (hue - warmDrift * 0.5f + 360f) % 360f
            val tint = Color(hsvToArgb(tintHue, min(1f, saturation * 1.6f + 0.12f), maxC * 0.4f, 1f))
            return StickerFinish(
                base = color,
                pastel = pastel,
                glossScale = 1f - 0.45f * pastel,
                shadeScale = 1f + 0.6f * pastel,
                shadow = Color(
                    red = tint.red * pastel,
                    green = tint.green * pastel,
                    blue = tint.blue * pastel,
                ),
                hue = hue,
                saturation = saturation,
                value = maxC,
                warmDrift = warmDrift,
            )
        }

        /**
         * How pastel a color is: light (high relative [luminance]) and softly saturated. Neutral
         * white and strongly saturated colors score 0, so the stock palette keeps its classic look.
         */
        internal fun pastelness(luminance: Float, saturation: Float): Float {
            val light = smoothstep(0.25f, 0.6f, luminance)
            val soft = smoothstep(0.08f, 0.2f, saturation) * (1f - smoothstep(0.62f, 0.82f, saturation))
            return light * soft
        }

        /** How much a pastel's saturation grows as it darkens, per unit of lost light. */
        private const val PASTEL_SHADE_SATURATION = 2f

        /** Fraction of the darkening a full pastel is spared, so it still reads as itself in the shade. */
        private const val PASTEL_LIGHT_KEPT = 0.35f

        /** How far (degrees, towards red) a warm pastel's hue drifts at full shade. */
        private const val WARM_DRIFT_DEGREES = 40f

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Packed ARGB of hue (0..360), saturation, value and alpha (all 0..1); allocation-free. */
        private fun hsvToArgb(hue: Float, saturation: Float, value: Float, alpha: Float): Int {
            val c = value * saturation
            val h = (hue / 60f).coerceIn(0f, 5.9999f)
            val x = c * (1f - abs(h % 2f - 1f))
            val m = value - c
            val sector = h.toInt()
            val r = when (sector) {
                0, 5 -> c
                1, 4 -> x
                else -> 0f
            }
            val g = when (sector) {
                1, 2 -> c
                0, 3 -> x
                else -> 0f
            }
            val b = when (sector) {
                3, 4 -> c
                2, 5 -> x
                else -> 0f
            }
            return (channel(alpha) shl 24) or (channel(r + m) shl 16) or (channel(g + m) shl 8) or channel(b + m)
        }

        private fun channel(v: Float): Int = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
