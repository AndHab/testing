package com.andhab.cubelens.ui.cube

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** The painter's algorithm of [CubeScene]: slab-group order and exact visibility (plain JVM). */
class CubeSceneTest {

    @Test
    fun backToFrontPaintsEverySlabBeforeTheSlabsThatCanCoverIt() {
        val random = Random(7)
        val out = IntArray(3)
        repeat(4000) {
            // Up to three adjacent slabs with random boundaries, eye anywhere around them.
            val count = 1 + random.nextInt(3)
            val bounds = FloatArray(count + 1)
            bounds[0] = -1.5f
            for (k in 1..count) bounds[k] = bounds[k - 1] + 0.05f + random.nextFloat()
            val starts = FloatArray(3) { if (it < count) bounds[it] else 0f }
            val ends = FloatArray(3) { if (it < count) bounds[it + 1] else 0f }
            val eye = -4f + random.nextFloat() * 8f
            CubeGeometry.backToFront(count, starts, ends, eye, out)
            assertEquals((0 until count).toSet(), out.take(count).toSet())
            for (i in 0 until count) for (j in i + 1 until count) {
                val a = out[i]
                val b = out[j]
                assertTrue("eye $eye: slab $a painted before $b", mayPaintBefore(a, b, starts, ends, eye))
            }
        }
    }

    @Test
    fun groupsSplitTheCubeAtTheTurningLayers() {
        val scene = CubeScene()
        val lattice = CubeLattice.of(7)
        scene.update(lattice, 0f, 0f, null, 0f)
        assertEquals(1, scene.groupCount)
        assertEquals(0, scene.lower(0, scene.axis))
        assertEquals(6, scene.upper(0, scene.axis))

        // "3Uw'" turns the top three layers: y layers 4..6; the rest (0..3) stays.
        scene.update(lattice, 0f, 0f, LayerMove.parse("3Uw'"), 0.4f)
        assertEquals(1, scene.axis)
        assertEquals(2, scene.groupCount)
        val ranges = (0 until 2).map { Triple(scene.lower(it, 1), scene.upper(it, 1), scene.isTurning(it)) }.toSet()
        assertEquals(setOf(Triple(0, 3, false), Triple(4, 6, true)), ranges)

        // An inner slice "2R" on a 5×5 makes three groups: x layers 0..2, 3 (turning) and 4.
        scene.update(CubeLattice.of(5), 0f, 0f, LayerMove.parse("2R"), 0.5f)
        assertEquals(3, scene.groupCount)
        val slices = (0 until 3).map { Triple(scene.lower(it, 0), scene.upper(it, 0), scene.isTurning(it)) }.toSet()
        assertEquals(setOf(Triple(0, 2, false), Triple(3, 3, true), Triple(4, 4, false)), slices)

        // A whole-cube rotation turns everything as one group.
        scene.update(CubeLattice.of(4), 0f, 0f, LayerMove.parse("4Fw"), 0.5f)
        assertEquals(1, scene.groupCount)
        assertTrue(scene.isTurning(0))
    }

    @Test
    fun turnsReportHowFarTheyAreFromARestPose() {
        val scene = CubeScene()
        val lattice = CubeLattice.of(3)
        scene.update(lattice, -35f, 28f, null, 0f)
        assertEquals(0f, scene.degreesFromRest, 0f)
        assertEquals(0f, scene.turnFraction, 0f)
        for (move in listOf("R", "U2", "3Rw", "2-3Fw'", "3Uw2")) {
            val parsed = LayerMove.parse(move)
            scene.update(lattice, -35f, 28f, parsed, 0f)
            assertEquals("$move at the start", 0f, scene.degreesFromRest, 1e-4f)
            assertEquals("$move done at the start", 0f, scene.turnFraction, 1e-4f)
            scene.update(lattice, -35f, 28f, parsed, 1f)
            assertEquals("$move at the end", 0f, scene.degreesFromRest, 1e-3f)
            assertEquals("$move done at the end", 1f, scene.turnFraction, 1e-4f)
            assertEquals("$move ends on its quarter turns", 90f * abs(parsed.signedQuarterTurns), abs(scene.turnTarget), 1e-3f)
            for (progress in listOf(0.1f, 0.3f, 0.5f, 0.8f)) {
                scene.update(lattice, -35f, 28f, parsed, progress)
                val angle = turnAngleDegrees(parsed, progress)
                val expected = minOf(abs(angle), abs(scene.turnTarget - angle))
                assertEquals("$move at $progress", expected, scene.degreesFromRest, 1e-3f)
            }
            scene.update(lattice, -35f, 28f, parsed, 0.3f)
            assertTrue("$move is well away from rest at 0.3: ${scene.degreesFromRest}", scene.degreesFromRest > 20f)
        }
    }

