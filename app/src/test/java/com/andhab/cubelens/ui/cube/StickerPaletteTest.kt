package com.andhab.cubelens.ui.cube

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.theme.CubePalette
import com.andhab.cubelens.ui.theme.StickerFinish
import com.andhab.cubelens.ui.theme.StickerPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** [StickerPalette] and the color-adaptive [StickerFinish] (plain JVM). */
class StickerPaletteTest {

    @Test
    fun standardPaletteIsTheStockColors() {
        for (c in CubeColor.entries) assertEquals(CubePalette.color(c), StickerPalette.Standard.color(c))
        assertEquals(CubePalette.Unknown, StickerPalette.Standard.color(null))
    }

    @Test
    fun customPalettesComeFromColorsOrArgbAndFallBackToStock() {
        val pink = 0xFFF2A0B4.toInt()
        val palette = StickerPalette.fromArgb(mapOf(CubeColor.RED to pink))
        assertEquals(Color(pink), palette.color(CubeColor.RED))
        assertEquals(CubePalette.color(CubeColor.BLUE), palette.color(CubeColor.BLUE))
        assertEquals(CubePalette.Unknown, palette.color(null))
        assertEquals(TestCubes.Pastel.color(CubeColor.GREEN).toArgb(), 0xFF9EDDB0.toInt())
    }

