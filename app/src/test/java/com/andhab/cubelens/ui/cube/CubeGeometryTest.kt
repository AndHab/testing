package com.andhab.cubelens.ui.cube

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.toLayerMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure-math tests of the renderer geometry (plain JVM, no Android). */
class CubeGeometryTest {

    /**
     * For every size and every kind of turn (outer, wide, inner slice, ranges, whole-cube rotations;
     * all faces, all amounts), rotating each turning sticker's position and normal by the full
     * animation angle lands exactly on the sticker the committed permutation moves its color to,
     * so the last frame of an animation and the committed colors are pixel-identical.
     */
    @Test
    fun finishedTurnLandsExactlyOnTheCommittedStateForEverySizeAndLayerMove() {
        val r = FloatArray(9)
        val out = FloatArray(3)
        for (n in 2..7) {
            val g = NxNGeometry.of(n)
            val moves = allLayerMoves(n)
            // Sanity: every kind of turn is covered.
            assertTrue(moves.any { it.isOuter })
            assertTrue(n < 3 || moves.any { it.fromDepth == 1 && it.toDepth in 2 until n })
            assertTrue(n < 3 || moves.any { it.fromDepth == it.toDepth && it.fromDepth in 2 until n })
            assertTrue(n < 4 || moves.any { it.fromDepth > 1 && it.toDepth > it.fromDepth && it.toDepth < n })
            assertTrue(moves.any { it.fromDepth == 1 && it.toDepth == n })
            for (move in moves) {
                val axis = move.face.normal
                CubeGeometry.rotation(axis.x, axis.y, axis.z, turnAngleDegrees(move, 1f), r)
                val perm = g.permutation(move)
                val destination = IntArray(g.stickerCount).also { d -> for (j in perm.indices) d[perm[j]] = j }
                for (i in 0 until g.stickerCount) {
                    val j = destination[i]
                    if (!g.isMovedBy(i, move)) {
                        assertEquals("$n×$n $move keeps sticker $i", i, j)
                        continue
                    }
                    val p = g.position[i]
                    val q = g.normal[i]
                    CubeGeometry.transform(r, p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), out)
                    assertLandsOn(g.position[j].let { intArrayOf(it.x, it.y, it.z) }, out, "$n×$n $move position of $i")
                    CubeGeometry.transform(r, q.x.toFloat(), q.y.toFloat(), q.z.toFloat(), out)
                    assertLandsOn(g.normal[j].let { intArrayOf(it.x, it.y, it.z) }, out, "$n×$n $move normal of $i")
                }
            }
        }
    }

    @Test
    fun outerLayerMovesAnimateLikeTheirThreeByThreeMoves() {
        for (move in Move.entries) {
            for (progress in listOf(0f, 0.3f, 0.5f, 1f)) {
                assertEquals(turnAngleDegrees(move, progress), turnAngleDegrees(move.toLayerMove(), progress), 0f)
            }
            assertEquals(move.signedQuarterTurns, move.toLayerMove().signedQuarterTurns)
        }
    }

    @Test
    fun sceneTurnsExactlyTheLayersTheModelMoves() {
        val scene = CubeScene()
        for (n in 2..7) {
            val lattice = CubeLattice.of(n)
            val g = lattice.geometry
            for (move in allLayerMoves(n)) {
                scene.update(lattice, yaw = 10f, pitch = 20f, move = move, progress = 0.5f)
                val axis = scene.axis
                assertEquals(CubeGeometry.axisOf(move.face), axis)
                for (i in 0 until g.stickerCount) {
                    val p = g.position[i]
                    val layer = (intArrayOf(p.x, p.y, p.z)[axis] + n - 1) / 2
                    val inTurningGroup = (0 until scene.groupCount).any { k ->
                        scene.isTurning(k) && layer in scene.lower(k, axis)..scene.upper(k, axis)
                    }
                    assertEquals("$n×$n $move sticker $i", g.isMovedBy(i, move), inTurningGroup)
                }
            }
        }
    }

    @Test
    fun latticeStickerTableMatchesTheModel() {
        for (n in 2..7) {
            val lattice = CubeLattice.of(n)
            val g = lattice.geometry
            var stickers = 0
            for (x in 0 until n) for (y in 0 until n) for (z in 0 until n) for (d in 0 until 6) {
                val i = lattice.sticker(x, y, z, d)
                if (i < 0) continue
                stickers++
                assertEquals(Face.entries[d], g.faceOf(i))
                val p = g.position[i]
                assertEquals(listOf(2 * x - (n - 1), 2 * y - (n - 1), 2 * z - (n - 1)), listOf(p.x, p.y, p.z))
            }
            assertEquals(g.stickerCount, stickers)
            assertEquals(2f * CubeGeometry.HALF_EXTENT, n * lattice.cell, 1e-5f)
        }
        // A 3×3 lattice has unit cubies at integer centers, like the facelet model.
        val three = CubeLattice.of(3)
        for (i in 0 until Facelets.COUNT) {
            val p = Facelets.position[i]
            assertEquals(i, three.sticker(p.x + 1, p.y + 1, p.z + 1, Facelets.faceOf(i).ordinal))
        }
    }

    @Test
    fun layerAtDepthCountsFromTheMovingFace() {
        val lattice = CubeLattice.of(5)
        assertEquals(4, lattice.layerAtDepth(Face.R, 1))
        assertEquals(0, lattice.layerAtDepth(Face.L, 1))
        assertEquals(3, lattice.layerAtDepth(Face.U, 2))
        assertEquals(1, lattice.layerAtDepth(Face.B, 2))
        assertEquals(0, lattice.layerAtDepth(Face.F, 5))
    }

    @Test
    fun primeMovesTurnBackwardsAndHalfTurnsTwice() {
        assertEquals(-90f, turnAngleDegrees(Move.U1, 1f), 0f)
        assertEquals(-180f, turnAngleDegrees(Move.U2, 1f), 0f)
        assertEquals(90f, turnAngleDegrees(Move.U3, 1f), 0f)
        assertEquals(90f, turnAngleDegrees(LayerMove.parse("3Uw'"), 1f), 0f)
        assertEquals(-180f, turnAngleDegrees(LayerMove.parse("2R2"), 1f), 0f)
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
            assertEquals(Face.entries[d].normal.let { listOf(it.x, it.y, it.z) }, IntArray(3) { CubeGeometry.faceNormal[d * 3 + it] }.toList())
            assertEquals(CubeGeometry.axisOf(Face.entries[d]), CubeGeometry.axisOfVector(CubeGeometry.faceNormal, d))
            assertEquals(CubeGeometry.signOf(Face.entries[d]), CubeGeometry.signOfVector(CubeGeometry.faceNormal, d))
        }
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

    @Test
    fun cubeSizeIsInferredFromStickerCounts() {
        for (n in 2..10) {
            assertEquals(n, CubeSizes.ofCube(6 * n * n))
            assertEquals(n, CubeSizes.ofFace(n * n))
        }
        for (bad in listOf(0, 1, 6, 9, 53, 55, 6 * 11 * 11, -54)) {
            assertThrows("cube of $bad") { CubeSizes.ofCube(bad) }
        }
        for (bad in listOf(0, 1, 8, 10, 54, 121)) {
            assertThrows("face of $bad") { CubeSizes.ofFace(bad) }
        }
    }

    private fun assertThrows(what: String, block: () -> Unit) {
        val thrown = runCatching(block).exceptionOrNull()
        assertTrue("$what should be rejected", thrown is IllegalArgumentException)
    }

    private fun assertLandsOn(expected: IntArray, actual: FloatArray, what: String) {
        for (k in 0 until 3) {
            assertEquals(what, expected[k], actual[k].roundToInt())
            assertTrue("$what lands exactly", abs(actual[k] - expected[k]) < 1e-4f)
        }
    }

    companion object {
        /** Every layer range of every face of an [n]×[n] cube, with every amount of turn. */
        fun allLayerMoves(n: Int): List<LayerMove> = buildList {
            for (face in Face.entries) for (from in 1..n) for (to in from..n) for (turns in 1..3) {
                add(LayerMove(face, from, to, turns))
            }
        }
    }
}
