package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

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

    /** White-balance gains (R, G, B) from neutral to strong casts that auto white balance left uncorrected. */
    private val casts = linkedMapOf(
        "neutral" to doubleArrayOf(1.0, 1.0, 1.0),
        "warm" to doubleArrayOf(1.12, 1.0, 0.8),
        "cool" to doubleArrayOf(0.88, 1.0, 1.16),
        "strong warm" to doubleArrayOf(1.3, 1.0, 0.65),
        "very warm" to doubleArrayOf(1.45, 1.0, 0.5),
        "extreme warm" to doubleArrayOf(1.6, 1.0, 0.4),
        "strong cool" to doubleArrayOf(0.8, 1.0, 1.3),
        "green tint" to doubleArrayOf(0.9, 1.1, 0.9),
        "magenta tint" to doubleArrayOf(1.08, 0.9, 1.08),
    )

    @Test
    fun toleratesExposureAndWhiteBalance() {
        // NOMINAL (brand colors) is held out: the references were not derived from it. PHOTO is close
        // to the calibration data, so for it this is a calibration check.
        for (palette in SyntheticFaces.Palette.entries) {
            for ((color, srgb) in palette.srgb) {
                for (exposure in listOf(0.2, 0.4, 0.6, 0.8, 1.0, 1.15, 1.3)) {
                    for ((cast, wb) in casts) {
                        val sample = sampleOf(srgb, exposure, wb)
                        assertEquals("$palette $color at exposure $exposure, $cast: $sample", color, LiveClassifier.classify(sample))
                    }
                }
            }
        }
    }

    @Test
    fun readsRenderedFacesUnderStrongCastsAndDimLight() {
        // Whole rendered faces (noise, gradients, highlights, misalignment) through the sampler, from
        // 0.15x exposure to bright, under every cast; both palettes.
        val random = Random(17)
        val exposures = listOf(0.15, 0.25, 0.4, 0.6, 1.0, 1.3)
        val summary = StringBuilder()
        for (palette in SyntheticFaces.Palette.entries) {
            val faces = SyntheticFaces(random, palette)
            var correct = 0
            var total = 0
            val errors = mutableListOf<String>()
            for ((cast, wb) in casts) {
                for (exposure in exposures) {
                    repeat(4) {
                        val cube = FaceletCube.scrambled(List(25) { Move.entries[random.nextInt(18)] }).toColors()
                        val face = random.nextInt(6)
                        val shown = cube.subList(face * 9, face * 9 + 9)
                        val conditions = faces.randomConditions().copy(brightness = exposure * random.nextDouble(0.9, 1.1), whiteBalance = wb)
                        val (image, guide) = faces.render(shown, conditions, imageSize = 160)
                        val samples = GridSampler.sample(image, guide)
                        for (i in 0 until 9) {
                            total++
                            val got = LiveClassifier.classify(samples[i])
                            if (got == shown[i]) correct++ else errors += "$cast x$exposure: ${shown[i]} read as $got (${samples[i]})"
                        }
                    }
                }
            }
            summary.append("$palette ${"%.2f".format(100.0 * correct / total)}% ($correct/$total)  ")
            // At most one sticker in a thousand, e.g. a dim sticker swamped by a specular highlight.
            assertTrue("$palette: ${errors.joinToString("\n")}", total - correct <= total / 1000)
        }
        println("LiveClassifier, strong casts and dim light: $summary")
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