    @Test
    fun wholeCubeRotationsLandTheRightFaceInEachSlot() {
        val scene = CubeScene()
        val lattice = CubeLattice.of(3)
        scene.update(lattice, 0f, 0f, null, 0f)
        for (face in Face.entries) assertEquals(face.ordinal, scene.faceLandingOn(face.ordinal))

        // x (= 3Rw on a 3×3): F goes up, so D arrives at F, F at U, U at B and B at D; R and L stay.
        scene.update(lattice, 0f, 0f, LayerMove.parse("3Rw"), 0.3f)
        assertEquals(-90f, scene.turnTarget, 1e-3f)
        val x = mapOf(Face.F to Face.D, Face.U to Face.F, Face.B to Face.U, Face.D to Face.B, Face.R to Face.R, Face.L to Face.L)
        for ((slot, arriving) in x) assertEquals("x: into $slot", arriving.ordinal, scene.faceLandingOn(slot.ordinal))

        for (face in Face.entries) assertEquals(face.ordinal, scene.faceCarriedTo(scene.faceLandingOn(face.ordinal)))
        assertEquals(Face.U.ordinal, scene.faceCarriedTo(Face.F.ordinal))

        // y' (= 4Uw' on a 4×4): F goes right, so L arrives at F and F at R.
        scene.update(CubeLattice.of(4), 0f, 0f, LayerMove.parse("4Uw'"), 0.7f)
        assertEquals(Face.L.ordinal, scene.faceLandingOn(Face.F.ordinal))
        assertEquals(Face.F.ordinal, scene.faceLandingOn(Face.R.ordinal))

        // z2 lands the opposite face.
        scene.update(lattice, 0f, 0f, LayerMove.parse("3Fw2"), 0.5f)
        assertEquals(Face.D.ordinal, scene.faceLandingOn(Face.U.ordinal))
        assertEquals(Face.L.ordinal, scene.faceLandingOn(Face.R.ordinal))
    }

    /**
     * Ray-casts random pixels of random frames (sizes 2..7, random camera, any layer move at any
     * progress) and checks that the face the painter's algorithm paints last at each pixel is the
     * face actually nearest to the camera there. This verifies group order, box-face visibility and
     * the claim that a group's visible box faces never overlap, all at once.
     */
    @Test
    fun painterResultMatchesRayCastingEverywhere() {
        val random = Random(20261001)
        val scene = CubeScene()
        val corners = FloatArray(12)
        var checkedPixels = 0
        repeat(700) { iteration ->
            val n = 2 + iteration % 6
            val lattice = CubeLattice.of(n)
            val yaw = random.nextFloat() * 360f - 180f
            val pitch = random.nextFloat() * 160f - 80f
            val move = if (iteration % 7 == 0) null else randomLayerMove(n, random)
            val progress = random.nextFloat()
            scene.update(lattice, yaw, pitch, move, progress)

            // Every box face of every group, in camera space; painted ones in painting order.
            val painted = mutableListOf<Quad>()
            val all = mutableListOf<Quad>()
            for (i in 0 until scene.groupCount) {
                val g = scene.groupInDrawOrder(i)
                for (d in 0 until 6) {
                    for (k in 0 until 4) scene.faceCorner(g, d, k, corners, k * 3)
                    val quad = Quad.of(corners, scene.view, "group $g face ${Face.entries[d]}")
                    all += quad
                    if (scene.isFaceVisible(g, d)) painted += quad
                }
            }
            repeat(150) {
                val sx = (random.nextFloat() - 0.5f) * 0.6f
                val sy = (random.nextFloat() - 0.5f) * 0.6f
                // Skip pixels right on an edge, where coverage is a matter of anti-aliasing.
                if (all.any { it.nearEdge(sx, sy) }) return@repeat
                val top = painted.lastOrNull { it.contains(sx, sy) }
                val nearest = all.filter { it.contains(sx, sy) }.minByOrNull { it.depthAt(sx, sy) }
                if (nearest == null) {
                    assertEquals("iteration $iteration: background pixel painted", null, top)
                    return@repeat
                }
                checkedPixels++
                val context = "iteration $iteration (${n}x$n, yaw $yaw, pitch $pitch, $move @ $progress) at ($sx, $sy)"
                assertTrue("$context: nothing painted, ${nearest.name} is visible", top != null)
                val topDepth = top!!.depthAt(sx, sy)
                val nearestDepth = nearest.depthAt(sx, sy)
                assertTrue(
                    "$context: painted ${top.name} at $topDepth but ${nearest.name} is nearer at $nearestDepth",
                    topDepth <= nearestDepth + 1e-3f * max(1f, nearestDepth),
                )
            }
        }
        assertTrue("enough pixels hit the cube ($checkedPixels)", checkedPixels > 20_000)
    }

