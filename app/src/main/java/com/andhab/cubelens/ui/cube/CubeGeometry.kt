package com.andhab.cubelens.ui.cube

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import kotlin.math.cos
import kotlin.math.sin

/**
 * Yaw and pitch (degrees, for [CubeViewState.yaw] / [CubeViewState.pitch]) that present [face]
 * prominently while keeping a three-quarter, clearly 3D look.
 *
 * The presets follow the scanning orientation (white on top, green in front):
 *  - F, R, B, L: the face looks at the viewer, turned slightly to the left so the next face of the
 *    F → R → B → L sequence peeks in on the right, with a little of U visible on top. Consecutive
 *    side faces are exactly 90 degrees of yaw apart, so animating between them reads as
 *    "turn the cube".
 *  - U: tilted towards the viewer with F at the bottom of the image (B at the top).
 *  - D: tilted towards the viewer with F at the top of the image (B at the bottom).
 */
fun viewAnglesFor(face: Face): Pair<Float, Float> = when (face) {
    Face.F -> PRESET_YAW to PRESET_SIDE_PITCH
    Face.R -> wrapDegrees(PRESET_YAW - 90f) to PRESET_SIDE_PITCH
    Face.B -> wrapDegrees(PRESET_YAW - 180f) to PRESET_SIDE_PITCH
    Face.L -> wrapDegrees(PRESET_YAW - 270f) to PRESET_SIDE_PITCH
    Face.U -> PRESET_YAW to PRESET_CAP_PITCH
    Face.D -> PRESET_YAW to -PRESET_CAP_PITCH
}

private const val PRESET_YAW = -24f
private const val PRESET_SIDE_PITCH = 20f
private const val PRESET_CAP_PITCH = 60f

/** Wraps an angle in degrees into [-180, 180). */
internal fun wrapDegrees(degrees: Float): Float {
    val r = (degrees + 180f) % 360f
    return (if (r < 0f) r + 360f else r) - 180f
}

/**
 * Easing of a layer turn: a snappy fast-out-slow-in curve (cubic Bezier 0.35, 0, 0.15, 1).
 * Exactly 0 at 0 and exactly 1 at 1, so a finished animation lands precisely on the quarter turn.
 */
internal object TurnEasing {
    private const val X1 = 0.35f
    private const val X2 = 0.15f
    private const val Y1 = 0f
    private const val Y2 = 1f

    fun transform(fraction: Float): Float {
        if (fraction <= 0f) return 0f
        if (fraction >= 1f) return 1f
        // Solve x(t) = fraction by bisection (x(t) is monotonic for these control points).
        var lo = 0f
        var hi = 1f
        var t = fraction
        repeat(24) {
            t = (lo + hi) * 0.5f
            if (bezier(t, X1, X2) < fraction) lo = t else hi = t
        }
        return bezier(t, Y1, Y2)
    }

    private fun bezier(t: Float, p1: Float, p2: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
    }
}

/**
 * Signed number of clockwise quarter turns as animated: +1 for `X`, +2 for `X2`, -1 for `X'`
 * (so a prime move turns a quarter counter-clockwise rather than three quarters clockwise).
 */
internal val Move.signedQuarterTurns: Int
    get() = if (turns == 3) -1 else turns

/**
 * Rotation in degrees (right-handed, about `move.face.normal`) of the turning layer at linear
 * time fraction [progress]. Clockwise as seen from outside the face is negative.
 */
internal fun turnAngleDegrees(move: Move, progress: Float): Float =
    -90f * move.signedQuarterTurns * TurnEasing.transform(progress)

/**
 * Static cube geometry plus the small amount of math the renderer needs each frame. Everything
 * works on preallocated [FloatArray]s so a frame does not allocate.
 *
 * Coordinates match [Facelets]: x right (R), y up (U), z towards the viewer (F). Cubie centers are
 * at integer coordinates in -1..1 and each cubie is a unit cube.
 */
internal object CubeGeometry {
    const val CUBIE_COUNT = 27

    /** Distance from the cube center to the camera, in cubie units. */
    const val CAMERA_DISTANCE = 10.5f

    /** Radius of the sphere that contains the cube in any orientation and mid-turn. */
    const val BOUNDING_RADIUS = 2.6f

    /** Cubie index for integer coordinates in -1..1. */
    fun cubieIndex(x: Int, y: Int, z: Int): Int = (x + 1) * 9 + (y + 1) * 3 + (z + 1)

