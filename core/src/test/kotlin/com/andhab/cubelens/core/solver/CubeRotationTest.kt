package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.CubeValidator
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CubeRotationTest {

    @Test
    fun rotationBringsEachAxisToUpDown() {
        assertEquals(Face.U, CubeRotation.faceImage(Face.U, 0))
        assertEquals(Face.U, CubeRotation.faceImage(Face.R, 1))
        assertEquals(Face.F, CubeRotation.faceImage(Face.U, 1))
        assertEquals(Face.R, CubeRotation.faceImage(Face.F, 1))
        assertEquals(Face.U, CubeRotation.faceImage(Face.F, 2))
        for (times in 0 until 3) {
            assertEquals(Face.entries.toSet(), Face.entries.map { CubeRotation.faceImage(it, times) }.toSet())
            for (face in Face.entries) {
                assertEquals(CubeRotation.faceImage(face, times).opposite, CubeRotation.faceImage(face.opposite, times))
            }
        }
    }

    @Test
    fun rotatingThreeTimesIsTheIdentity() {
        assertEquals(FaceletCube.SOLVED, CubeRotation.rotate(FaceletCube.SOLVED, 1))
        val random = Random(9)
        repeat(50) {
            val cube = Scrambler.randomState(random)
            val once = CubeRotation.rotate(cube, 1)
            assertTrue(CubeValidator.validate(once).isValid)
            assertEquals(CubeRotation.rotate(cube, 2), CubeRotation.rotate(once, 1))
            assertEquals(cube, CubeRotation.rotate(CubeRotation.rotate(cube, 2), 1))
        }
    }

    @Test
    fun turnsCommuteWithRotationOntoTheImageFace() {
        val random = Random(10)
        repeat(20) {
            val cube = Scrambler.randomState(random)
            for (times in 0 until 3) {
                for (move in Move.entries) {
                    val rotatedMove = Move.of(CubeRotation.faceImage(move.face, times), move.turns)
                    assertEquals(
                        CubeRotation.rotate(cube.apply(move), times),
                        CubeRotation.rotate(cube, times).apply(rotatedMove),
                    )
                    assertEquals(move, CubeRotation.unrotate(rotatedMove, times))
                }
            }
        }
    }
}
