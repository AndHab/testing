package com.andhab.cubelens.ui.cube

import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.ui.cube.CubeGeometry.faceNormal
import com.andhab.cubelens.ui.cube.CubeGeometry.faceU
import com.andhab.cubelens.ui.cube.CubeGeometry.faceV
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The per-frame geometry of [Cube3D]: camera, turning layers, and the exact back-to-front order of
 * what to paint. Pure math (no Android), one instance reused for every frame; [update] allocates
 * nothing.
 *
 * ## Painter's algorithm
 *
 * A layer turn splits the cube into up to three **slab groups** along the turning axis: the layers
 * before the turning range, the turning range, and the layers after it. Each group is a rigid,
 * axis-aligned box of cubies (the turning one rotated about the axis, which keeps it inside its
 * own slab of space), and groups are separated by the cut planes perpendicular to the axis. So:
 *  1. groups are painted back to front with [CubeGeometry.backToFront] on the cut planes, which is
 *     exact for any eye position;
 *  2. within a group only the outside of its box can be seen: the cubie faces glued to a neighbor
 *     of the same group are hidden. A box is convex, so its faces turned towards the camera never
 *     overlap on screen, and each of them is painted as one grid of cubie faces (see
 *     [isFaceVisible]). That replaces sorting hundreds of cubies by depth with at most three faces
 *     per group, which keeps a 7×7 as cheap to order as a 2×2.
 *
 * At rest there is a single group: the whole cube.
 */
internal class CubeScene {

    /** The cube being shown. */
    var lattice: CubeLattice = CubeLattice.of(3)
        private set

    /** View rotation: cube to camera coordinates. */
    val view = FloatArray(9)

    /** Camera position in cube coordinates. */
    val eye = FloatArray(3)

    /** Rotation of the turning group (identity at rest). */
    val turn = FloatArray(9)

    /** Rotation of the turning group once the turn is complete (identity at rest). */
    private val turnEnd = FloatArray(9)

    /** Turning axis (0 = x, 1 = y, 2 = z); 1 at rest. */
    var axis = 1
        private set

    /** Number of slab groups: 1 at rest, up to 3 mid-turn. */
    var groupCount = 0
        private set

    /** Current rotation of the turning group about the turning face's normal, in degrees (0 at rest). */
    var turnAngle = 0f
        private set

    /** Rotation at which the current turn ends, in degrees: a multiple of 90 (0 at rest). */
    var turnTarget = 0f
        private set

    /** Fraction (0..1, eased like the rotation) of the current turn already done; 0 at rest. */
    val turnFraction: Float
        get() = if (turnTarget == 0f) 0f else turnAngle / turnTarget

    /**
     * Degrees between the turning group and the nearer of its two rest poses (the start and the end
     * of the turn); 0 at rest. In either rest pose the cube is a whole, axis-aligned box again, so
     * overlays that assume one (e.g. the focus outline) can fade with this rather than pop.
     */
    val degreesFromRest: Float
        get() = min(abs(turnAngle), abs(turnTarget - turnAngle))

    // Groups in ascending layer order along the axis.
    private val groupFrom = IntArray(MAX_GROUPS)
    private val groupTo = IntArray(MAX_GROUPS)
    private val groupTurning = BooleanArray(MAX_GROUPS)
    private val starts = FloatArray(MAX_GROUPS)
    private val ends = FloatArray(MAX_GROUPS)
    private val drawOrder = IntArray(MAX_GROUPS)

    private val center = FloatArray(3)
    private val normal = FloatArray(3)

    /**
     * Sets up a frame: the camera at [yaw] / [pitch] degrees and [move] (if any) turned to linear
     * time fraction [progress]. A move deeper than the cube is shown at rest.
     */
    fun update(lattice: CubeLattice, yaw: Float, pitch: Float, move: LayerMove?, progress: Float) {
        this.lattice = lattice
        CubeGeometry.viewRotation(yaw, pitch, view)
        CubeGeometry.eyePosition(view, eye)
        val n = lattice.n
        groupCount = 0
        if (move == null || move.toDepth > n) {
            axis = 1
            turnAngle = 0f
            turnTarget = 0f
            CubeGeometry.identity(turn)
            CubeGeometry.identity(turnEnd)
            addGroup(0, n - 1, turning = false)
        } else {
            axis = CubeGeometry.axisOf(move.face)
            val a = lattice.layerAtDepth(move.face, move.fromDepth)
            val b = lattice.layerAtDepth(move.face, move.toDepth)
            val lo = min(a, b)
            val hi = max(a, b)
            // The cached normals, not Face.normal, which allocates.
            val f = move.face.ordinal * 3
            turnAngle = turnAngleDegrees(move, progress)
            turnTarget = turnAngleDegrees(move, 1f)
            CubeGeometry.rotation(faceNormal[f], faceNormal[f + 1], faceNormal[f + 2], turnAngle, turn)
            CubeGeometry.rotation(faceNormal[f], faceNormal[f + 1], faceNormal[f + 2], turnTarget, turnEnd)
            if (lo > 0) addGroup(0, lo - 1, turning = false)
            addGroup(lo, hi, turning = true)
            if (hi < n - 1) addGroup(hi + 1, n - 1, turning = false)
        }
        CubeGeometry.backToFront(groupCount, starts, ends, eye[axis], drawOrder)
    }

    private fun addGroup(from: Int, to: Int, turning: Boolean) {
        val g = groupCount++
        groupFrom[g] = from
        groupTo[g] = to
        groupTurning[g] = turning
        starts[g] = lattice.plane(from)
        ends[g] = lattice.plane(to + 1)
    }

