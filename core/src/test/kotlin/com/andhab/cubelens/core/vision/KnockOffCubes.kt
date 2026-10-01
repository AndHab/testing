package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A cube's appearance for [SyntheticFaces]: its sticker colors as a phone camera captures them in
 * neutral light at a typical auto exposure, and what shows between the stickers.
 */
data class CubeLook(
    val name: String,
    /** sRGB (0..255) of each sticker color as captured. */
    val srgb: Map<CubeColor, IntArray>,
    val body: SyntheticFaces.Body = SyntheticFaces.Body.BLACK,
) {
    /** [srgb] in linear light. */
    val linear: Map<CubeColor, DoubleArray> = srgb.mapValues { (_, c) -> DoubleArray(3) { ColorMath.srgbToLinear(c[it]) } }

    /** An ideal (noise-free, neutral light) sample of [color]. */
    fun sample(color: CubeColor): StickerSample = srgb.getValue(color).let { StickerSample.of(it[0], it[1], it[2]) }

    override fun toString(): String = name
}

/** Sticker colors of knock-off and special cubes, for tests. */
object KnockOffCubes {

    /** Design colors (0xRRGGBB) of a pastel knock-off, like the user's daughter's cube. */
    val PASTEL_HEX: Map<CubeColor, Int> = mapOf(
        CubeColor.WHITE to 0xF7F5EE, // warm white
        CubeColor.YELLOW to 0xF3E58A, // lemon
        CubeColor.GREEN to 0x9EDDB0, // mint
        CubeColor.BLUE to 0x93BFEA, // baby blue
        CubeColor.RED to 0xF2A0B4, // pink
        CubeColor.ORANGE to 0xF7BE92, // peach / apricot
    )

    /** A bright "candy" knock-off: hot-pink red, sky blue, lime-ish green. */
    val CANDY_HEX: Map<CubeColor, Int> = mapOf(
        CubeColor.WHITE to 0xFFFFFF,
        CubeColor.YELLOW to 0xFFEE33,
        CubeColor.GREEN to 0x33DD77,
        CubeColor.BLUE to 0x3399FF,
        CubeColor.RED to 0xFF3366,
        CubeColor.ORANGE to 0xFF9933,
    )

    /** A darker candy variant: raspberry red, deeper sky blue. */
    val DARK_CANDY_HEX: Map<CubeColor, Int> = mapOf(
        CubeColor.WHITE to 0xF2F2F2,
        CubeColor.YELLOW to 0xF5D80F,
        CubeColor.GREEN to 0x1FBF5F,
        CubeColor.BLUE to 0x1F7FE0,
        CubeColor.RED to 0xE0245A,
        CubeColor.ORANGE to 0xF07A1F,
    )

    /** A muted / matte cube: ivory, mustard, sage, slate blue, brick, terracotta. */
    val MUTED_HEX: Map<CubeColor, Int> = mapOf(
        CubeColor.WHITE to 0xE6E1D3,
        CubeColor.YELLOW to 0xD9C25A,
        CubeColor.GREEN to 0x5E9E6E,
        CubeColor.BLUE to 0x4F74A8,
        CubeColor.RED to 0xB04A4A,
        CubeColor.ORANGE to 0xD98545,
    )

    /** Bright stickerless plastic. */
    val STICKERLESS_HEX: Map<CubeColor, Int> = mapOf(
        CubeColor.WHITE to 0xF5F5F5,
        CubeColor.YELLOW to 0xFFE800,
        CubeColor.GREEN to 0x00D44A,
        CubeColor.BLUE to 0x0A6CFF,
        CubeColor.RED to 0xF5142B,
        CubeColor.ORANGE to 0xFF7A00,
    )

    /** The commonly published nominal Rubik's brand colors. */
    val BRAND_HEX: Map<CubeColor, Int> = mapOf(
        CubeColor.WHITE to 0xFFFFFF,
        CubeColor.YELLOW to 0xFFD500,
        CubeColor.GREEN to 0x009B48,
        CubeColor.BLUE to 0x0046AD,
        CubeColor.RED to 0xB71234,
        CubeColor.ORANGE to 0xFF5800,
    )

    // Other pastel knock-offs, so that nothing is tuned to PASTEL_HEX alone.

    /** Pastel with a lavender "blue". */
    val LAVENDER_HEX: Map<CubeColor, Int> = PASTEL_HEX + (CubeColor.BLUE to 0xC8B6E2)

