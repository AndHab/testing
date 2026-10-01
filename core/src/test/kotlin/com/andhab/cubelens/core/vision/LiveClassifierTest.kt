package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveClassifierTest {

    private fun sampleOf(srgb: IntArray, exposure: Double = 1.0, gains: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)): StickerSample {
        val c = IntArray(3) { ColorMath.linearToSrgb(ColorMath.srgbToLinear(srgb[it]) * exposure * gains[it]) }
        return StickerSample.of(c[0], c[1], c[2])
    }

    @Test
    fun referenceColorsClassifyAsThemselves() {
        for ((color, lab) in LiveClassifier.reference) {
            assertEquals(color, LiveClassifier.classify(StickerSample.ofArgb(ColorMath.labToArgb(lab))))
        }
    }

    @Test
    fun toleratesExposureAndWhiteBalance() {
        val whiteBalances = listOf(
            doubleArrayOf(1.0, 1.0, 1.0),
            doubleArrayOf(1.1, 1.0, 0.85), // warm
            doubleArrayOf(0.9, 1.0, 1.12), // cool
        )
        for ((color, srgb) in SyntheticFaces.BASE_SRGB) {
            for (exposure in listOf(0.6, 0.8, 1.0, 1.15, 1.3)) {
                for (wb in whiteBalances) {
                    val sample = sampleOf(srgb, exposure, wb)
                    assertEquals("$color at exposure $exposure, wb ${wb.toList()}: $sample", color, LiveClassifier.classify(sample))
                }
            }
        }
    }

    @Test
    fun separatesTheHardPairs() {
        // Dark, shaded red (real photo) vs a dim orange; greyish shaded white vs a dull yellow.
        assertEquals(CubeColor.RED, LiveClassifier.classify(StickerSample.of(107, 12, 17)))
        assertEquals(CubeColor.ORANGE, LiveClassifier.classify(StickerSample.of(170, 85, 20)))
        assertEquals(CubeColor.WHITE, LiveClassifier.classify(StickerSample.of(168, 166, 167)))
        assertEquals(CubeColor.YELLOW, LiveClassifier.classify(StickerSample.of(181, 164, 40)))
        assertEquals(CubeColor.WHITE, LiveClassifier.classify(StickerSample.of(255, 255, 255)))
        assertEquals(CubeColor.BLUE, LiveClassifier.classify(StickerSample.of(1, 44, 86)))
    }
}
