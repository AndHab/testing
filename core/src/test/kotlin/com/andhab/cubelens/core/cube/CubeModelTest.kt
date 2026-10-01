package com.andhab.cubelens.core.cube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CubeModelTest {

    private val solved = FaceletCube.SOLVED

    private fun facesAt(cube: FaceletCube, face: Face, vararg cells: Int) =
        cells.map { cube[Facelets.index(face, it / 3, it % 3)] }

    @Test
    fun geometryIsABijection() {
        val keys = (0 until Facelets.COUNT).map { Facelets.position[it] to Facelets.normal[it] }.toSet()
        assertEquals(54, keys.size)
        for (i in 0 until Facelets.COUNT) {
            assertEquals(i, Facelets.indexOf(Facelets.position[i], Facelets.normal[i]))
            assertEquals(Facelets.faceOf(i).normal, Facelets.normal[i])
        }
    }

    @Test
    fun everyMoveHasOrderFourAndInverseUndoes() {
        val rnd = Random(1)
        val start = FaceletCube.scrambled(List(30) { Move.entries[rnd.nextInt(18)] })
        for (m in Move.entries) {
            val q = Move.of(m.face, 1)
            assertEquals(start, start.apply(listOf(q, q, q, q)))
            assertEquals(start, start.apply(m).apply(m.inverse))
            assertEquals(start.apply(List(m.turns) { q }), start.apply(m))
        }
    }

    @Test
    fun standardMoveDirections() {
        // U: front top row goes to the left face.
        assertEquals(List(3) { Face.F }, facesAt(solved.apply(Move.U1), Face.L, 0, 1, 2))
        // R: front right column goes up.
        assertEquals(List(3) { Face.F }, facesAt(solved.apply(Move.R1), Face.U, 2, 5, 8))
        // F: top face's bottom row goes to the right face's left column.
        assertEquals(List(3) { Face.U }, facesAt(solved.apply(Move.F1), Face.R, 0, 3, 6))
        // D: front bottom row goes to the right face.
        assertEquals(List(3) { Face.F }, facesAt(solved.apply(Move.D1), Face.R, 6, 7, 8))
        // L: top face's left column goes to the front.
        assertEquals(List(3) { Face.U }, facesAt(solved.apply(Move.L1), Face.F, 0, 3, 6))
        // B: right face's right column goes to the top face's top row.
        assertEquals(List(3) { Face.R }, facesAt(solved.apply(Move.B1), Face.U, 0, 1, 2))
    }

    @Test
    fun moveCubesMatchKociembaTables() {
        val u = CubieCube.moveCube(Move.U1)
        assertEquals(listOf(3, 0, 1, 2, 4, 5, 6, 7), u.cp.toList())
        assertEquals(List(8) { 0 }, u.co.toList())
        assertEquals(listOf(3, 0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11), u.ep.toList())
        assertEquals(List(12) { 0 }, u.eo.toList())

        val r = CubieCube.moveCube(Move.R1)
        assertEquals(listOf(4, 1, 2, 0, 7, 5, 6, 3), r.cp.toList())
        assertEquals(listOf(2, 0, 0, 1, 1, 0, 0, 2), r.co.toList())
        assertEquals(listOf(8, 1, 2, 3, 11, 5, 6, 7, 4, 9, 10, 0), r.ep.toList())
        assertEquals(List(12) { 0 }, r.eo.toList())

        val f = CubieCube.moveCube(Move.F1)
        assertEquals(listOf(1, 5, 2, 3, 0, 4, 6, 7), f.cp.toList())
        assertEquals(listOf(1, 2, 0, 0, 2, 1, 0, 0), f.co.toList())
        assertEquals(listOf(0, 9, 2, 3, 4, 8, 6, 7, 1, 5, 10, 11), f.ep.toList())
        assertEquals(listOf(0, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0), f.eo.toList())

        val d = CubieCube.moveCube(Move.D1)
        assertEquals(listOf(0, 1, 2, 3, 5, 6, 7, 4), d.cp.toList())
        assertEquals(listOf(0, 1, 2, 3, 5, 6, 7, 4, 8, 9, 10, 11), d.ep.toList())

        val l = CubieCube.moveCube(Move.L1)
        assertEquals(listOf(0, 2, 6, 3, 4, 1, 5, 7), l.cp.toList())
        assertEquals(listOf(0, 1, 2, 0, 0, 2, 1, 0), l.co.toList())
        assertEquals(listOf(0, 1, 10, 3, 4, 5, 9, 7, 8, 2, 6, 11), l.ep.toList())

        val b = CubieCube.moveCube(Move.B1)
        assertEquals(listOf(0, 1, 3, 7, 4, 5, 2, 6), b.cp.toList())
        assertEquals(listOf(0, 0, 1, 2, 0, 0, 2, 1), b.co.toList())
        assertEquals(listOf(0, 1, 2, 11, 4, 5, 6, 10, 8, 9, 3, 7), b.ep.toList())
        assertEquals(listOf(0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 1), b.eo.toList())
    }

    @Test
    fun cubieAndFaceletLevelsAgree() {
        val rnd = Random(7)
        repeat(200) {
            val seq = List(rnd.nextInt(1, 40)) { Move.entries[rnd.nextInt(18)] }
            val facelet = FaceletCube.scrambled(seq)
            val cubie = CubieCube.SOLVED.apply(seq)
            assertEquals(facelet, cubie.toFaceletCube())
            assertEquals(cubie, CubieCube.fromFacelets(facelet))
            assertTrue(CubeValidator.validate(facelet).isValid)
            assertTrue(cubie.multiply(cubie.inverse()).isSolved)
        }
    }

    @Test
    fun parseAndFormat() {
        val seq = Move.parseSequence("R U R' U'  F2 B3 L1 D2'")
        assertEquals("R U R' U' F2 B' L D2", Move.format(seq))
        assertEquals(Move.R3, Move.parse("R’"))
        val s = FaceletCube.scrambled(seq)
        assertEquals(s, FaceletCube.parse(s.toFaceletString()))
    }

    @Test
    fun colorsRoundTrip() {
        val cube = FaceletCube.scrambled(Move.parseSequence("R U2 F' L D B2"))
        val colors = cube.toColors()
        assertEquals(cube, FaceletCube.fromColors(colors))
        assertEquals(ColorScheme.STANDARD, FaceletCube.schemeOf(colors))
        val badCenters = colors.toMutableList().also { it[Facelets.center(Face.U)] = CubeColor.RED }
        assertNull(FaceletCube.fromColors(badCenters))
        assertEquals(listOf(CubeError.CentersNotDistinct), CubeValidator.validate(badCenters).errors)
    }

    @Test
    fun validatorDetectsTwistFlipAndParity() {
        val base = CubieCube.SOLVED.apply(Move.parseSequence("R U F D L B R2 U'"))

        val twisted = base.copy().also { it.co[0] = (it.co[0] + 1) % 3 }
        assertEquals(listOf(CubeError.TwistedCorner), CubeValidator.validate(twisted.toFaceletCube()).errors)

        val flipped = base.copy().also { it.eo[3] = 1 - it.eo[3] }
        assertEquals(listOf(CubeError.FlippedEdge), CubeValidator.validate(flipped.toFaceletCube()).errors)

        val swapped = base.copy().also { val t = it.ep[0]; it.ep[0] = it.ep[1]; it.ep[1] = t }
        assertEquals(listOf(CubeError.Parity), CubeValidator.validate(swapped.toFaceletCube()).errors)
    }

    @Test
    fun validatorFlagsBadStickers() {
        val cube = FaceletCube.scrambled(Move.parseSequence("R U F"))
        val faces = cube.toList().toMutableList()
        // Give the UF edge two identical stickers, which no real edge has.
        val uf = CubieCube.EDGE_FACELET[CubieCube.UF]
        faces[uf[1]] = faces[uf[0]]
        val result = CubeValidator.validate(FaceletCube.of(faces))
        assertFalse(result.isValid)
        assertTrue(result.errors.any { it is CubeError.WrongColorCount })
        assertTrue(result.errors.any { it is CubeError.ImpossibleEdge && it.position == CubieCube.UF })
        assertTrue(result.flaggedFacelets.containsAll(uf.toList()))
    }
}
