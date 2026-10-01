package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class CubePaletteTest {

    private fun hueDifference(x: Float, y: Float): Float {
        val d = abs(x - y) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** Ideal scans of a random cube in [look], each face at its own exposure. */
    private fun idealScans(look: CubeLook, random: Random): List<List<StickerSample>> {
        val colors = FaceletCube.scrambled(List(25) { Move.entries[random.nextInt(18)] }).toColors()
        return Face.entries.map { face ->
            val exposure = random.nextDouble(0.7, 1.2)
            colors.subList(face.ordinal * 9, face.ordinal * 9 + 9).map { c ->
                val lin = look.linear.getValue(c)
                val v = IntArray(3) { ColorMath.linearToSrgb(lin[it] * exposure) }
                StickerSample.of(v[0], v[1], v[2])
            }
        }.shuffled(random)
    }

    @Test
    fun standardPaletteIsTheStockPalette() {
        val standard = CubePaletteEstimate.STANDARD
        assertTrue(standard.isStandardLike)
        assertEquals(CubeColor.entries.toSet(), standard.colors.keys)
        assertTrue(standard.colors.values.all { it ushr 24 == 0xFF })
    }

    @Test
    fun theUsersRealCubeIsStandardLike() {
        val scans = listOf("1_red", "2_green", "3_blue", "4_white", "6_yellow", "7_orange").map { Photos.scan(it) }
        val analysis = ScanResolver.resolve(scans)
        assertTrue(analysis.isValid)
        assertTrue("palette ${analysis.palette}", analysis.palette.isStandardLike)
    }

    @Test
    fun estimatesStandardAndKnockOffPalettes() {
        val random = Random(11)
        for (look in KnockOffCubes.ALL + SyntheticFaces.Palette.PHOTO.look) {
            val standard = look.name in setOf("vivid standard", "PHOTO", "white body")
            val knockOff = look in setOf(KnockOffCubes.PASTEL, KnockOffCubes.CANDY, KnockOffCubes.MUTED)
            repeat(10) {
                val analysis = ScanResolver.resolve(idealScans(look, random))
                assertTrue("$look", analysis.isValid)
                val palette = analysis.palette
                if (standard) assertTrue("$look: $palette", palette.isStandardLike)
                if (knockOff) assertFalse("$look: $palette", palette.isStandardLike)
                for ((color, argb) in palette.colors) {
                    assertEquals("opaque", 0xFF, argb ushr 24)
                    if (color == CubeColor.WHITE) continue
                    // Display colors keep the hue of what was measured.
                    val measured = look.sample(color).lab.hue
                    val shown = StickerSample.ofArgb(argb).lab.hue
                    assertTrue("$look $color: measured hue $measured, shown $shown", hueDifference(measured, shown) <= 10f)
                }
            }
        }
    }

    @Test
    fun pastelColorsStayPastelAndDarkColorsAreLifted() {
        val random = Random(12)
        val pastel = ScanResolver.resolve(idealScans(KnockOffCubes.PASTEL, random)).palette
        val vivid = ScanResolver.resolve(idealScans(KnockOffCubes.VIVID, random)).palette
        for (color in CubeColor.entries) {
            if (color == CubeColor.WHITE) continue
            val p = StickerSample.ofArgb(pastel.colors.getValue(color)).lab
            val v = StickerSample.ofArgb(vivid.colors.getValue(color)).lab
            assertTrue("$color: pastel chroma ${p.chroma} vs vivid ${v.chroma}", p.chroma < v.chroma)
            // Lifted for a dark background: even a deep blue stays readable.
            assertTrue("$color: lightness ${v.l}", v.l >= 40f)
        }
        val white = StickerSample.ofArgb(pastel.colors.getValue(CubeColor.WHITE)).lab
        assertTrue("white $white", white.l > 90f && white.chroma < 3f)
    }

    @Test
    fun displayColorsAreClampedIntoGamut() {
        // Extreme inputs (very saturated, very dark, very light) still give opaque, finite colors.
        for (lab in listOf(Lab(50f, 120f, -120f), Lab(5f, 40f, 40f), Lab(99f, -60f, 90f), Lab(0f, 0f, 0f), Lab(100f, 0f, 0f))) {
            val argb = PaletteEstimator.toDisplay(lab)
            assertEquals(0xFF, argb ushr 24)
            val shown = StickerSample.ofArgb(argb).lab
            if (lab.chroma > 0f && shown.chroma > 2f) assertTrue("$lab -> $shown", hueDifference(lab.hue, shown.hue) < 6f)
        }
    }

    @Test
    fun malformedInputGetsTheStandardPalette() {
        val analysis = ScanResolver.resolve(emptyList())
        assertFalse(analysis.isValid)
        assertEquals(CubePaletteEstimate.STANDARD, analysis.palette)
        assertEquals(Placement.CENTER_COLORS, analysis.placement)
    }
}
