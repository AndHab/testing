package com.andhab.cubelens.core.cube

/**
 * Facelet indexing and 3D geometry.
 *
 * Facelets are numbered in the standard Kociemba layout: face order U, R, F, D, L, B, each face's
 * nine stickers row-major as they appear in this net (row 0 = top row of the face as drawn):
 *
 * ```
 *              |U0 U1 U2|
 *              |U3 U4 U5|
 *              |U6 U7 U8|
 *     |L0 L1 L2|F0 F1 F2|R0 R1 R2|B0 B1 B2|
 *     |L3 L4 L5|F3 F4 F5|R3 R4 R5|B3 B4 B5|
 *     |L6 L7 L8|F6 F7 F8|R6 R7 R8|B6 B7 B8|
 *              |D0 D1 D2|
 *              |D3 D4 D5|
 *              |D6 D7 D8|
 * ```
 *
 * How each face is "looked at" when read row-major (this is also how the camera sees it while scanning):
 *  - F, R, B, L: looking straight at that face, U face on top.
 *  - U: looking down at the top, B face at the top of the image (F at the bottom).
 *  - D: looking up at the bottom, F face at the top of the image (B at the bottom).
 *
 * Coordinates: x points right (towards R), y up (towards U), z towards the viewer (towards F).
 * Each sticker has the integer position of its cubie (each coordinate in -1..1) and the outward
 * normal of its face.
 */
object Facelets {
    const val COUNT = 54

    const val SOLVED_STRING = "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB"

    /** Index of the center sticker of each face. */
    fun center(face: Face): Int = face.ordinal * 9 + 4

    fun index(face: Face, row: Int, col: Int): Int {
        require(row in 0..2 && col in 0..2)
        return face.ordinal * 9 + row * 3 + col
    }

    fun faceOf(index: Int): Face = Face.entries[index / 9]
    fun rowOf(index: Int): Int = (index % 9) / 3
    fun colOf(index: Int): Int = index % 3

    /** Cubie position of each facelet. */
    val position: List<Vec3> = List(COUNT) { i ->
        val r = rowOf(i)
        val c = colOf(i)
        when (faceOf(i)) {
            Face.U -> Vec3(c - 1, 1, r - 1)
            Face.R -> Vec3(1, 1 - r, 1 - c)
            Face.F -> Vec3(c - 1, 1 - r, 1)
            Face.D -> Vec3(c - 1, -1, 1 - r)
            Face.L -> Vec3(-1, 1 - r, c - 1)
            Face.B -> Vec3(1 - c, 1 - r, -1)
        }
    }

    /** Outward normal of each facelet (the normal of the face it is on). */
    val normal: List<Vec3> = List(COUNT) { i -> faceOf(i).normal }

    private val lookup: Map<Pair<Vec3, Vec3>, Int> =
        (0 until COUNT).associateBy { position[it] to normal[it] }

    /** The facelet at cubie [position] whose outward normal is [normal]. */
    fun indexOf(position: Vec3, normal: Vec3): Int =
        lookup[position to normal] ?: throw IllegalArgumentException("No facelet at $position facing $normal")

    /**
     * Rotates [v] by a quarter turn clockwise as seen when looking at the face whose outward normal is
     * [axis] from outside the cube (i.e. a -90 degree right-handed rotation about [axis]).
     */
    fun rotateClockwise(v: Vec3, axis: Vec3): Vec3 {
        // Rodrigues with theta = -90deg: v' = -(k x v) + k (k . v)
        val cross = Vec3(
            axis.y * v.z - axis.z * v.y,
            axis.z * v.x - axis.x * v.z,
            axis.x * v.y - axis.y * v.x,
        )
        return axis * (axis dot v) - cross
    }

    /** Whether the facelet's cubie belongs to the outer layer of [face]. */
    fun isInLayer(index: Int, face: Face): Boolean = (position[index] dot face.normal) == 1
}
