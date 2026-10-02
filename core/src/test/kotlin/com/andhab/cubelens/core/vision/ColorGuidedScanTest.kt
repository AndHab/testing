package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Odd cubes whose colors are arranged differently from the standard scheme, scanned by someone who
 * follows the color names of the guided steps ("Red center facing you, white on top") rather than
 * the turns: the faces then come in the guided order's slots by their standard colors, not by where
 * they sit on the cube. Front and top are where the first hold put them; the other four scans have
 * to be rearranged.
 */
class ColorGuidedScanTest {

    /** The standard scheme's mirror image (red and orange swapped). */
    private val mirrored = ColorScheme.STANDARD.centers + mapOf(Face.R to CubeColor.ORANGE, Face.L to CubeColor.RED)

    /** Japanese scheme: white opposite blue, green opposite yellow; some named holds are impossible. */
    private val japanese = mapOf(
        Face.U to CubeColor.WHITE, Face.D to CubeColor.BLUE, Face.F to CubeColor.GREEN,
        Face.B to CubeColor.YELLOW, Face.R to CubeColor.RED, Face.L to CubeColor.ORANGE,
    )

    /** What each guided step names (standard colors): the center in front and the one on top. */
    private val steps = listOf(
        CubeColor.GREEN to CubeColor.WHITE,
        CubeColor.RED to CubeColor.WHITE,
        CubeColor.BLUE to CubeColor.WHITE,
        CubeColor.ORANGE to CubeColor.WHITE,
        CubeColor.WHITE to CubeColor.BLUE,
        CubeColor.YELLOW to CubeColor.GREEN,
    )

    private fun center(cube: NxNCube, face: Face): CubeColor = cube[cube.geometry.index(face, cube.n / 2, cube.n / 2)]

    /** Every way to hold [cube] (whole-cube turns), each reached once. */
    private fun holds(cube: NxNCube): List<NxNCube> {
        val n = cube.n
        val result = LinkedHashMap<List<CubeColor>, NxNCube>()
        for (x in 0..3) for (y in 0..3) for (z in 0..3) {
            val moves = listOfNotNull(
                x.takeIf { it > 0 }?.let { LayerMove(Face.R, 1, n, it) },
                y.takeIf { it > 0 }?.let { LayerMove(Face.U, 1, n, it) },
                z.takeIf { it > 0 }?.let { LayerMove(Face.F, 1, n, it) },
            )
            val held = cube.apply(moves)
            result.putIfAbsent(Face.entries.map { center(held, it) }, held)
        }
        return result.values.toList()
    }

    /**
     * The scans of someone following the color names: for each step, the cube held with that color in
     * front and the named color on top (or, where the cube can't be held that way, with any color on
     * top), the front face photographed.
     */
    private fun scanByColorNames(cube: NxNCube): List<List<StickerSample>> {
        val holds = holds(cube)
        return steps.map { (front, top) ->
            val held = holds.firstOrNull { center(it, Face.F) == front && center(it, Face.U) == top }
                ?: holds.first { center(it, Face.F) == front }
            held.face(Face.F).map(KnockOffCubes.VIVID::sample)
        }
    }

    private fun check(name: String, scheme: Map<Face, CubeColor>) {
        for (n in listOf(3, 5, 7)) {
            for (seed in 1..3) {
                val cube = NxNCube.solved(n, scheme).apply(NxNScrambler.randomMoves(n, Random(seed * 31 + n)))
                val analysis = ScanResolver.resolve(n, scanByColorNames(cube), NxNSessions.GUIDED)
                assertTrue("$name ${n}x$n seed $seed: ${analysis.problems}", analysis.isValid)
                assertEquals(Placement.REARRANGED, analysis.placement)
                assertEquals("$name ${n}x$n seed $seed", cube.toColors(), analysis.colors)
            }
        }
    }

    @Test
    fun mirroredCubesScannedByColorNamesResolveToThePhysicalCube() = check("mirrored", mirrored)

    @Test
    fun japaneseCubesScannedByColorNamesResolveToThePhysicalCube() = check("Japanese", japanese)

    @Test
    fun standardCubesScannedByColorNamesStillUseTheirCenters() {
        for (n in listOf(3, 5)) {
            val cube = NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(n)))
            val analysis = ScanResolver.resolve(n, scanByColorNames(cube), NxNSessions.GUIDED)
            assertTrue(analysis.isValid)
            assertEquals(Placement.CENTER_COLORS, analysis.placement)
            assertEquals(cube.toColors(), analysis.colors)
        }
    }
}