    /** Pastel with a coral-pink red and a saturated pastel orange (#FFB347, close to yellow). */
    val CORAL_HEX: Map<CubeColor, Int> = PASTEL_HEX + mapOf(CubeColor.RED to 0xF88379, CubeColor.ORANGE to 0xFFB347)

    /** Pastel with a very light yellow (#FFF8C6), close to white. */
    val LIGHT_YELLOW_HEX: Map<CubeColor, Int> = PASTEL_HEX + (CubeColor.YELLOW to 0xFFF8C6)

    /** Pastel with a cyan-ish mint and a very light sky blue, close to each other and to white. */
    val MINT_SKY_HEX: Map<CubeColor, Int> = PASTEL_HEX + mapOf(CubeColor.GREEN to 0xAAF0D1, CubeColor.BLUE to 0xA7C7E7)

    /** "Macaron" pastels as sold by several brands. */
    val MACARON_HEX: Map<CubeColor, Int> = mapOf(
        CubeColor.WHITE to 0xFAFAFA,
        CubeColor.YELLOW to 0xFFF68F,
        CubeColor.GREEN to 0x8FE3B0,
        CubeColor.BLUE to 0x8EC9F0,
        CubeColor.RED to 0xF59BB6,
        CubeColor.ORANGE to 0xFFC48C,
    )

    /** Washed-out pastels with a greyish blue (#AEC6CF, chroma about 9) and a very light peach. */
    val GREYISH_HEX: Map<CubeColor, Int> = mapOf(
        CubeColor.WHITE to 0xF5F5F0,
        CubeColor.YELLOW to 0xFDFD96,
        CubeColor.GREEN to 0xB2D8B2,
        CubeColor.BLUE to 0xAEC6CF,
        CubeColor.RED to 0xFFB7C5,
        CubeColor.ORANGE to 0xFFD1A4,
    )

    /** [PASTEL_HEX] mixed 35% (in linear light) with its white: chroma down to about 10-30. */
    val PALE_HEX: Map<CubeColor, Int> = towardsWhite(PASTEL_HEX, 0.35)

    /** [hex] mixed with its own white by [fraction] in linear light. */
    fun towardsWhite(hex: Map<CubeColor, Int>, fraction: Double): Map<CubeColor, Int> {
        val white = hex.getValue(CubeColor.WHITE)
        return hex.mapValues { (_, rgb) ->
            var mixed = 0
            for (shift in intArrayOf(16, 8, 0)) {
                val c = ColorMath.srgbToLinear((rgb shr shift) and 0xFF)
                val w = ColorMath.srgbToLinear((white shr shift) and 0xFF)
                mixed = mixed or (ColorMath.linearToSrgb(c * (1 - fraction) + w * fraction) shl shift)
            }
            mixed
        }
    }

    /**
     * A random but plausible pastel palette (0xRRGGBB per color): each color's CIELAB hue drawn from
     * the range pastel cubes use for it (pink to coral reds, peach to apricot oranges, lemon yellows,
     * mint to sage greens, sky blue to lavender blues), light and of low chroma, with neighbouring
     * colors at least [MIN_HUE_GAP] degrees apart; an off-white white.
     */
    fun randomPastel(random: Random): Map<CubeColor, Int> {
        while (true) {
            val hue = mapOf(
                CubeColor.RED to random.nextDouble(-20.0, 30.0),
                CubeColor.ORANGE to random.nextDouble(50.0, 75.0),
                CubeColor.YELLOW to random.nextDouble(88.0, 108.0),
                CubeColor.GREEN to random.nextDouble(130.0, 175.0),
                CubeColor.BLUE to random.nextDouble(215.0, 300.0),
            )
            val order = listOf(CubeColor.RED, CubeColor.ORANGE, CubeColor.YELLOW, CubeColor.GREEN, CubeColor.BLUE)
            if (order.zipWithNext().any { (a, b) -> hue.getValue(b) - hue.getValue(a) < MIN_HUE_GAP }) continue
            val lab = mapOf(
                CubeColor.WHITE to Lab(random.nextDouble(95.0, 98.5).toFloat(), random.nextDouble(-1.0, 1.0).toFloat(), random.nextDouble(-2.0, 4.0).toFloat()),
                CubeColor.RED to lch(random.nextDouble(70.0, 82.0), random.nextDouble(22.0, 40.0), hue.getValue(CubeColor.RED)),
                CubeColor.ORANGE to lch(random.nextDouble(78.0, 88.0), random.nextDouble(22.0, 40.0), hue.getValue(CubeColor.ORANGE)),
                CubeColor.YELLOW to lch(random.nextDouble(88.0, 95.0), random.nextDouble(28.0, 50.0), hue.getValue(CubeColor.YELLOW)),
                CubeColor.GREEN to lch(random.nextDouble(78.0, 88.0), random.nextDouble(20.0, 38.0), hue.getValue(CubeColor.GREEN)),
                CubeColor.BLUE to lch(random.nextDouble(70.0, 82.0), random.nextDouble(18.0, 35.0), hue.getValue(CubeColor.BLUE)),
            )
            return lab.mapValues { (_, c) -> inGamutRgb(c) }
        }
    }