    /** The [i]-th group to paint (back to front), as a group index for the other accessors. */
    fun groupInDrawOrder(i: Int): Int = drawOrder[i]

    /** Whether group [g] is the turning one. */
    fun isTurning(g: Int): Boolean = groupTurning[g]

    /** Lowest layer index of group [g] along coordinate axis [a]. */
    fun lower(g: Int, a: Int): Int = if (a == axis) groupFrom[g] else 0

    /** Highest layer index of group [g] along coordinate axis [a]. */
    fun upper(g: Int, a: Int): Int = if (a == axis) groupTo[g] else lattice.n - 1

    /** Number of cubies of group [g] along coordinate axis [a]. */
    fun span(g: Int, a: Int): Int = upper(g, a) - lower(g, a) + 1

    /**
     * The face (a [com.andhab.cubelens.core.cube.Face] ordinal, in the turning group's own frame)
     * that the completed turn carries onto world face [d]: e.g. for a whole-cube `x` rotation, D
     * lands on F. Faces whose direction the turn does not change (and every face at rest) map to
     * themselves.
     */
    fun faceLandingOn(d: Int): Int {
        for (c in 0 until 6) {
            CubeGeometry.transform(turnEnd, faceNormal[c * 3].toFloat(), faceNormal[c * 3 + 1].toFloat(), faceNormal[c * 3 + 2].toFloat(), normal)
            val dot = normal[0] * faceNormal[d * 3] + normal[1] * faceNormal[d * 3 + 1] + normal[2] * faceNormal[d * 3 + 2]
            if (dot > 0.5f) return c
        }
        return d
    }

    /**
     * The world face that box face [d] of the turning group faces once the turn is complete (the
     * inverse of [faceLandingOn]): e.g. for a whole-cube `x` rotation, F ends up facing U.
     */
    fun faceCarriedTo(d: Int): Int {
        CubeGeometry.transform(turnEnd, faceNormal[d * 3].toFloat(), faceNormal[d * 3 + 1].toFloat(), faceNormal[d * 3 + 2].toFloat(), normal)
        for (c in 0 until 6) {
            val dot = normal[0] * faceNormal[c * 3] + normal[1] * faceNormal[c * 3 + 1] + normal[2] * faceNormal[c * 3 + 2]
            if (dot > 0.5f) return c
        }
        return d
    }

    /** Writes (x, y, z), a point or direction of group [g], in world space (rotated if it turns) into [out]. */
    fun orient(g: Int, x: Float, y: Float, z: Float, out: FloatArray, offset: Int = 0) {
        if (groupTurning[g]) {
            CubeGeometry.transform(turn, x, y, z, out, offset)
        } else {
            out[offset] = x; out[offset + 1] = y; out[offset + 2] = z
        }
    }

    /** World coordinate (before rotation) of the box face of group [g] along axis [a] on side [sign]. */
    fun boxPlane(g: Int, a: Int, sign: Int): Float =
        if (sign > 0) lattice.plane(upper(g, a) + 1) else lattice.plane(lower(g, a))

    /** Center (before rotation) of group [g]'s box along axis [a]. */
    fun boxCenter(g: Int, a: Int): Float = (lattice.plane(lower(g, a)) + lattice.plane(upper(g, a) + 1)) * 0.5f

    /**
     * Corner [k] of box face [d] (a [com.andhab.cubelens.core.cube.Face] ordinal) of group [g], in
     * world space, into [out] at [offset]. Corners go around the face's local rectangle: 0 at
     * (-u, -v), 1 at (+u, -v), 2 at (+u, +v), 3 at (-u, +v) (see [CubeGeometry.faceU]).
     */
    fun faceCorner(g: Int, d: Int, k: Int, out: FloatArray, offset: Int = 0) {
        val su = if (k == 1 || k == 2) 1 else -1
        val sv = if (k >= 2) 1 else -1
        var x = 0f
        var y = 0f
        var z = 0f
        for (a in 0 until 3) {
            val sign = faceNormal[d * 3 + a] + su * faceU[d * 3 + a] + sv * faceV[d * 3 + a]
            val c = boxPlane(g, a, sign)
            when (a) {
                0 -> x = c
                1 -> y = c
                else -> z = c
            }
        }
        orient(g, x, y, z, out, offset)
    }

    /**
     * Whether box face [d] of group [g] is turned towards the camera, so it is painted. Faces seen
     * nearly edge-on are skipped: they cover no pixels and would need a degenerate mapping.
     */
    fun isFaceVisible(g: Int, d: Int): Boolean = facing(g, d) > MIN_FACING

    /**
     * How far (world units) the camera is in front of the plane of box face [d] of group [g]:
     * positive when the face is turned towards the camera, about 0 when it is seen edge-on.
     */
    fun facing(g: Int, d: Int): Float {
        val nx = faceNormal[d * 3]
        val ny = faceNormal[d * 3 + 1]
        val nz = faceNormal[d * 3 + 2]
        val cx = if (nx != 0) boxPlane(g, 0, nx) else boxCenter(g, 0)
        val cy = if (ny != 0) boxPlane(g, 1, ny) else boxCenter(g, 1)
        val cz = if (nz != 0) boxPlane(g, 2, nz) else boxCenter(g, 2)
        orient(g, cx, cy, cz, center)
        orient(g, nx.toFloat(), ny.toFloat(), nz.toFloat(), normal)
        return (eye[0] - center[0]) * normal[0] + (eye[1] - center[1]) * normal[1] + (eye[2] - center[2]) * normal[2]
    }

    private companion object {
        const val MAX_GROUPS = 3

        /** Minimum facing (distance of the eye in front of the face's plane) for a face to be drawn. */
        const val MIN_FACING = 0.02f
    }
}
