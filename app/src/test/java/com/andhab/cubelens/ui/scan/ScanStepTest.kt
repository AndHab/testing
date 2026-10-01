package com.andhab.cubelens.ui.scan

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Vec3
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanStepTest {

    private val scheme = ColorScheme.STANDARD

    @Test
    fun everyFaceIsScannedOnceInTheGuidedOrder() {
        assertEquals(listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D), ScanStep.entries.map { it.face })
        for (step in ScanStep.entries) assertEquals(scheme.colorOf(step.face), step.color)
    }

    @Test
    fun holdsAreTheResolversReferenceOrientation() {
        // Side faces with white on top, white with blue on top, yellow with green on top.
        for (step in listOf(ScanStep.Green, ScanStep.Red, ScanStep.Blue, ScanStep.Orange)) assertEquals(CubeColor.WHITE, step.topColor)
        assertEquals(CubeColor.BLUE, ScanStep.White.topColor)
        assertEquals(CubeColor.GREEN, ScanStep.Yellow.topColor)
    }

    @Test
    fun heldAsInstructedTheScreenShowsTheFaceInFaceletOrder() {
        // Row-major as seen must equal the face's facelet order: the top row borders the top color's
        // face and the right column borders the right color's face.
        for (step in ScanStep.entries) {
            val top = scheme.faceOf(step.topColor)
            val right = scheme.faceOf(step.rightColor)
            assertEquals("$step top", 1, Facelets.position[Facelets.index(step.face, 0, 1)] dot top.normal)
            assertEquals("$step right", 1, Facelets.position[Facelets.index(step.face, 1, 2)] dot right.normal)
        }
    }

    @Test
    fun heldCubeIsARealRotationOfTheStandardCube() {
        for (step in ScanStep.entries) {
            val colors = step.heldCubeColors
            assertEquals(step.color, colors[Facelets.center(Face.F)])
            assertEquals(step.topColor, colors[Facelets.center(Face.U)])
            assertEquals(step.rightColor, colors[Facelets.center(Face.R)])
            assertEquals(CubeColor.entries.toSet(), Face.entries.map { colors[Facelets.center(it)] }.toSet())
            // Right-handed: right = up x front, as for the standard orientation (not a mirror image).
            val front = scheme.faceOf(step.color).normal
            val up = scheme.faceOf(step.topColor).normal
            assertEquals("$step", scheme.faceOf(step.rightColor).normal, up cross front)
        }
    }

    private infix fun Vec3.cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
}