    @Test
    fun orderingAFrameDoesNotAllocate() {
        val scene = CubeScene()
        val lattice = CubeLattice.of(7)
        val moves = listOf(LayerMove.parse("3Uw'"), LayerMove.parse("4R"), null)
        val corner = FloatArray(3)
        var sink = 0f
        fun frame(k: Int) {
            scene.update(lattice, k * 0.7f, 20f, moves[k % 3], (k % 50) / 50f)
            for (i in 0 until scene.groupCount) {
                val g = scene.groupInDrawOrder(i)
                for (d in 0 until 6) {
                    if (!scene.isFaceVisible(g, d)) continue
                    for (c in 0 until 4) scene.faceCorner(g, d, c, corner)
                    sink += corner[0]
                }
            }
        }
        repeat(2_000) { frame(it) }
        val bytes = allocatedBytes { repeat(2_000) { frame(it) } }
        assertTrue("2000 frames allocated $bytes bytes ($sink)", bytes < 4_096)
    }

    /**
     * Whether painting slab [a] before slab [b] is safe: some plane between them has the eye on
     * [b]'s side (or contains the eye, and then the two never overlap on screen). That holds exactly
     * when the eye is beyond [a]'s boundary that faces [b].
     */
    private fun mayPaintBefore(a: Int, b: Int, starts: FloatArray, ends: FloatArray, eye: Float): Boolean =
        if (b > a) eye >= ends[a] else eye <= starts[a]

    private fun randomLayerMove(n: Int, random: Random): LayerMove {
        val from = 1 + random.nextInt(n)
        val to = from + random.nextInt(n - from + 1)
        return LayerMove(Face.entries[random.nextInt(6)], from, to, 1 + random.nextInt(3))
    }

    /** A planar convex quad in camera space and its projection (focal length 1). */
    private class Quad(
        val name: String,
        private val xs: FloatArray,
        private val ys: FloatArray,
        private val nx: Float,
        private val ny: Float,
        private val nz: Float,
        private val c: Float,
    ) {
        /** Distance along the ray through screen point (sx, sy) to the quad's plane. */
        fun depthAt(sx: Float, sy: Float): Float {
            val d = CubeGeometry.CAMERA_DISTANCE
            return (c - nz * d) / (nx * sx + ny * sy - nz)
        }

        fun contains(sx: Float, sy: Float): Boolean {
            var sign = 0
            for (k in 0 until 4) {
                val cross = edgeCross(k, sx, sy)
                val s = if (cross > 0f) 1 else if (cross < 0f) -1 else 0
                if (s == 0) continue
                if (sign == 0) sign = s else if (s != sign) return false
            }
            return true
        }

        fun nearEdge(sx: Float, sy: Float): Boolean {
            for (k in 0 until 4) {
                val x0 = xs[k]
                val y0 = ys[k]
                val x1 = xs[(k + 1) % 4]
                val y1 = ys[(k + 1) % 4]
                val len = kotlin.math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
                if (len < 1e-6f) continue
                val t = ((sx - x0) * (x1 - x0) + (sy - y0) * (y1 - y0)) / (len * len)
                if (t < -0.01f || t > 1.01f) continue
                if (abs(edgeCross(k, sx, sy)) / len < EDGE_MARGIN) return true
            }
            return false
        }

        private fun edgeCross(k: Int, sx: Float, sy: Float): Float {
            val x0 = xs[k]
            val y0 = ys[k]
            val x1 = xs[(k + 1) % 4]
            val y1 = ys[(k + 1) % 4]
            return (x1 - x0) * (sy - y0) - (y1 - y0) * (sx - x0)
        }

        companion object {
            const val EDGE_MARGIN = 0.002f

            /** From four world-space corners (xyz each) and the view rotation. */
            fun of(world: FloatArray, view: FloatArray, name: String): Quad {
                val cam = FloatArray(12)
                for (k in 0 until 4) CubeGeometry.transform(view, world[k * 3], world[k * 3 + 1], world[k * 3 + 2], cam, k * 3)
                val d = CubeGeometry.CAMERA_DISTANCE
                val xs = FloatArray(4) { cam[it * 3] / (d - cam[it * 3 + 2]) }
                val ys = FloatArray(4) { cam[it * 3 + 1] / (d - cam[it * 3 + 2]) }
                val ax = cam[3] - cam[0]
                val ay = cam[4] - cam[1]
                val az = cam[5] - cam[2]
                val bx = cam[9] - cam[0]
                val by = cam[10] - cam[1]
                val bz = cam[11] - cam[2]
                val nx = ay * bz - az * by
                val ny = az * bx - ax * bz
                val nz = ax * by - ay * bx
                val c = nx * cam[0] + ny * cam[1] + nz * cam[2]
                return Quad(name, xs, ys, nx, ny, nz, c)
            }
        }
    }
}