    /** Cubie positions, flattened as x, y, z per [cubieIndex]. */
    val cubiePosition: IntArray = IntArray(CUBIE_COUNT * 3).also { p ->
        for (x in -1..1) for (y in -1..1) for (z in -1..1) {
            val c = cubieIndex(x, y, z)
            p[c * 3] = x
            p[c * 3 + 1] = y
            p[c * 3 + 2] = z
        }
    }

    /** Outward normal of each of the six cubie faces, in [Face] order, flattened xyz. */
    val faceNormal: IntArray = IntArray(18).also { n ->
        Face.entries.forEach { f ->
            n[f.ordinal * 3] = f.normal.x
            n[f.ordinal * 3 + 1] = f.normal.y
            n[f.ordinal * 3 + 2] = f.normal.z
        }
    }

    /**
     * In-plane axes (u, v) of each face with u x v = normal. The renderer maps a face's local square
     * (0..S along u, 0..S along v) onto its projected quad.
     */
    val faceU: IntArray = intArrayOf(
        1, 0, 0, // U
        0, 0, -1, // R
        1, 0, 0, // F
        1, 0, 0, // D
        0, 0, 1, // L
        -1, 0, 0, // B
    )

    /** See [faceU]. */
    val faceV: IntArray = intArrayOf(
        0, 0, -1, // U
        0, 1, 0, // R
        0, 1, 0, // F
        0, 0, 1, // D
        0, 1, 0, // L
        0, 1, 0, // B
    )

    /** Facelet index shown on cubie face `cubie * 6 + face`, or -1 for an inner (sticker-less) face. */
    val sticker: IntArray = IntArray(CUBIE_COUNT * 6) { -1 }.also { s ->
        for (i in 0 until Facelets.COUNT) {
            val p = Facelets.position[i]
            s[cubieIndex(p.x, p.y, p.z) * 6 + Facelets.faceOf(i).ordinal] = i
        }
    }

    /** Whether integer coordinates lie inside the 3x3x3 grid. */
    fun inGrid(x: Int, y: Int, z: Int): Boolean = x in -1..1 && y in -1..1 && z in -1..1

    /** Axis index (0 = x, 1 = y, 2 = z) of a face normal. */
    fun axisOf(face: Face): Int = when (face) {
        Face.R, Face.L -> 0
        Face.U, Face.D -> 1
        Face.F, Face.B -> 2
    }

    /** Which slab along [axisOf] the outer layer of [face] occupies: +1 or -1. */
    fun slabOf(face: Face): Int = with(face.normal) { x + y + z }

