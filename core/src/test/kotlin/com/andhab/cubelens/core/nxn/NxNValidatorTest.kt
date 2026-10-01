package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubeValidator
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNTestCubes.recolor
import com.andhab.cubelens.core.nxn.NxNTestCubes.rotate
import com.andhab.cubelens.core.nxn.NxNTestCubes.swap
import com.andhab.cubelens.core.solver.Scrambler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NxNValidatorTest {

    private val standard = ColorScheme.STANDARD.centers

    private fun idx(n: Int, face: Face, row: Int, col: Int) = NxNGeometry.of(n).index(face, row, col)

    /** Stickers of the URF corner (U, R, F). */
    private fun urf(n: Int) = intArrayOf(idx(n, Face.U, n - 1, n - 1), idx(n, Face.R, 0, 0), idx(n, Face.F, 0, n - 1))

    private fun NxNValidation.errorFlagging(vararg stickers: Int): NxNError? =
        errors.firstOrNull { e -> stickers.all { it in e.stickers } }

    private fun assertInvalid(validation: NxNValidation, messagePart: String, vararg flagged: Int) {
        assertFalse(validation.isValid)
        val error = if (flagged.isEmpty()) {
            validation.errors.firstOrNull { it.message.contains(messagePart, ignoreCase = true) }
        } else {
            validation.errorFlagging(*flagged)
        }
        assertTrue("no error flagging ${flagged.toList()} in ${validation.errors}", error != null)
        val message = error!!.message
        assertTrue("'$message' should mention '$messagePart'", message.contains(messagePart, ignoreCase = true))
        if (flagged.isEmpty()) assertTrue(error.stickers.isEmpty())
    }

    @Test
    fun solvedCubesAreValid() {
        for (n in 2..10) {
            val validation = NxNValidator.validate(NxNCube.solved(n))
            assertTrue("$n: ${validation.errors}", validation.isValid)
            assertEquals(standard, validation.scheme)
        }
    }

    @Test
    fun scrambledCubesAreValidAndTheSchemeComesFromCentersOrTheDblCorner() {
        val random = Random(17)
        for (n in 2..7) repeat(8) {
            val cube = NxNTestCubes.scrambled(n, random)
            val validation = NxNValidator.validate(cube)
            assertTrue("$n: ${validation.errors}", validation.isValid)
            val scheme = validation.scheme!!
            if (n % 2 == 1) {
                assertEquals(standard, scheme)
            } else {
                val (d, b, l) = NxNTestCubes.dblStickers(n)
                assertEquals(cube[d], scheme[Face.D])
                assertEquals(cube[b], scheme[Face.B])
                assertEquals(cube[l], scheme[Face.L])
                // Still the standard scheme, seen from another side: same opposite pairs.
                for (f in Face.entries) {
                    val pair = setOf(scheme.getValue(f), scheme.getValue(f.opposite))
                    assertTrue(pair in standard.keys.map { setOf(standard.getValue(it), standard.getValue(it.opposite)) })
                }
            }
        }
    }

    @Test
    fun knockOffSchemesAreAccepted() {
        val random = Random(23)
        val whiteOppositeBlue = mapOf(
            Face.U to CubeColor.WHITE, Face.D to CubeColor.BLUE, Face.F to CubeColor.RED,
            Face.B to CubeColor.ORANGE, Face.R to CubeColor.YELLOW, Face.L to CubeColor.GREEN,
        )
        for (n in 2..6) {
            for (scheme in listOf(whiteOppositeBlue) + List(5) { NxNTestCubes.randomScheme(random) }) {
                val solved = NxNCube.solved(n, scheme)
                assertEquals(scheme, NxNValidator.validate(solved).scheme)
                val cube = NxNTestCubes.scrambled(n, random, scheme)
                assertTrue(NxNValidator.validate(cube).isValid)
            }
        }
    }

    /** For a 3×3 the verdict always agrees with the 3×3 validator, for valid and broken cubes alike. */
    @Test
    fun threeByThreeAgreesWithCubeValidator() {
        val random = Random(31)
        val g = NxNGeometry.of(3)
        var invalid = 0
        repeat(3000) {
            var cube = NxNCube.fromFaceletCube(Scrambler.randomState(random))
                .let { c -> NxNCube.of(3, c.toColors().map { color -> CubeColor.entries[(color.ordinal + it) % 6] }) }
            when (random.nextInt(6)) {
                0 -> Unit
                1 -> cube = swap(cube, random.nextInt(54), random.nextInt(54))
                2 -> cube = recolor(cube, random.nextInt(54), CubeColor.entries[random.nextInt(6)])
                3 -> cube = rotate(cube, NxNModel.of(3).corners[random.nextInt(8)])
                4 -> cube = rotate(cube, NxNModel.of(3).midges!![random.nextInt(12)])
                else -> repeat(2) { cube = swap(cube, random.nextInt(54), random.nextInt(54)) }
            }
            val expected = CubeValidator.validate(cube.toColors()).isValid
            val validation = NxNValidator.validate(cube)
            assertEquals("$cube: ${validation.errors}", expected, validation.isValid)
            if (!expected) invalid++
            assertTrue(validation.flaggedStickers.all { it in 0 until g.stickerCount })
        }
        assertTrue("only $invalid invalid cubes tried", invalid > 1000)
    }

    @Test
    fun wrongColorCountsAreReportedAndTheOddStickerIsFlagged() {
        val random = Random(41)
        for (n in 2..6) {
            val cube = NxNTestCubes.scrambled(n, random)
            val i = idx(n, Face.F, n / 2, (n - 1) / 2)
            val other = CubeColor.entries.first { it != cube[i] }
            val validation = NxNValidator.validate(recolor(cube, i, other))
            assertFalse(validation.isValid)
            assertTrue(validation.errors.any { it.message.contains("of each color") && it.stickers.isEmpty() })
            assertTrue("$n: ${validation.errors}", i in validation.flaggedStickers)
        }
    }

    @Test
    fun fewerThanSixColors() {
        val cube = NxNCube.of(4, NxNCube.solved(4).toColors().map { if (it == CubeColor.BLUE) CubeColor.GREEN else it })
        val validation = NxNValidator.validate(cube)
        assertFalse(validation.isValid)
        assertNull(validation.scheme)
        assertTrue(validation.errors.single().message.contains("six"))
    }

    @Test
    fun impossibleCornerWithOppositeColors() {
        for (n in 2..5) {
            val (u, r, f) = urf(n)
            // White, yellow and green: white and yellow belong on opposite sides.
            val validation = NxNValidator.validate(recolor(NxNCube.solved(n), r, CubeColor.YELLOW))
            assertInvalid(validation, "opposite", u, r, f)
        }
    }

    @Test
    fun mirroredCorner() {
        for (n in 2..5) {
            val (u, r, f) = urf(n)
            assertInvalid(NxNValidator.validate(swap(NxNCube.solved(n), r, f)), "mirror", u, r, f)
        }
    }

    @Test
    fun mirroredDblCornerLeavesTheEvenSchemeUnknown() {
        val (d, b, l) = NxNTestCubes.dblStickers(4)
        val validation = NxNValidator.validate(swap(NxNCube.solved(4), b, l))
        assertInvalid(validation, "mirror", d, b, l)
        assertNull(validation.scheme)
    }

    @Test
    fun duplicateCorner() {
        val n = 2
        val cube = NxNCube.solved(n)
        // Paint the UFL corner like URF: white, red, green.
        val ufl = NxNModel.of(n).corners[1]
        val urf = NxNModel.of(n).corners[0]
        var broken = cube
        for (k in 0 until 3) broken = recolor(broken, ufl[k], cube[urf[k]])
        assertInvalid(NxNValidator.validate(broken), "appears 2 times", *ufl, *urf)
    }

    @Test
    fun twistedCorner() {
        val random = Random(43)
        for (n in 2..6) {
            val cube = NxNTestCubes.scrambled(n, random)
            assertInvalid(NxNValidator.validate(rotate(cube, urf(n))), "twisted")
        }
    }

    @Test
    fun impossibleWingPair() {
        for (n in listOf(4, 5, 6)) {
            val u = idx(n, Face.U, n - 1, 1)
            val f = idx(n, Face.F, 0, 1)
            // White-green wing gets a yellow sticker from the D center: white and yellow are opposite.
            val validation = NxNValidator.validate(swap(NxNCube.solved(n), f, idx(n, Face.D, 1, 1)))
            assertInvalid(validation, "opposite", u, f)
        }
    }

    @Test
    fun wingWithMirroredHandednessIsADuplicate() {
        val random = Random(47)
        for (n in listOf(4, 5, 6, 7)) {
            val u = idx(n, Face.U, n - 1, 1)
            val f = idx(n, Face.F, 0, 1)
            assertInvalid(NxNValidator.validate(swap(NxNCube.solved(n), u, f)), "mirrored", u, f)
            // Also in a scrambled cube: flip whichever wing sits in that slot.
            val cube = NxNTestCubes.scrambled(n, random)
            assertInvalid(NxNValidator.validate(swap(cube, u, f)), "mirrored", u, f)
        }
    }

    @Test
    fun flippedMiddleEdge() {
        val random = Random(53)
        for (n in listOf(3, 5, 7)) {
            val midge = NxNModel.of(n).midges!![CubeIndices.UF]
            val cube = NxNTestCubes.scrambled(n, random)
            assertInvalid(NxNValidator.validate(swap(cube, midge[0], midge[1])), "flipped")
        }
    }

    @Test
    fun middleEdgeAndCornerParity() {
        for (n in listOf(3, 5, 7)) {
            val midges = NxNModel.of(n).midges!!
            val uf = midges[CubeIndices.UF]
            val ur = midges[CubeIndices.UR]
            val cube = swap(swap(NxNCube.solved(n), uf[0], ur[0]), uf[1], ur[1])
            assertInvalid(NxNValidator.validate(cube), "swapped")
        }
    }

    @Test
    fun duplicateMiddleEdge() {
        val n = 5
        val midges = NxNModel.of(n).midges!!
        val uf = midges[CubeIndices.UF]
        val ur = midges[CubeIndices.UR]
        // UR painted like UF (white-green); the red sticker reappears on a center to keep counts.
        val cube = recolor(NxNCube.solved(n), ur[1], CubeColor.GREEN)
        assertInvalid(NxNValidator.validate(cube), "appears 2 times", *uf, *ur)
    }

    @Test
    fun wrongCenterCountsPerOrbit() {
        for (n in listOf(5, 7)) {
            val x = idx(n, Face.U, 1, 1) // an x-center
            val plus = idx(n, Face.R, 1, n / 2) // a +-center
            val validation = NxNValidator.validate(swap(NxNCube.solved(n), x, plus))
            assertFalse(validation.isValid)
            assertInvalid(validation, "center pieces", x)
            assertInvalid(validation, "center pieces", plus)
            assertTrue(validation.errors.filter { it.message.contains("center pieces") }.size == 2)
        }
        // Even cube: one center orbit gets five red pieces.
        val n = 4
        val i = idx(n, Face.U, 1, 1)
        val validation = NxNValidator.validate(swap(NxNCube.solved(n), i, idx(n, Face.R, 0, 1)))
        assertInvalid(validation, "center pieces", i)
    }

    @Test
    fun fixedCentersMustDifferAndMatchTheCorners() {
        for (n in listOf(3, 5)) {
            val m = NxNModel.of(n)
            val centers = m.fixedCenters!!
            val uCenter = centers[Face.U.ordinal]
            val fCenter = centers[Face.F.ordinal]
            // Two green centers.
            val twice = swap(NxNCube.solved(n), uCenter, if (n == 3) idx(n, Face.F, 0, 0) else idx(n, Face.F, 1, 2))
            val v1 = NxNValidator.validate(twice)
            assertInvalid(v1, "different center", uCenter, fCenter)
            assertNull(v1.scheme)
            // Centers swapped: distinct, but the corners say otherwise.
            val v2 = NxNValidator.validate(swap(NxNCube.solved(n), uCenter, fCenter))
            assertInvalid(v2, "match the corner", uCenter, fCenter)
            assertNull(v2.scheme)
        }
    }

    /** Kociemba edge indices used above. */
    private object CubeIndices {
        const val UR = 0
        const val UF = 1
    }
}
