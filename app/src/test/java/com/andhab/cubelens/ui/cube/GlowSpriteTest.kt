package com.andhab.cubelens.ui.cube

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-math tests of the glow falloff and sprite pixels (plain JVM, no Android drawing). */
class GlowSpriteTest {

    @Test
    fun profileIsHalfStrengthAtTheEdgeAndFadesToNothingAtTheSpread() {
        val spread = 54f
        val sigma = spread / 2.5f
        assertEquals(0.5f, glowAlpha(0f, spread, sigma), 1e-6f)
        assertTrue("solid deep inside", glowAlpha(-3 * sigma, spread, sigma) > 0.99f)
        assertEquals(0f, glowAlpha(spread, spread, sigma), 0f)
        assertEquals(0f, glowAlpha(spread + 10f, spread, sigma), 0f)
        assertTrue("just inside the spread is nearly invisible", glowAlpha(spread - 0.5f, spread, sigma) < 1e-3f)
    }

    @Test
    fun profileFallsSmoothlyWithoutSteps() {
        val spread = 54f
        val sigma = spread / 2.5f
        var last = glowAlpha(-spread, spread, sigma)
        var d = -spread
        while (d <= spread) {
            d += 0.25f
            val a = glowAlpha(d, spread, sigma)
            assertTrue("never rises at $d", a <= last + 1e-6f)
            // Steepest at the edge: 1.702 / (4 sigma) per px, i.e. about 0.5% per quarter pixel here.
            assertTrue("no step at $d: ${last - a}", last - a < 0.006f)
            last = a
        }
    }

    @Test
    fun spriteHasNoBandsAndAClearBorder() {
        // The active FaceGrid at 200dp on xxhdpi: a 492 px plate with a 54 px glow, at half resolution.
        val shape = Size(492f, 492f)
        val spread = 54f
        val width = 300
        val pixels = glowPixels(width, width, shape, cornerRadius = 64f, spread = spread, palette = intArrayOf(0xFFFF2E63.toInt()))
        fun alpha(x: Int, y: Int) = pixels[y * width + x] ushr 24

        for (i in 0 until width) {
            for (edge in listOf(alpha(i, 0), alpha(0, i), alpha(i, width - 1), alpha(width - 1, i))) {
                assertEquals("sprite border is transparent", 0, edge)
            }
        }
        // Walk outward from the plate along a straight side (2 canvas px per sprite pixel) and along
        // the corner diagonal (2.8 px per step). At its steepest the glow falls ~5 levels per canvas
        // pixel; the old ring glow jumped ~14 levels within a single pixel at every band.
        val mid = width / 2
        assertOutwardFalloffIsSmooth("side", (0..mid).map { x -> alpha(mid - x, mid) }, maxStep = 12)
        assertOutwardFalloffIsSmooth("corner", (0..mid).map { k -> alpha(mid - k, mid - k) }, maxStep = 16)
        // The four sides and corners match.
        for (k in 0 until width) {
            assertEquals(alpha(k, mid), alpha(width - 1 - k, mid))
            assertEquals(alpha(mid, k), alpha(mid, width - 1 - k))
            assertEquals(alpha(k, k), alpha(width - 1 - k, width - 1 - k))
        }
    }

    @Test
    fun hueFollowsTheDiagonal() {
        val palette = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val width = 64
        val pixels = glowPixels(width, width, Size(80f, 80f), cornerRadius = 10f, spread = 20f, palette = palette)
        assertEquals(0xFF0000, pixels[8 * width + 8] and 0xFFFFFF)
        assertEquals(0x00FF00, pixels[32 * width + 32] and 0xFFFFFF)
        assertEquals(0x0000FF, pixels[(width - 9) * width + width - 9] and 0xFFFFFF)
    }

    /** [alphas] run from inside the plate outward: they never rise and never jump. */
    private fun assertOutwardFalloffIsSmooth(label: String, alphas: List<Int>, maxStep: Int) {
        assertEquals("$label: solid under the plate", 255, alphas.first())
        assertEquals("$label: gone at the border", 0, alphas.last())
        for (k in 1 until alphas.size) {
            val step = alphas[k - 1] - alphas[k]
            assertTrue("$label: rises at $k", step >= 0)
            assertTrue("$label: band of $step at $k", step <= maxStep)
        }
        assertTrue("$label: not a hard edge", alphas.count { it in 8..247 } >= 10)
    }
}
