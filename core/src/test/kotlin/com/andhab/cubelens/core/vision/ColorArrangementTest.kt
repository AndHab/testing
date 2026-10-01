package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Cubes whose colors are arranged differently from the standard scheme, scanned in the app's guided
 * order (front, right, back, left, up, down, turning the cube left between the side faces).
 */
class ColorArrangementTest {

    private val guidedOrder = listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D)

    /** Japanese scheme: white opposite blue, green opposite yellow, red opposite orange. */
    private val japanese = ColorScheme(
        mapOf(
            Face.U to CubeColor.WHITE, Face.D to CubeColor.BLUE, Face.F to CubeColor.GREEN,
            Face.B to CubeColor.YELLOW, Face.R to CubeColor.RED, Face.L to CubeColor.ORANGE,
        ),
    )

    /** The standard scheme's mirror image (red and orange swapped): same opposite pairs, other handedness. */
    private val mirrored = ColorScheme(
        mapOf(
            Face.U to CubeColor.WHITE, Face.D to CubeColor.YELLOW, Face.F to CubeColor.GREEN,
            Face.B to CubeColor.BLUE, Face.R to CubeColor.ORANGE, Face.L to CubeColor.RED,
        ),
    )

    /** An arbitrary arrangement. */
    private val permuted = ColorScheme(
        mapOf(
            Face.U to CubeColor.ORANGE, Face.D to CubeColor.GREEN, Face.F to CubeColor.WHITE,
            Face.B to CubeColor.RED, Face.R to CubeColor.YELLOW, Face.L to CubeColor.BLUE,
        ),
    )

    private fun randomCube(random: Random): FaceletCube = FaceletCube.scrambled(List(random.nextInt(20, 31)) { Move.entries[random.nextInt(18)] })

    private fun conditions(random: Random) = SyntheticFaces.Conditions(
        brightness = random.nextDouble(0.65, 1.25),
        whiteBalance = doubleArrayOf(random.nextDouble(0.92, 1.08), 1.0, random.nextDouble(0.9, 1.1)),
        gradient = random.nextDouble(-0.2, 0.2) to random.nextDouble(-0.2, 0.2),
        shift = random.nextDouble(-0.03, 0.03) to random.nextDouble(-0.03, 0.03),
        scale = random.nextDouble(0.95, 1.05),
        rotationDegrees = random.nextDouble(-3.0, 3.0),
        keystone = random.nextDouble(-0.04, 0.04) to random.nextDouble(-0.04, 0.04),
        highlights = random.nextInt(0, 3),
        noise = random.nextDouble(2.0, 5.0),
    )

    /** Photos of [colors] in [guidedOrder], each face held at a random angle. */
    private fun scanGuided(faces: SyntheticFaces, colors: List<CubeColor>, random: Random): List<List<StickerSample>> =
        guidedOrder.map { face ->
            val shown = OrientationFixer.rotateFace(colors, face, random.nextInt(4)).subList(face.ordinal * 9, face.ordinal * 9 + 9)
            val (image, guide) = faces.render(shown, conditions(random), 128)
            GridSampler.sample(image, guide)
        }

    @Test
    fun resolvesOtherArrangementsScannedInGuidedOrder() {
        val random = Random(2027)
        val report = StringBuilder()
        for ((name, scheme) in listOf("Japanese" to japanese, "mirrored" to mirrored, "permuted" to permuted)) {
            for (look in listOf(KnockOffCubes.VIVID, KnockOffCubes.PASTEL, KnockOffCubes.LAVENDER)) {
                val faces = SyntheticFaces(random, look)
                var exact = 0
                val cubes = 20
                repeat(cubes) { n ->
                    val cube = randomCube(random)
                    val colors = cube.toColors(scheme)
                    val scans = scanGuided(faces, colors, random)

                    val analysis = ScanResolver.resolve(scans, guidedOrder)
                    assertTrue("$name $look cube $n: ${analysis.rawColors.letters()}", analysis.isValid)
                    assertEquals(Placement.SCAN_ORDER, analysis.placement)
                    val wrong = (0 until Facelets.COUNT).filter { analysis.colors[it] != colors[it] }.toSet()
                    if (wrong.isEmpty()) {
                        exact++
                        assertEquals(cube, FaceletCube.fromColors(analysis.colors))
                    } else {
                        // Only an orientation that colors cannot decide, and it is flagged.
                        assertTrue("$name $look cube $n: wrong $wrong not flagged", analysis.uncertain.containsAll(wrong))
                    }

                    // Without the scan order the arrangement can't be placed: no crash, and no wrong cube.
                    val blind = ScanResolver.resolve(scans)
                    assertFalse(blind.isValid)
                    assertEquals(Placement.CENTER_COLORS, blind.placement)
                    assertEquals(Facelets.COUNT, blind.colors.size)
                }
                report.append("$name $look ${exact}/$cubes exact; ")
                assertTrue("$name $look: $exact/$cubes exact", exact >= cubes - 1)
            }
        }
        println("Other color arrangements, guided order: $report")
    }

    @Test
    fun standardCubesKeepCenterColorPlacementInGuidedOrder() {
        val random = Random(2028)
        val faces = SyntheticFaces(random, KnockOffCubes.PASTEL)
        repeat(10) { n ->
            val cube = randomCube(random)
            val colors = cube.toColors()
            val scans = scanGuided(faces, colors, random)
            for (positions in listOf(guidedOrder, null)) {
                val analysis = ScanResolver.resolve(scans, positions)
                assertTrue("cube $n", analysis.isValid)
                assertEquals(Placement.CENTER_COLORS, analysis.placement)
            }
            // Held with another face up, the scan positions are wrong, but the centers still place it.
            val shuffled = ScanResolver.resolve(scans, listOf(Face.U, Face.D, Face.F, Face.B, Face.R, Face.L))
            assertTrue("cube $n", shuffled.isValid)
            assertEquals(Placement.CENTER_COLORS, shuffled.placement)
        }
    }

    @Test
    fun malformedScanPositionsAreIgnored() {
        val random = Random(2029)
        val cube = randomCube(random)
        val colors = cube.toColors(japanese)
        val scans = guidedOrder.map { face -> colors.subList(face.ordinal * 9, face.ordinal * 9 + 9).map(KnockOffCubes.VIVID::sample) }
        assertTrue(ScanResolver.resolve(scans, guidedOrder).isValid)
        for (positions in listOf(emptyList(), guidedOrder.take(5), guidedOrder + Face.U, List(6) { Face.F }, guidedOrder.dropLast(1) + Face.U)) {
            val analysis = ScanResolver.resolve(scans, positions)
            assertFalse("$positions", analysis.isValid)
            assertEquals(Placement.CENTER_COLORS, analysis.placement)
        }
        // Positions for malformed scans are ignored as well.
        assertFalse(ScanResolver.resolve(scans.take(5), guidedOrder).isValid)
    }
}
