package com.andhab.cubelens.ui.scan

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.vision.ColorMath
import com.andhab.cubelens.core.vision.Lab
import com.andhab.cubelens.core.vision.LiveClassifier
import com.andhab.cubelens.core.vision.StickerSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LearnedPaletteTest {

    private val colors = CubeColor.entries

    @Test
    fun nothingCapturedKeepsTheStockColors() {
        assertEquals(emptyMap<CubeColor, Int>(), LearnedPalette.estimate(emptyList(), emptyList()))
    }

    @Test
    fun aStandardCubeKeepsTheStockColors() {
        // The camera's typical view of standard stickers, a little darker and brighter than usual.
        for (exposure in listOf(0.6, 1.0, 1.5)) {
            val face = colors.map { exposed(ColorMath.labToArgb(LiveClassifier.reference.getValue(it)), exposure) }
            assertEquals("exposure $exposure", emptyMap<CubeColor, Int>(), LearnedPalette.estimate(listOf(face), listOf(colors)))
        }
    }

    @Test
    fun aPastelCubeIsShownInItsOwnLightSoftColors() {
        val face = colors.map { exposed(Pastel.getValue(it), 0.66) }
        val palette = LearnedPalette.estimate(listOf(face), listOf(colors))
        assertEquals(colors.toSet(), palette.keys)
        for (color in colors) {
            val shown = lab(palette.getValue(color))
            val design = lab(Pastel.getValue(color))
            assertTrue("$color light: $shown", shown.l > 75f)
            assertTrue("$color soft: $shown", shown.chroma < 55f)
            if (color != CubeColor.WHITE) assertTrue("$color keeps its hue: $shown vs $design", hueDifference(shown, design) < 12f)
        }
        // White-balanced on the cube's own (warm) white: it shows as a clean white.
        assertTrue(lab(palette.getValue(CubeColor.WHITE)).chroma < 4f)
    }

    @Test
    fun onlyTheColorsSeenSoFarAreLearned() {
        val labels = listOf(CubeColor.RED, CubeColor.RED, CubeColor.GREEN, CubeColor.WHITE)
        val face = labels.map { exposed(Pastel.getValue(it), 0.66) }
        assertEquals(setOf(CubeColor.RED, CubeColor.GREEN, CubeColor.WHITE), LearnedPalette.estimate(listOf(face), listOf(labels)).keys)
    }

    @Test
    fun aStrayMisreadStickerDoesNotTintAColor() {
        // Five pink stickers and one mint sticker misread as red: red stays pink.
        val labels = List(6) { CubeColor.RED } + CubeColor.WHITE
        val face = List(5) { exposed(Pastel.getValue(CubeColor.RED), 0.66) } + exposed(Pastel.getValue(CubeColor.GREEN), 0.66) + exposed(Pastel.getValue(CubeColor.WHITE), 0.66)
        val red = lab(LearnedPalette.estimate(listOf(face), listOf(labels)).getValue(CubeColor.RED))
        assertTrue("pink, not grey: $red", hueDifference(red, lab(Pastel.getValue(CubeColor.RED))) < 12f && red.chroma > 15f)
    }

    @Test
    fun aDimCaptureWithoutWhiteIsBrightenedALittle() {
        val labels = listOf(CubeColor.GREEN, CubeColor.RED)
        val dim = labels.map { exposed(Pastel.getValue(it), 0.3) }
        val palette = LearnedPalette.estimate(listOf(dim), listOf(labels))
        for (color in labels) assertTrue("$color", lab(palette.getValue(color)).l > lab(dim[labels.indexOf(color)].argb).l + 5f)
    }

    private fun exposed(rgb: Int, exposure: Double): StickerSample {
        fun channel(shift: Int) = ColorMath.linearToSrgb(ColorMath.srgbToLinear((rgb shr shift) and 0xFF) * exposure)
        return StickerSample.of(channel(16), channel(8), channel(0))
    }

    private fun lab(argb: Int): Lab = ColorMath.srgbToLab((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    private fun hueDifference(x: Lab, y: Lab): Float {
        val d = abs(x.hue - y.hue) % 360f
        return if (d > 180f) 360f - d else d
    }

    private companion object {
        /** A pastel knock-off's design colors: warm white, lemon, mint, baby blue, pink, peach. */
        val Pastel: Map<CubeColor, Int> = mapOf(
            CubeColor.WHITE to 0xF7F5EE, CubeColor.YELLOW to 0xF3E58A, CubeColor.GREEN to 0x9EDDB0,
            CubeColor.BLUE to 0x93BFEA, CubeColor.RED to 0xF2A0B4, CubeColor.ORANGE to 0xF7BE92,
        )
    }
}