    /** Smallest CIELAB hue step between neighbouring colors of [randomPastel] palettes (degrees). */
    const val MIN_HUE_GAP = 22.0

    private fun lch(l: Double, c: Double, hDegrees: Double): Lab {
        val h = Math.toRadians(hDegrees)
        return Lab(l.toFloat(), (c * cos(h)).toFloat(), (c * sin(h)).toFloat())
    }

    /** [lab] as 0xRRGGBB, with chroma reduced until it fits into sRGB. */
    private fun inGamutRgb(lab: Lab): Int {
        var c = lab
        while (ColorMath.labToLinear(c).any { it < 0.0 || it > 1.0 }) c = Lab(c.l, c.a * 0.97f, c.b * 0.97f)
        return ColorMath.labToArgb(c) and 0xFFFFFF
    }

    /**
     * [hex] design colors as a camera captures them at a typical auto exposure: [exposure] scales
     * linear light (0.62 puts a pure white sticker at sRGB 206, like [SyntheticFaces.Palette.NOMINAL]).
     */
    fun captured(hex: Map<CubeColor, Int>, exposure: Double = 0.62): Map<CubeColor, IntArray> = hex.mapValues { (_, rgb) ->
        IntArray(3) { ch -> ColorMath.linearToSrgb(ColorMath.srgbToLinear((rgb shr (16 - 8 * ch)) and 0xFF) * exposure) }
    }

    val VIVID = CubeLook("vivid standard", SyntheticFaces.Palette.NOMINAL.srgb)
    val PASTEL = CubeLook("pastel", captured(PASTEL_HEX, 0.66))
    val CANDY = CubeLook("candy", captured(CANDY_HEX))
    val MUTED = CubeLook("muted", captured(MUTED_HEX, 0.7))
    val STICKERLESS = CubeLook("stickerless", captured(STICKERLESS_HEX, 0.64), SyntheticFaces.Body.STICKERLESS)
    val WHITE_BODY = CubeLook("white body", SyntheticFaces.Palette.NOMINAL.srgb, SyntheticFaces.Body.WHITE)

    /** Every look the knock-off tests cover. */
    val ALL = listOf(VIVID, PASTEL, CANDY, MUTED, STICKERLESS, WHITE_BODY)

    val LAVENDER = CubeLook("lavender pastel", captured(LAVENDER_HEX, 0.66))
    val CORAL = CubeLook("coral pastel", captured(CORAL_HEX, 0.66))
    val LIGHT_YELLOW = CubeLook("light yellow pastel", captured(LIGHT_YELLOW_HEX, 0.66))
    val MINT_SKY = CubeLook("mint and sky pastel", captured(MINT_SKY_HEX, 0.66))
    val MACARON = CubeLook("macaron", captured(MACARON_HEX, 0.66))
    val GREYISH = CubeLook("greyish pastel", captured(GREYISH_HEX, 0.66))
    val PALE = CubeLook("pale pastel", captured(PALE_HEX, 0.66))

    /** Pastel looks other than [PASTEL], for checking that nothing is tuned to one palette. */
    val OTHER_PASTELS = listOf(LAVENDER, CORAL, LIGHT_YELLOW, MINT_SKY, MACARON, GREYISH, PALE)

    /** The design color of each look's stickers, for comparing estimated palettes with. */
    val DESIGN_HEX: Map<CubeLook, Map<CubeColor, Int>> = mapOf(
        VIVID to BRAND_HEX,
        PASTEL to PASTEL_HEX,
        CANDY to CANDY_HEX,
        MUTED to MUTED_HEX,
        STICKERLESS to STICKERLESS_HEX,
        WHITE_BODY to BRAND_HEX,
        LAVENDER to LAVENDER_HEX,
        CORAL to CORAL_HEX,
        LIGHT_YELLOW to LIGHT_YELLOW_HEX,
        MINT_SKY to MINT_SKY_HEX,
        MACARON to MACARON_HEX,
        GREYISH to GREYISH_HEX,
        PALE to PALE_HEX,
    )
}
