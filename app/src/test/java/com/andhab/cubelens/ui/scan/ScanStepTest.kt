package com.andhab.cubelens.ui.scan

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Vec3
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.NxNScrambler
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ScanStepTest {

    private val scheme = ColorScheme.STANDARD

    @Test
    fun everyFaceIsScannedOnceInTheGuidedOrder() {
        assertEquals(listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D), ScanStep.entries.map { it.face })
        assertEquals((1..6).toList(), ScanStep.entries.map { it.number })
        for (step in ScanStep.entries) assertEquals(scheme.colorOf(step.face), step.color)
    }

    @Test
    fun holdsAreTheResolversReferenceOrientation() {
        // Side faces with white on top, white with blue on top, yellow with green on top.
        for (step in listOf(ScanStep.Front, ScanStep.Right, ScanStep.Back, ScanStep.Left)) assertEquals(CubeColor.WHITE, step.topColor)
        assertEquals(CubeColor.BLUE, ScanStep.Top.topColor)
        assertEquals(CubeColor.GREEN, ScanStep.Bottom.topColor)
    }

    @Test
    fun eachHoldPutsItsFaceInFrontWithItsTopFaceUp() {
        for (n in 2..7) {
            val geometry = NxNGeometry.of(n)
            // Label every sticker by the face it starts on, then hold the cube for each step.
            val faces = List(geometry.stickerCount) { geometry.faceOf(it) }
            for (step in ScanStep.entries) {
                val held = step.held(faces, n)
                assertEquals("$n $step front", List(n * n) { step.face }, faceOf(held, Face.F, n))
                assertEquals("$n $step top", List(n * n) { step.topFace }, faceOf(held, Face.U, n))
            }
        }
    }

    @Test
    fun heldAsInstructedTheCameraReadsEachFaceInStickerOrder() {
        // Whatever the cube's colors, the face in front while held for a step is that face's
        // stickers in NxNGeometry order: row-major as seen, the top row next to the top face.
        for (n in 2..7) {
            val cube = NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(n))).toColors()
            for (step in ScanStep.entries) {
                assertEquals("$n $step", NxNCube.of(n, cube).face(step.face), faceOf(step.held(cube, n), Face.F, n))
            }
        }
    }

    @Test
    fun heldSolvedCubeIsARealRotationOfTheStandardCube() {
        for (step in ScanStep.entries) {
            val colors = step.heldSolvedCube(3)
            val center = { face: Face -> colors[face.ordinal * 9 + 4] }
            assertEquals(step.color, center(Face.F))
            assertEquals(step.topColor, center(Face.U))
            assertEquals(CubeColor.entries.toSet(), Face.entries.map(center).toSet())
            // Right-handed: right = up x front, as for the standard orientation (not a mirror image).
            val front = scheme.faceOf(step.color).normal
            val up = scheme.faceOf(step.topColor).normal
            assertEquals("$step", scheme.faceOf(center(Face.R)).normal, up cross front)
        }
    }

    @Test
    fun forColorFindsTheStepOfACenter() {
        for (step in ScanStep.entries) assertEquals(step, ScanStep.forColor(step.color))
    }

    private fun <T> faceOf(colors: List<T>, face: Face, n: Int): List<T> = colors.subList(face.ordinal * n * n, (face.ordinal + 1) * n * n)

    private infix fun Vec3.cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
}
