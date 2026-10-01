package com.andhab.cubelens.ui.cube

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.LayerMove
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

/** [Move.signedQuarterTurns] for a turn of any layers. */
internal val LayerMove.signedQuarterTurns: Int
    get() = if (turns == 3) -1 else turns

/**
 * Rotation in degrees (right-handed, about `move.face.normal`) of the turning layer at linear
 * time fraction [progress]. Clockwise as seen from outside the face is negative.
 */
internal fun turnAngleDegrees(move: Move, progress: Float): Float =
    -90f * move.signedQuarterTurns * TurnEasing.transform(progress)

/** [turnAngleDegrees] for a turn of any layers: they all rotate about `move.face.normal`. */
internal fun turnAngleDegrees(move: LayerMove, progress: Float): Float =
    -90f * move.signedQuarterTurns * TurnEasing.transform(progress)

/**
 * Size-independent camera and rotation math of the renderer. Everything works on preallocated
 * [FloatArray]s so a frame does not allocate.
 *
 * World coordinates match [com.andhab.cubelens.core.nxn.NxNGeometry]'s axes: x right (R), y up (U),
 * z towards the viewer (F). Whatever its size, the cube spans [-HALF_EXTENT, HALF_EXTENT] on every
 * axis (a 3×3 has unit cubies), so the camera and framing are the same for every size.
 */
internal object CubeGeometry {
    /** Half the edge length of the whole cube, in world units. */
    const val HALF_EXTENT = 1.5f

    /** Distance from the cube center to the camera, in world units. */
    const val CAMERA_DISTANCE = 10.5f

    /** Radius of the sphere that contains the cube in any orientation and mid-turn. */
    const val BOUNDING_RADIUS = 2.6f

    /** Outward normal of each of the six faces, in [Face] order, flattened xyz. */
    val faceNormal: IntArray = IntArray(18).also { n ->
        Face.entries.forEach { f ->
            n[f.ordinal * 3] = f.normal.x
            n[f.ordinal * 3 + 1] = f.normal.y
            n[f.ordinal * 3 + 2] = f.normal.z
        }
    }

    /**
     * In-plane axes (u, v) of each face with u x v = normal. The renderer maps a face's local
     * rectangle (x along u, y along v) onto its projected quad.
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

    /** Axis index (0 = x, 1 = y, 2 = z) of a face normal. */
    fun axisOf(face: Face): Int = when (face) {
        Face.R, Face.L -> 0
        Face.U, Face.D -> 1
        Face.F, Face.B -> 2
    }

    /** Sign (+1 or -1) of [face]'s normal along its [axisOf]. Allocation-free (unlike [Face.normal]). */
    fun signOf(face: Face): Int = signOfVector(faceNormal, face.ordinal)

    /** Axis index of the unit vector stored at [vectors]`[d * 3]` (e.g. [faceU] or [faceV] of face d). */
    fun axisOfVector(vectors: IntArray, d: Int): Int = when {
        vectors[d * 3] != 0 -> 0
        vectors[d * 3 + 1] != 0 -> 1
        else -> 2
    }

    /** Sign of the unit vector stored at [vectors]`[d * 3]` along its axis. */
    fun signOfVector(vectors: IntArray, d: Int): Int = vectors[d * 3] + vectors[d * 3 + 1] + vectors[d * 3 + 2]

    /**
     * Writes the 3x3 row-major matrix of a right-handed rotation by [degrees] about the integer unit
     * axis into [out] (Rodrigues' formula).
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

    /**
     * Back-to-front painting order of [count] slabs stacked along one axis.
     *
     * Slab `i` spans `[starts[i], ends[i]]` along the axis; slabs are sorted and do not overlap
     * (`ends[i] <= starts[i + 1]`), so any two are separated by a plane perpendicular to the axis.
     * [eye] is the eye's coordinate on that axis. A slab is painted only once every slab it could
     * cover is already painted:
     *  - a slab whose upper plane is below the eye is behind all slabs above it, and a slab whose
     *    lower plane is above the eye is behind all slabs below it;
     *  - slabs on opposite sides of the eye's own plane never overlap on screen;
     *  - so peeling the lowest or highest remaining slab, whichever lies beyond the eye, is exact.
     *
     * Writes slab indices, back to front, into [out]. Allocation-free.
     */
    fun backToFront(count: Int, starts: FloatArray, ends: FloatArray, eye: Float, out: IntArray) {
        var lo = 0
        var hi = count - 1
        var k = 0
        while (lo <= hi) {
            out[k++] = when {
                eye >= ends[lo] -> lo++
                eye <= starts[hi] -> hi--
                // Only reachable with a single slab left (the eye is inside it).
                else -> lo++
            }
        }
    }
}
