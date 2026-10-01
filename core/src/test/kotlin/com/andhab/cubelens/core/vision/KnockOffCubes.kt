package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor

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

    /** The design color of each look's stickers, for comparing estimated palettes with. */
    val DESIGN_HEX: Map<CubeLook, Map<CubeColor, Int>> = mapOf(
        VIVID to BRAND_HEX,
        PASTEL to PASTEL_HEX,
        CANDY to CANDY_HEX,
        MUTED to MUTED_HEX,
        STICKERLESS to STICKERLESS_HEX,
        WHITE_BODY to BRAND_HEX,
    )
}
