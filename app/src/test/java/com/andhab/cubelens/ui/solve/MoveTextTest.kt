package com.andhab.cubelens.ui.solve

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The plain-words descriptions of every kind of [LayerMove] (outer, wide, inner slice, layer range,
 * whole cube) counted from every side, with their direction, viewpoint and spoken notation.
 */
@RunWith(AndroidJUnit4::class)
class MoveTextTest {

    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun describe(notation: String, n: Int): String = resources.moveDescription(LayerMove.parse(notation), n)

    private fun viewpoint(notation: String, n: Int): String? = resources.moveWords(LayerMove.parse(notation), n).viewpoint

    @Test
    fun outerFaces() {
        assertEquals("Right face · clockwise", describe("R", 3))
        assertEquals("Top face · half turn", describe("U2", 3))
        assertEquals("Front face · counter-clockwise", describe("F'", 3))
        assertEquals("Back face · counter-clockwise", describe("B'", 3))
        assertEquals("Bottom face · clockwise", describe("D", 7))
        assertEquals("Left face · half turn", describe("L2", 2))
    }

    @Test
    fun wideMoves() {
        assertEquals("Right two layers · clockwise", describe("Rw", 4))
        assertEquals("Right 3 layers · counter-clockwise", describe("3Rw'", 7))
        assertEquals("Front 3 layers · clockwise", describe("3Fw", 4))
        assertEquals("Top 5 layers · clockwise", describe("5Uw", 6))
        assertEquals("Bottom two layers · half turn", describe("Dw2", 5))
        assertEquals("Left two layers · counter-clockwise", describe("Lw'", 4))
        assertEquals("Back 3 layers · clockwise", describe("3Bw", 7))
        // Lowercase is the same wide move.
        assertEquals(describe("Rw", 5), describe("r", 5))
    }

    @Test
    fun innerSlices() {
        assertEquals("2nd layer from the right · clockwise", describe("2R", 3))
        assertEquals("3rd layer from the top · counter-clockwise", describe("3U'", 5))
        assertEquals("2nd layer from the bottom · clockwise", describe("2D", 4))
        assertEquals("2nd layer from the back · half turn", describe("2B2", 4))
        assertEquals("4th layer from the left · clockwise", describe("4L", 7))
        assertEquals("3rd layer from the front · counter-clockwise", describe("3F'", 6))
    }

    @Test
    fun layerRanges() {
        assertEquals("Layers 2–3 from the right · clockwise", describe("2-3Rw", 4))
        assertEquals("Layers 2–5 from the front · counter-clockwise", describe("2-5Fw'", 6))
        assertEquals("Layers 3–4 from the left · half turn", describe("3-4Lw2", 7))
        assertEquals("Layers 2–3 from the top · clockwise", describe("2-3Uw", 7))
        assertEquals("Layers 2–4 from the bottom · counter-clockwise", describe("2-4Dw'", 5))
        assertEquals("Layers 2–3 from the back · clockwise", describe("2-3Bw", 4))
    }

    @Test
    fun turningEveryLayerIsTheWholeCube() {
        assertEquals("Whole cube · clockwise", describe("Rw", 2))
        assertEquals("Whole cube · counter-clockwise", describe("3Uw'", 3))
        assertEquals("Whole cube · half turn", describe("7Fw2", 7))
        // Every whole-cube turn says where it is seen from, also for the sides the person faces.
        assertEquals("Seen from the right", viewpoint("Rw", 2))
        assertEquals("Seen from above", viewpoint("3Uw", 3))
        assertEquals("Seen from the front", viewpoint("4Fw", 4))
        assertEquals("Seen from below", viewpoint("4Dw", 4))
    }

    @Test
    fun sidesFacingAwayExplainWhereClockwiseIsSeenFrom() {
        for (notation in listOf("B", "Bw", "2B'", "2-3Bw2")) assertEquals(notation, "Seen from behind", viewpoint(notation, 5))
        for (notation in listOf("D'", "3Dw", "2D", "2-3Dw")) assertEquals(notation, "Seen from below", viewpoint(notation, 5))
        for (notation in listOf("L2", "Lw'", "2L", "2-3Lw")) assertEquals(notation, "Seen from the left", viewpoint(notation, 5))
        for (notation in listOf("R", "Uw", "2F", "2-3Rw'", "3U'")) assertNull(notation, viewpoint(notation, 5))
    }

    @Test
    fun spokenNotationNeverReadsASliceNumberAsAHalfTurn() {
        fun spoken(notation: String) = resources.spokenMove(LayerMove.parse(notation))
        assertEquals("R", spoken("R"))
        assertEquals("R prime", spoken("R'"))
        assertEquals("U two", spoken("U2"))
        assertEquals("2R prime", spoken("2R'"))
        assertEquals("2R two", spoken("2R2"))
        assertEquals("2R", spoken("2R"))
        assertEquals("Rw prime", spoken("Rw'"))
        assertEquals("3Fw", spoken("3Fw"))
        assertEquals("2-3Rw two", spoken("2-3Rw2"))
    }

    @Test
    fun stageNames() {
        assertEquals("Solve", resources.stageName(NxNSolver.STAGE_SOLVE))
        assertEquals("Corners", resources.stageName(NxNSolver.STAGE_CORNERS))
        assertEquals("Corners & middle edges", resources.stageName(NxNSolver.STAGE_FRAME))
        assertEquals("Edges", resources.stageName(NxNSolver.STAGE_EDGES))
        assertEquals("Centers", resources.stageName(NxNSolver.STAGE_CENTERS))
        assertEquals("Something new", resources.stageName("Something new"))
    }

    @Test
    fun faceNames() {
        assertEquals(
            listOf("Top face", "Right face", "Front face", "Bottom face", "Left face", "Back face"),
            Face.entries.map { resources.faceName(it) },
        )
    }
}
