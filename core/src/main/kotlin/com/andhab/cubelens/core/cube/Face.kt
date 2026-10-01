package com.andhab.cubelens.core.cube

/**
 * The six faces of the cube, in the canonical Kociemba order U, R, F, D, L, B.
 *
 * The ordinal is used directly as an index everywhere (facelet index = face.ordinal * 9 + row * 3 + col).
 */
enum class Face {
    U, R, F, D, L, B;

    val opposite: Face
        get() = when (this) {
            U -> D
            D -> U
            R -> L
            L -> R
            F -> B
            B -> F
        }

    /** Outward unit normal in the cube coordinate system (x = right, y = up, z = toward the viewer/front). */
    val normal: Vec3
        get() = when (this) {
            U -> Vec3(0, 1, 0)
            D -> Vec3(0, -1, 0)
            R -> Vec3(1, 0, 0)
            L -> Vec3(-1, 0, 0)
            F -> Vec3(0, 0, 1)
            B -> Vec3(0, 0, -1)
        }

    companion object {
        fun fromChar(c: Char): Face = when (c) {
            'U' -> U
            'R' -> R
            'F' -> F
            'D' -> D
            'L' -> L
            'B' -> B
            else -> throw IllegalArgumentException("Not a face: '$c'")
        }
    }
}

/** Integer 3D vector; cubie positions use coordinates in {-1, 0, 1}. */
data class Vec3(val x: Int, val y: Int, val z: Int) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Int) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
}
