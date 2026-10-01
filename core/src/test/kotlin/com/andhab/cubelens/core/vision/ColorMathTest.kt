package com.andhab.cubelens.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ColorMathTest {

    private fun assertLab(expected: Lab, actual: Lab, tolerance: Float = 0.01f) {
        assertEquals("L", expected.l, actual.l, tolerance)
        assertEquals("a", expected.a, actual.a, tolerance)
        assertEquals("b", expected.b, actual.b, tolerance)
    }

    @Test
    fun srgbToLabMatchesReferenceValues() {
        assertLab(Lab(100f, 0f, 0f), ColorMath.srgbToLab(255, 255, 255))
        assertLab(Lab(0f, 0f, 0f), ColorMath.srgbToLab(0, 0, 0))
        assertLab(Lab(53.2408f, 80.0925f, 67.2032f), ColorMath.srgbToLab(255, 0, 0))
        assertLab(Lab(87.7347f, -86.1827f, 83.1793f), ColorMath.srgbToLab(0, 255, 0))
        assertLab(Lab(32.2970f, 79.1875f, -107.8602f), ColorMath.srgbToLab(0, 0, 255))
        assertLab(Lab(97.1393f, -21.5537f, 94.4780f), ColorMath.srgbToLab(255, 255, 0))
        // Mid grey is neutral and close to L = 50.
        val grey = ColorMath.srgbToLab(119, 119, 119)
        assertEquals(50.0f, grey.l, 0.3f)
        assertEquals(0f, grey.a, 0.01f)
        assertEquals(0f, grey.b, 0.01f)
    }

    @Test
    fun labRoundTripsThroughSrgb() {
        val random = Random(1)
        repeat(500) {
            val r = random.nextInt(256)
            val g = random.nextInt(256)
            val b = random.nextInt(256)
            val argb = ColorMath.labToArgb(ColorMath.srgbToLab(r, g, b))
            assertEquals((0xFF shl 24) or (r shl 16) or (g shl 8) or b, argb)
        }
    }

    /** Test pairs from Sharma, Wu & Dalal (2005), "The CIEDE2000 color-difference formula". */
    @Test
    fun ciede2000MatchesSharmaTestData() {
        val pairs = listOf(
            doubleArrayOf(50.0, 2.6772, -79.7751, 50.0, 0.0, -82.7485, 2.0425),
            doubleArrayOf(50.0, 3.1571, -77.2803, 50.0, 0.0, -82.7485, 2.8615),
            doubleArrayOf(50.0, 2.8361, -74.0200, 50.0, 0.0, -82.7485, 3.4412),
            doubleArrayOf(50.0, -1.3802, -84.2814, 50.0, 0.0, -82.7485, 1.0000),
            doubleArrayOf(50.0, -1.1848, -84.8006, 50.0, 0.0, -82.7485, 1.0000),
            doubleArrayOf(50.0, -0.9009, -85.5211, 50.0, 0.0, -82.7485, 1.0000),
            doubleArrayOf(50.0, 0.0, 0.0, 50.0, -1.0, 2.0, 2.3669),
            doubleArrayOf(50.0, -1.0, 2.0, 50.0, 0.0, 0.0, 2.3669),
            doubleArrayOf(50.0, 2.49, -0.001, 50.0, -2.49, 0.0009, 7.1792),
            doubleArrayOf(50.0, 2.49, -0.001, 50.0, -2.49, 0.0010, 7.1792),
            doubleArrayOf(50.0, 2.49, -0.001, 50.0, -2.49, 0.0011, 7.2195),
            doubleArrayOf(50.0, 2.49, -0.001, 50.0, -2.49, 0.0012, 7.2195),
            doubleArrayOf(50.0, 2.5, 0.0, 73.0, 25.0, -18.0, 27.1492),
            doubleArrayOf(50.0, 2.5, 0.0, 61.0, -5.0, 29.0, 22.8977),
            doubleArrayOf(50.0, 2.5, 0.0, 56.0, -27.0, -3.0, 31.9030),
            doubleArrayOf(50.0, 2.5, 0.0, 58.0, 24.0, 15.0, 19.4535),
            doubleArrayOf(60.2574, -34.0099, 36.2677, 60.4626, -34.1751, 39.4387, 1.2644),
            doubleArrayOf(2.0776, 0.0795, -1.1350, 0.9033, -0.0636, -0.5514, 0.9082),
        )
        for (p in pairs) {
            assertEquals(p.toList().toString(), p[6], ColorMath.ciede2000(p[0], p[1], p[2], p[3], p[4], p[5]), 1e-4)
            assertEquals("symmetric", p[6], ColorMath.ciede2000(p[3], p[4], p[5], p[0], p[1], p[2]), 1e-4)
        }
    }

    @Test
    fun deltaEHalvesLightnessDifferences() {
        val a = Lab(50f, 20f, -10f)
        val b = Lab(62f, 20f, -10f)
        val full = ColorMath.ciede2000(50.0, 20.0, -10.0, 62.0, 20.0, -10.0)
        assertEquals(full / 2, ColorMath.deltaE(a, b).toDouble(), 1e-4)
        assertEquals(0f, ColorMath.deltaE(a, a), 0f)
        assertEquals(ColorMath.deltaE(a, b), ColorMath.deltaE(b, a), 1e-5f)
    }

    @Test
    fun deltaESeparatesStickerColorsBetterThanLightnessChanges() {
        // A sticker in shadow is closer to itself in good light than to the neighbouring hue.
        val red = ColorMath.srgbToLab(183, 25, 29)
        val shadedRed = ColorMath.srgbToLab(110, 12, 18)
        val orange = ColorMath.srgbToLab(228, 114, 22)
        assertTrue(ColorMath.deltaE(red, shadedRed) < ColorMath.deltaE(red, orange))
        val white = ColorMath.srgbToLab(192, 200, 212)
        val shadedWhite = ColorMath.srgbToLab(120, 124, 130)
        val yellow = ColorMath.srgbToLab(222, 214, 62)
        assertTrue(ColorMath.deltaE(white, shadedWhite) < ColorMath.deltaE(white, yellow))
    }

    @Test
    fun hueAndChroma() {
        assertEquals(0f, Lab(50f, 0f, 0f).hue, 0f)
        assertEquals(90f, Lab(50f, 0f, 10f).hue, 1e-4f)
        assertEquals(270f, Lab(50f, 0f, -10f).hue, 1e-4f)
        assertEquals(5f, Lab(50f, 3f, 4f).chroma, 1e-5f)
    }
}