    /**
     * Writes the 3x3 row-major matrix of a right-handed rotation by [degrees] about the integer unit
     * [axis] (Rodrigues' formula) into [out].
     */
    fun rotation(axisX: Int, axisY: Int, axisZ: Int, degrees: Float, out: FloatArray) {
        val rad = Math.toRadians(degrees.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val t = 1f - c
        val x = axisX.toFloat()
        val y = axisY.toFloat()
        val z = axisZ.toFloat()
        out[0] = c + t * x * x; out[1] = t * x * y - s * z; out[2] = t * x * z + s * y
        out[3] = t * x * y + s * z; out[4] = c + t * y * y; out[5] = t * y * z - s * x
        out[6] = t * x * z - s * y; out[7] = t * y * z + s * x; out[8] = c + t * z * z
    }

    /** Writes the identity matrix into [out]. */
    fun identity(out: FloatArray) {
        out.fill(0f)
        out[0] = 1f; out[4] = 1f; out[8] = 1f
    }

    /**
     * Writes the view rotation `Rx(pitch) * Ry(yaw)` into [out]: cube coordinates to camera
     * coordinates (x right, y up, z towards the camera). Positive yaw swings the front face to the
     * right, positive pitch tips the top face towards the viewer.
     */
    fun viewRotation(yawDegrees: Float, pitchDegrees: Float, out: FloatArray) {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val pitch = Math.toRadians(pitchDegrees.toDouble())
        val cy = cos(yaw).toFloat()
        val sy = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()
        out[0] = cy; out[1] = 0f; out[2] = sy
        out[3] = sp * sy; out[4] = cp; out[5] = -sp * cy
        out[6] = -cp * sy; out[7] = sp; out[8] = cp * cy
    }

    /** The camera position in cube coordinates for a view rotation [view] (transpose times (0, 0, D)). */
    fun eyePosition(view: FloatArray, out: FloatArray) {
        out[0] = view[6] * CAMERA_DISTANCE
        out[1] = view[7] * CAMERA_DISTANCE
        out[2] = view[8] * CAMERA_DISTANCE
    }

    /**
     * Back-to-front order of the three slabs at coordinates -1, 0, 1 along one axis, for an eye whose
     * coordinate along that axis is [eye]. Slabs are separated by the planes at -0.5 and 0.5, so a
     * slab farther from the eye (by distance to its center line) can never cover a nearer one.
     */
    fun slabOrder(eye: Float, out: IntArray) {
        when {
            eye > 0.5f -> { out[0] = -1; out[1] = 0; out[2] = 1 }
            eye < -0.5f -> { out[0] = 1; out[1] = 0; out[2] = -1 }
            // The eye is between the two planes: the outer slabs cannot overlap each other on screen,
            // and the middle slab is in front of both.
            eye >= 0f -> { out[0] = -1; out[1] = 1; out[2] = 0 }
            else -> { out[0] = 1; out[1] = -1; out[2] = 0 }
        }
    }

    /**
     * Painter's order (back to front) of all 27 cubies.
     *
     * The three slabs along [axis] are ordered first (the turning layer stays inside its slab because
     * it rotates about that axis). Inside a slab the cubies form a rigid 3x3 grid, so rows and then
     * cubies are ordered the same way using the eye position expressed in the slab's own frame: for
     * the turning slab ([turningSlab], or 0 if nothing turns) that frame is rotated by
     * [layerRotation]. Every step is separated by a plane, which makes the order exact.
     *
     * @param eye camera position in cube coordinates (3 floats).
     * @param axis turning axis (0 = x, 1 = y, 2 = z); any axis when nothing turns.
     * @param out receives the 27 cubie indices, back to front.
     * @param scratch reusable working memory, so ordering a frame allocates nothing.
     */
    fun cubieDrawOrder(
        eye: FloatArray,
        axis: Int,
        turningSlab: Int,
        layerRotation: FloatArray,
        out: IntArray,
        scratch: OrderScratch,
    ) {
        val b = (axis + 1) % 3
        val c = (axis + 2) % 3
        slabOrder(eye[axis], scratch.slabs)
        var n = 0
        for (si in 0 until 3) {
            val slab = scratch.slabs[si]
            val local = scratch.localEye
            if (turningSlab != 0 && slab == turningSlab) {
                // Eye in the rotated slab's frame: R^T * eye.
                val r = layerRotation
                local[0] = r[0] * eye[0] + r[3] * eye[1] + r[6] * eye[2]
                local[1] = r[1] * eye[0] + r[4] * eye[1] + r[7] * eye[2]
                local[2] = r[2] * eye[0] + r[5] * eye[1] + r[8] * eye[2]
            } else {
                local[0] = eye[0]; local[1] = eye[1]; local[2] = eye[2]
            }
            slabOrder(local[b], scratch.rows)
            slabOrder(local[c], scratch.cols)
            for (ri in 0 until 3) for (ci in 0 until 3) {
                val coord = scratch.coord
                coord[axis] = slab
                coord[b] = scratch.rows[ri]
                coord[c] = scratch.cols[ci]
                out[n++] = cubieIndex(coord[0], coord[1], coord[2])
            }
        }
    }

    /** Reusable working memory for [cubieDrawOrder]. */
    class OrderScratch {
        val slabs = IntArray(3)
        val rows = IntArray(3)
        val cols = IntArray(3)
        val coord = IntArray(3)
        val localEye = FloatArray(3)
    }

    /** Multiplies the 3x3 row-major matrix [m] with (x, y, z) and writes the result at [out][offset]. */
    fun transform(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, offset: Int = 0) {
        out[offset] = m[0] * x + m[1] * y + m[2] * z
        out[offset + 1] = m[3] * x + m[4] * y + m[5] * z
        out[offset + 2] = m[6] * x + m[7] * y + m[8] * z
    }

    /** Multiplies the transpose of [m] with (x, y, z) and writes the result at [out][offset]. */
    fun transformTransposed(m: FloatArray, x: Float, y: Float, z: Float, out: FloatArray, offset: Int = 0) {
        out[offset] = m[0] * x + m[3] * y + m[6] * z
        out[offset + 1] = m[1] * x + m[4] * y + m[7] * z
        out[offset + 2] = m[2] * x + m[5] * y + m[8] * z
    }
}
