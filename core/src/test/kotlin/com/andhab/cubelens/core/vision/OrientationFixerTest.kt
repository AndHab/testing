package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeValidator
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OrientationFixerTest {

    private fun randomCube(random: Random): FaceletCube =
        FaceletCube.scrambled(List(random.nextInt(18, 30)) { Move.entries[random.nextInt(18)] })

    private fun faceOf(colors: List<CubeColor>, face: Face) = colors.subList(face.ordinal * 9, face.ordinal * 9 + 9)

    @Test
    fun rotateFaceHasOrderFourAndOnlyTouchesItsFace() {
        val colors = randomCube(Random(1)).toColors()
        for (face in Face.entries) {
            var turned = colors
            repeat(4) { turned = OrientationFixer.rotateFace(turned, face, 1) }
            assertEquals(colors, turned)
            assertEquals(colors, OrientationFixer.rotateFace(OrientationFixer.rotateFace(colors, face, 1), face, 3))
            assertEquals(OrientationFixer.rotateFace(colors, face, -1), OrientationFixer.rotateFace(colors, face, 3))
            assertEquals(colors, OrientationFixer.rotateFace(colors, face, 8))
            val once = OrientationFixer.rotateFace(colors, face, 1)
            for (i in 0 until Facelets.COUNT) {
                if (Facelets.faceOf(i) != face || i == Facelets.center(face)) assertEquals(colors[i], once[i])
            }
        }
    }

    @Test
    fun rotateFaceTurnsClockwiseAsSeenFromOutside() {
        // Positions 0..8 of the U face, as letters, to follow each sticker.
        val colors = MutableList(Facelets.COUNT) { CubeColor.WHITE }
        val marks = listOf(CubeColor.RED, CubeColor.GREEN, CubeColor.BLUE, CubeColor.ORANGE, CubeColor.WHITE, CubeColor.YELLOW, CubeColor.RED, CubeColor.BLUE, CubeColor.GREEN)
        for (p in 0 until 9) colors[p] = marks[p]
        // Clockwise: the left column (bottom to top) becomes the top row.
        val expected = listOf(6, 3, 0, 7, 4, 1, 8, 5, 2).map { marks[it] }
        assertEquals(expected, faceOf(OrientationFixer.rotateFace(colors, Face.U, 1), Face.U))
    }

    @Test
    fun rotateFaceMatchesTheFaceTurn() {
        // A clockwise face move turns that face's own stickers exactly like a clockwise grid rotation.
        val random = Random(2)
        repeat(20) {
            val cube = randomCube(random)
            for (face in Face.entries) {
                val moved = cube.apply(Move.of(face, 1)).toColors()
                assertEquals(faceOf(moved, face), faceOf(OrientationFixer.rotateFace(cube.toColors(), face, 1), face))
            }
        }
    }

    @Test
    fun rotateFaceMatchesTurningTheCameraView() {
        // Reading a frame that still needs a quarter turn clockwise, without applying it, shows the face
        // turned a quarter counter-clockwise; rotateFace by one clockwise turn undoes exactly that.
        val random = Random(3)
        val faces = SyntheticFaces(random)
        val colors = randomCube(random).toColors()
        for (face in Face.entries) {
            val shown = faceOf(colors, face)
            val (upright, guide) = faces.render(shown, faces.randomConditions().copy(rotationDegrees = 0.0, keystone = 0.0 to 0.0))
            val buffer = upright.asBufferNeedingRotation(90)
            val asIs = GridSampler.sample(buffer, upright.regionInBuffer(guide, 90), 0).map(LiveClassifier::classify)
            val placed = colors.toMutableList().also { list -> for (p in 0 until 9) list[face.ordinal * 9 + p] = asIs[p] }
            assertEquals(shown, faceOf(OrientationFixer.rotateFace(placed, face, 1), face))
        }
    }

    @Test
    fun validCubeNeedsNoRotation() {
        val colors = randomCube(Random(4)).toColors()
        val (fixed, rotations) = OrientationFixer.fix(colors)!!
        assertEquals(colors, fixed)
        assertEquals(Face.entries.associateWith { 0 }, rotations)
    }

    @Test
    fun undoesRandomFaceRotations() {
        val random = Random(5)
        var ambiguous = 0
        repeat(300) {
            val cube = randomCube(random)
            val turns = IntArray(6) { random.nextInt(4) }
            var scanned = cube.toColors()
            for (face in Face.entries) scanned = OrientationFixer.rotateFace(scanned, face, turns[face.ordinal])
            val (fixed, rotations) = OrientationFixer.fix(scanned) ?: error("No fix for $cube with turns ${turns.toList()}")
            assertTrue(CubeValidator.validate(fixed).isValid)
            var expected = scanned
            for (face in Face.entries) expected = OrientationFixer.rotateFace(expected, face, rotations.getValue(face))
            assertEquals(expected, fixed)
            val orientation = OrientationFixer.orient(scanned)!!
            if (FaceletCube.fromColors(fixed) != cube) {
                // Rarely, another rotation of some face also gives a valid (different) cube; colors alone
                // cannot tell them apart, and the fixer must then have picked the preferred one ...
                ambiguous++
                val undo = IntArray(6) { (4 - turns[it]) % 4 }
                assertTrue(preference(rotations.values.toIntArray()) <= preference(undo))
                // ... and reported every sticker that differs from the true cube as ambiguous.
                val truth = cube.toColors()
                val wrong = (0 until Facelets.COUNT).filter { fixed[it] != truth[it] }.toSet()
                assertTrue("wrong $wrong not in ambiguous ${orientation.ambiguous}", orientation.ambiguous.containsAll(wrong))
            }
        }
        println("OrientationFixer: $ambiguous of 300 randomly rotated scans had another, preferred valid reading")
        assertTrue("ambiguous $ambiguous", ambiguous <= 3)
    }

    @Test
    fun reportsWhereAnotherValidReadingDisagrees() {
        // Half-turn-only scrambles leave two opposite colors per face, so several rotations of a face
        // are often valid. The preferred reading is right when faces are held in the reference
        // orientation; otherwise every sticker it gets wrong must be reported as ambiguous.
        val random = Random(9)
        val halfTurns = Move.entries.filter { it.notation.endsWith("2") }
        var ambiguousCubes = 0
        var wrongCubes = 0
        repeat(200) {
            val cube = FaceletCube.scrambled(List(random.nextInt(8, 20)) { halfTurns[random.nextInt(halfTurns.size)] })
            val truth = cube.toColors()

            val asHeld = OrientationFixer.orient(truth)!!
            assertEquals(truth, asHeld.colors)
            assertEquals(Face.entries.associateWith { 0 }, asHeld.rotations)
            if (asHeld.ambiguous.isNotEmpty()) ambiguousCubes++

            var scanned = truth
            for (face in Face.entries) scanned = OrientationFixer.rotateFace(scanned, face, random.nextInt(4))
            val orientation = OrientationFixer.orient(scanned)!!
            val wrong = (0 until Facelets.COUNT).filter { orientation.colors[it] != truth[it] }.toSet()
            if (wrong.isNotEmpty()) wrongCubes++
            assertTrue("wrong $wrong not in ambiguous ${orientation.ambiguous}", orientation.ambiguous.containsAll(wrong))
            // Ambiguous stickers never include centers, which no rotation moves.
            assertTrue(orientation.ambiguous.none { it % 9 == 4 })
        }
        println("OrientationFixer: half-turn scrambles, $ambiguousCubes/200 ambiguous, $wrongCubes/200 misread when held at random angles (all flagged)")
        assertTrue("expected ambiguous cases, got $ambiguousCubes", ambiguousCubes > 50)
    }

    @Test
    fun symmetricFacesAreNotAmbiguous() {
        // Every rotation of a one-color or checkerboard face is valid but shows the same colors.
        for (cube in listOf(FaceletCube.SOLVED, FaceletCube.scrambled(Move.parseSequence("U2 D2 F2 B2 L2 R2")))) {
            assertEquals(emptySet<Int>(), OrientationFixer.orient(cube.toColors())!!.ambiguous)
        }
        // The user's cube has exactly one valid reading.
        assertEquals(emptySet<Int>(), OrientationFixer.orient(usersCube())!!.ambiguous)
    }

    private fun preference(turns: IntArray): Int = turns.count { it != 0 } * 100 + turns.sumOf { minOf(it, 4 - it) }

    @Test
    fun prefersFewestRotatedFaces() {
        // A face of one color looks the same at any rotation, so it must not be reported as rotated.
        val checkerboard = FaceletCube.scrambled(Move.parseSequence("U2 D2 F2 B2 L2 R2"))
        val colors = OrientationFixer.rotateFace(checkerboard.toColors(), Face.F, 1) // symmetric face: no-op
        assertEquals(Face.entries.associateWith { 0 }, OrientationFixer.fix(colors)!!.second)
        val solved = FaceletCube.SOLVED.toColors()
        assertEquals(Face.entries.associateWith { 0 }, OrientationFixer.fix(solved)!!.second)
    }

    @Test
    fun agreesWithBruteForceSearch() {
        val random = Random(6)
        repeat(12) { n ->
            val cube = randomCube(random)
            var colors = cube.toColors()
            for (face in Face.entries) colors = OrientationFixer.rotateFace(colors, face, random.nextInt(4))
            if (n % 3 == 0) {
                // Break it: swap two non-center stickers of different colors.
                val m = colors.toMutableList()
                val i = (0 until 54).filter { it % 9 != 4 }.random(random)
                val j = (0 until 54).filter { it % 9 != 4 && m[it] != m[i] }.random(random)
                m[i] = m[j].also { m[j] = m[i] }
                colors = m
            }
            val expected = bruteForce(colors)
            val actual = OrientationFixer.fix(colors)
            assertEquals(expected?.first, actual?.first)
            assertEquals(expected?.second, actual?.second)
        }
    }

    @Test
    fun returnsNullWhenNoRotationHelps() {
        val colors = randomCube(Random(7)).toColors().toMutableList()
        // Ten white stickers: no rotation changes the color counts.
        val i = (0 until 54).first { it % 9 != 4 && colors[it] != CubeColor.WHITE }
        colors[i] = CubeColor.WHITE
        assertNull(OrientationFixer.fix(colors))
        // Repeated centers.
        val sameCenters = FaceletCube.SOLVED.toColors().toMutableList().also { it[Facelets.center(Face.U)] = CubeColor.RED }
        assertNull(OrientationFixer.fix(sameCenters))
    }

    @Test
    fun isFast() {
        val random = Random(8)
        val inputs = List(200) { k ->
            var colors = randomCube(random).toColors()
            for (face in Face.entries) colors = OrientationFixer.rotateFace(colors, face, random.nextInt(4))
            if (k % 2 == 0) colors else colors.shuffled(random).let { s ->
                // Garbage with the right centers and color counts (the hardest case to reject).
                val centers = Face.entries.map { colors[Facelets.center(it)] }
                val rest = s.toMutableList().apply { centers.forEach { remove(it) } }
                List(54) { i -> if (i % 9 == 4) centers[i / 9] else rest.removeAt(0) }
            }
        }
        inputs.forEach { OrientationFixer.fix(it) } // warm-up
        var worstMs = 0.0
        val start = System.nanoTime()
        for (colors in inputs) {
            val t = System.nanoTime()
            OrientationFixer.fix(colors)
            worstMs = maxOf(worstMs, (System.nanoTime() - t) / 1e6)
        }
        val averageMs = (System.nanoTime() - start) / 1e6 / inputs.size
        println("OrientationFixer.fix: average %.3f ms, worst %.3f ms".format(averageMs, worstMs))
        assertTrue("average $averageMs ms", averageMs < 5.0)
        assertTrue("worst $worstMs ms", worstMs < 50.0)
    }

    /** Reference: all 4^6 combinations through CubeValidator, same preference order as the fixer. */
    private fun bruteForce(colors: List<CubeColor>): Pair<List<CubeColor>, Map<Face, Int>>? {
        if (FaceletCube.schemeOf(colors) == null) return null
        var best: Pair<List<CubeColor>, IntArray>? = null
        var bestKey = Int.MAX_VALUE
        for (code in 0 until 4096) {
            val turns = IntArray(6) { (code shr (2 * (5 - it))) and 3 }
            var c = colors
            for (face in Face.entries) c = OrientationFixer.rotateFace(c, face, turns[face.ordinal])
            if (!CubeValidator.validate(c).isValid) continue
            val key = preference(turns)
            if (key < bestKey) {
                bestKey = key
                best = c to turns
            }
        }
        return best?.let { (c, t) -> c to Face.entries.associateWith { t[it.ordinal] } }
    }

    /** The user's cube from the photo ground truth, each face as photographed. */
    private fun usersCube(): List<CubeColor> {
        val raw = Photos.TRUTH.filterKeys { it != "5_red_rotated" }.mapValues { Photos.truth(it.key) }
        val byCenter = raw.values.associateBy { it[4] }
        return Face.entries.flatMap { face -> byCenter.getValue(ColorScheme.STANDARD.colorOf(face)) }
    }

    @Test
    fun bruteForceFindsTheUsersCubeUnique() {
        // The real cube from the photos: exactly one rotation combination is valid.
        val colors = usersCube()
        var valid = 0
        for (code in 0 until 4096) {
            var c = colors
            for (face in Face.entries) c = OrientationFixer.rotateFace(c, face, (code shr (2 * face.ordinal)) and 3)
            if (CubeValidator.validate(c).isValid) valid++
        }
        assertEquals(1, valid)
        assertNotNull(OrientationFixer.fix(colors))
    }
}