    @Test
    fun palettesCompareByValue() {
        val a = StickerPalette.fromArgb(mapOf(CubeColor.WHITE to 0xFFF7F5EE.toInt()))
        val b = StickerPalette(mapOf(CubeColor.WHITE to Color(0xFFF7F5EE)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(StickerPalette.Standard, StickerPalette(emptyMap()))
        assertNotEquals(StickerPalette.Standard, TestCubes.Pastel)
    }

    @Test
    fun stockColorsKeepTheClassicFinish() {
        for (c in CubeColor.entries) {
            val finish = StickerPalette.Standard.finish(c)
            assertEquals("$c is not pastel", 0f, finish.pastel, 0.02f)
            assertEquals(1f, finish.glossScale, 0.01f)
            assertEquals(1f, finish.shadeScale, 0.02f)
            assertTrue("$c shadow is black", finish.shadow.red + finish.shadow.green + finish.shadow.blue < 0.02f)
            // Darkening a vivid color is plain multiplication, as the renderer always did.
            val base = finish.base.toArgb()
            for (k in listOf(0.6f, 0.8f, 1f)) {
                val shaded = finish.shadeArgb(k)
                for (shift in listOf(16, 8, 0)) {
                    val expected = ((base shr shift) and 0xFF) * k
                    assertEquals("$c at $k", expected, ((shaded shr shift) and 0xFF).toFloat(), 1.01f)
                }
            }
        }
    }

    @Test
    fun pastelsGetGentlerGlossAndTintedShadows() {
        for (c in CubeColor.entries.filter { it != CubeColor.WHITE }) {
            val finish = TestCubes.Pastel.finish(c)
            assertTrue("$c pastel ${finish.pastel}", finish.pastel > 0.3f)
            assertTrue("$c gloss ${finish.glossScale}", finish.glossScale < 0.9f)
            assertTrue("$c shade ${finish.shadeScale}", finish.shadeScale > 1.15f)
            // The shadow is a deep version of the hue (warmed a little for yellows and oranges), not black or grey.
            assertHue("$c shadow hue", hue(finish.base), hue(finish.shadow), if (c.isWarm) 25f else 12f)
            assertTrue("$c shadow is deep", value(finish.shadow) < 0.45f)
        }
        // Off-white stays neutral: it is a white sticker, not a pastel.
        assertEquals(0f, TestCubes.Pastel.finish(CubeColor.WHITE).pastel, 0.05f)
    }

    @Test
    fun darkenedPastelsKeepTheirHueAndGainSaturation() {
        for (c in CubeColor.entries.filter { it != CubeColor.WHITE }) {
            val finish = TestCubes.Pastel.finish(c)
            assertEquals(finish.base.toArgb(), finish.shadeArgb(1f))
            val dark = Color(finish.shadeArgb(0.6f))
            if (c == CubeColor.ORANGE) {
                // Peach turns coral, never brown: the hue moves towards red.
                val drift = hue(finish.base) - hue(dark)
                assertTrue("$c drifts warmer by $drift", drift in 3f..20f)
            } else if (c == CubeColor.YELLOW) {
                // Lemon barely drifts: a move towards orange is what makes it mustard.
                val drift = hue(finish.base) - hue(dark)
                assertTrue("$c drifts warmer by $drift", drift in 0f..4f)
            } else {
                assertHue("$c hue", hue(finish.base), hue(dark), 4f)
            }
            assertTrue("$c is richer when darker", saturation(dark) > saturation(finish.base) + 0.1f)
            // Darker, but less than plain multiplication would make it.
            assertTrue("$c value", value(dark) in value(finish.base) * 0.6f + 0.02f..value(finish.base) - 0.05f)
        }
    }

    @Test
    fun shadedLemonStaysADeeperLemonRatherThanMustard() {
        val lemon = TestCubes.Pastel.finish(CubeColor.YELLOW)
        val peach = TestCubes.Pastel.finish(CubeColor.ORANGE)
        // 0.6 is the darkest a face gets in the renderer (its ambient light).
        val shaded = Color(lemon.shadeArgb(0.6f))
        assertHue("lemon hue in the shade", hue(lemon.base), hue(shaded), 4f)
        assertTrue("lemon keeps its light: ${value(shaded)}", value(shaded) >= value(lemon.base) * 0.82f)
        assertTrue("lemon deepens: ${saturation(shaded)}", saturation(shaded) >= saturation(lemon.base) + 0.12f)
        // Shaded lemon and shaded peach stay clearly apart.
        val shadedPeach = Color(peach.shadeArgb(0.6f))
        val apart = abs(hue(shaded) - hue(shadedPeach))
        assertTrue("lemon and peach hues apart by $apart", apart > 20f)
    }

    @Test
    fun shadingDoesNotAllocate() {
        val finish = TestCubes.Pastel.finish(CubeColor.RED)
        var sink = 0
        // Warm up, then measure: renderers call this per face per frame.
        repeat(50_000) { sink = sink xor finish.shadeArgb((it % 100) / 100f) }
        val bytes = allocatedBytes {
            repeat(100_000) { sink = sink xor finish.shadeArgb((it % 100) / 100f) }
        }
        assertTrue("shadeArgb allocated $bytes bytes ($sink)", bytes < 4_096)
    }

    @Test
    fun finishOfUnknownIsMatte() {
        val finish = StickerPalette.Standard.finish(null)
        assertEquals(CubePalette.Unknown, finish.base)
        assertEquals(0f, finish.pastel, 0.01f)
        assertEquals(StickerFinish.of(CubePalette.Unknown), finish)
    }

    private fun hue(c: Color): Float {
        val maxC = max(c.red, max(c.green, c.blue))
        val d = maxC - min(c.red, min(c.green, c.blue))
        if (d == 0f) return 0f
        val h = when (maxC) {
            c.red -> 60f * (((c.green - c.blue) / d + 6f) % 6f)
            c.green -> 60f * ((c.blue - c.red) / d + 2f)
            else -> 60f * ((c.red - c.green) / d + 4f)
        }
        return h
    }

    private fun saturation(c: Color): Float {
        val maxC = max(c.red, max(c.green, c.blue))
        return if (maxC == 0f) 0f else (maxC - min(c.red, min(c.green, c.blue))) / maxC
    }

    private fun value(c: Color): Float = max(c.red, max(c.green, c.blue))

    private val CubeColor.isWarm: Boolean get() = this == CubeColor.YELLOW || this == CubeColor.ORANGE

    private fun assertHue(what: String, expected: Float, actual: Float, tolerance: Float) {
        val d = abs(expected - actual) % 360f
        assertTrue("$what: $expected vs $actual", min(d, 360f - d) <= tolerance)
    }
}
