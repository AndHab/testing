package com.andhab.cubelens.ui.cube

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** Pure-math tests of the renderer geometry (plain JVM, no Android). */
class CubeGeometryTest {

    @Test
    fun finishedTurnLandsExactlyOnTheCommittedState() {
        val r = FloatArray(9)
        val out = FloatArray(3)
        for (move in Move.entries) {
            val n = move.face.normal
            CubeGeometry.rotation(n.x, n.y, n.z, turnAngleDegrees(move, 1f), r)
            for (i in 0 until Facelets.COUNT) {
                if (!Facelets.isInLayer(i, move.face)) continue
                val j = (0 until Facelets.COUNT).single { move.permutation[it] == i }
                val p = Facelets.position[i]
                val q = Facelets.normal[i]

                CubeGeometry.transform(r, p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), out)
                assertLandsOn(Facelets.position[j].let { intArrayOf(it.x, it.y, it.z) }, out, "$move position of $i")
                CubeGeometry.transform(r, q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), out)
                assertLandsOn(Facelets.normal[j].let { intArrayOf(it.x, it.y, it.z) }, out, "$move normal of $i")
            }
        }
    }

    @Test
    fun rendererLayerMatchesFaceletLayer() {
        for (face in Face.entries) {
            val axis = CubeGeometry.axisOf(face)
            val slab = CubeGeometry.slabOf(face)
            for (i in 0 until Facelets.COUNT) {
                val p = Facelets.position[i]
                val coord = intArrayOf(p.x, p.y, p.z)[axis]
                assertEquals("$face facelet $i", Facelets.isInLayer(i, face), coord == slab)
            }
        }
    }

    @Test
    fun primeMovesTurnBackwardsAndHalfTurnsTwice() {
        assertEquals(-90f, turnAngleDegrees(Move.U1, 1f), 0f)
        assertEquals(-180f, turnAngleDegrees(Move.U2, 1f), 0f)
        assertEquals(90f, turnAngleDegrees(Move.U3, 1f), 0f)
        for (move in Move.entries) {
            assertEquals(0f, turnAngleDegrees(move, 0f), 0f)
            // Never overshoots and moves monotonically.
            var last = 0f
            for (k in 1..100) {
                val a = abs(turnAngleDegrees(move, k / 100f))
                assertTrue("$move at $k", a >= last - 1e-4f && a <= 90f * abs(move.signedQuarterTurns) + 1e-4f)
                last = a
            }
        }
    }

    @Test
    fun faceAxesAreRightHanded() {
        for (d in 0 until 6) {
            val u = IntArray(3) { CubeGeometry.faceU[d * 3 + it] }
            val v = IntArray(3) { CubeGeometry.faceV[d * 3 + it] }
            val cross = intArrayOf(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0])
            assertEquals(Face.entries[d].normal.let { listOf(it.x, it.y, it.z) }, cross.toList())
        }
    }

    @Test
    fun stickerTableMatchesFacelets() {
        var stickers = 0
        for (c in 0 until CubeGeometry.CUBIE_COUNT) for (d in 0 until 6) {
            val facelet = CubeGeometry.sticker[c * 6 + d]
            if (facelet < 0) continue
            stickers++
            val p = Facelets.position[facelet]
            assertEquals(c, CubeGeometry.cubieIndex(p.x, p.y, p.z))
            assertEquals(Face.entries[d], Facelets.faceOf(facelet))
        }
        assertEquals(Facelets.COUNT, stickers)
    }

    @Test
    fun viewAnglesPresentTheRequestedFace() {
        val view = FloatArray(9)
        val n = FloatArray(3)
        val up = FloatArray(3)
        val front = FloatArray(3)
        for (face in Face.entries) {
            val (yaw, pitch) = viewAnglesFor(face)
            assertTrue(pitch in CubeViewState.MIN_PITCH..CubeViewState.MAX_PITCH)
            CubeGeometry.viewRotation(yaw, pitch, view)
            // The face looks at the camera more than any other face does.
            val towardsCamera = Face.entries.associateWith { f ->
                CubeGeometry.transform(view, f.normal.x.toFloat(), f.normal.y.toFloat(), f.normal.z.toFloat(), n)
                n[2]
            }
            assertEquals(face, towardsCamera.maxBy { it.value }.key)
            assertTrue("$face faces the camera", towardsCamera.getValue(face) > 0.8f)
            // ...but not dead-on, so it still reads as 3D.
            assertTrue("$face is three-quarter", towardsCamera.getValue(face) < 0.97f)

            CubeGeometry.transform(view, 0f, 1f, 0f, up)
            CubeGeometry.transform(view, 0f, 0f, 1f, front)
            when (face) {
                Face.U -> assertTrue("F at the bottom when showing U", front[1] < -0.5f)
                Face.D -> assertTrue("F at the top when showing D", front[1] > 0.5f)
                else -> assertTrue("U on top when showing $face", up[1] > 0.85f && up[2] > 0f)
            }
        }
    }

    @Test
    fun consecutiveSideFacesAreAQuarterTurnApart() {
        val sides = listOf(Face.F, Face.R, Face.B, Face.L)
        for (k in sides.indices) {
            val a = viewAnglesFor(sides[k]).first
            val b = viewAnglesFor(sides[(k + 1) % 4]).first
            assertEquals(-90f, wrapDegrees(b - a), 1e-4f)
        }
    }

    @Test
    fun slabOrderIsBackToFront() {
        val out = IntArray(3)
        CubeGeometry.slabOrder(5f, out)
        assertEquals(listOf(-1, 0, 1), out.toList())
        CubeGeometry.slabOrder(-5f, out)
        assertEquals(listOf(1, 0, -1), out.toList())
        CubeGeometry.slabOrder(0.2f, out)
        assertEquals(0, out[2])
        CubeGeometry.slabOrder(-0.3f, out)
        assertEquals(0, out[2])
        for (e in listOf(-3f, -0.7f, -0.2f, 0f, 0.4f, 0.6f, 3f)) {
            CubeGeometry.slabOrder(e, out)
            assertEquals(setOf(-1, 0, 1), out.toSet())
            // Distance from the eye to each slab's center never increases along the order.
            for (k in 0 until 2) assertTrue(abs(e - out[k]) >= abs(e - out[k + 1]))
        }
    }

    @Test
    fun cubieDrawOrderNeverPaintsANearerCubieFirst() {
        val random = Random(20261001)
        val view = FloatArray(9)
        val eye = FloatArray(3)
        val layer = FloatArray(9)
        val order = IntArray(CubeGeometry.CUBIE_COUNT)
        val scratch = CubeGeometry.OrderScratch()
        val local = FloatArray(3)
        repeat(600) { iteration ->
            val yaw = random.nextFloat() * 360f - 180f
            val pitch = random.nextFloat() * 160f - 80f
            val move = if (iteration % 6 == 0) null else Move.entries[random.nextInt(Move.entries.size)]
            val progress = random.nextFloat()
            CubeGeometry.viewRotation(yaw, pitch, view)
            CubeGeometry.eyePosition(view, eye)
            val axis = move?.let { CubeGeometry.axisOf(it.face) } ?: 1
            val turning = move?.let { CubeGeometry.slabOf(it.face) } ?: 0
            if (move != null) {
                val n = move.face.normal
                CubeGeometry.rotation(n.x, n.y, n.z, turnAngleDegrees(move, progress), layer)
            } else {
                CubeGeometry.identity(layer)
            }
            CubeGeometry.cubieDrawOrder(eye, axis, turning, layer, order, scratch)
            assertEquals((0 until CubeGeometry.CUBIE_COUNT).toSet(), order.toSet())

            for (i in order.indices) for (j in i + 1 until order.size) {
                val a = order[i]
                val b = order[j]
                val pa = position(a)
                val pb = position(b)
                val aTurns = turning != 0 && pa[axis] == turning
                val bTurns = turning != 0 && pb[axis] == turning
                val ok = if (aTurns != bTurns) {
                    // Different slabs along the turning axis: both stay inside their slab, so any plane
                    // between the two slabs separates them.
                    eyeOnSideOf(pa[axis], pb[axis], eye[axis])
                } else {
                    if (aTurns) CubeGeometry.transformTransposed(layer, eye[0], eye[1], eye[2], local) else eye.copyInto(local)
                    (0 until 3).any { k -> eyeOnSideOf(pa[k], pb[k], local[k]) }
                }
                assertTrue("iteration $iteration ($yaw, $pitch, $move @ $progress): cubie $a drawn before $b", ok)
            }
        }
    }

    @Test
    fun turnEasingHitsEndpointsExactly() {
        assertEquals(0f, TurnEasing.transform(0f), 0f)
        assertEquals(1f, TurnEasing.transform(1f), 0f)
        assertEquals(0f, TurnEasing.transform(-1f), 0f)
        assertEquals(1f, TurnEasing.transform(2f), 0f)
        assertTrue(TurnEasing.transform(0.5f) in 0.6f..0.95f)
    }

    @Test
    fun wrapDegreesStaysInRange() {
        assertEquals(-170f, wrapDegrees(190f), 1e-4f)
        assertEquals(170f, wrapDegrees(-190f), 1e-4f)
        assertEquals(-180f, wrapDegrees(180f), 1e-4f)
        assertEquals(10f, wrapDegrees(370f + 720f), 1e-3f)
    }

    /**
     * Whether some plane perpendicular to this axis separates the two cubies (coordinates [a] and
     * [b]) with the eye on [b]'s side.
     */
    private fun eyeOnSideOf(a: Int, b: Int, eye: Float): Boolean = when {
        b > a -> eye > a + 0.5f
        b < a -> eye < a - 0.5f
        else -> false
    }

    private fun position(c: Int) = IntArray(3) { CubeGeometry.cubiePosition[c * 3 + it] }

    private fun assertLandsOn(expected: IntArray, actual: FloatArray, what: String) {
        for (k in 0 until 3) {
            assertEquals(what, expected[k], actual[k].roundToInt())
            assertTrue("$what lands exactly", abs(actual[k] - expected[k]) < 1e-5f)
        }
    }
}
