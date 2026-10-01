package com.andhab.cubelens.core.solver

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.cube.Vec3

/**
 * Whole-cube rotation by 120 degrees about the URF-DBL diagonal, which maps the R face to U, U to
 * F and F to R. Applying it once or twice turns the R-L or the F-B axis into the U-D axis, so the
 * solver can run phase 1 against each of the three axes and keep the best result.
 *
 * Works purely on the facelet geometry of [Facelets]: rotating a physical cube moves each sticker
 * to the rotated location and relabels it with the face its center moved to. A face turn of the
 * original cube is then the same turn (same direction, since rotations preserve handedness) of the
 * face it was rotated onto.
 */
internal object CubeRotation {

    /** The rotation (x, y, z) -> (z, x, y): R -> U, U -> F, F -> R. */
    private fun rotate(v: Vec3) = Vec3(v.z, v.x, v.y)

    /** `FACE_IMAGE[k][f]` is the face that face `f` lands on after `k` rotations. */
    private val FACE_IMAGE: Array<Array<Face>> = Array(3) { k ->
        Array(6) { f ->
            var n = Face.entries[f].normal
            repeat(k) { n = rotate(n) }
            Face.entries.first { it.normal == n }
        }
    }

    /** Facelet index that facelet `i` moves to under one rotation. */
    private val FACELET_IMAGE: IntArray = IntArray(Facelets.COUNT) { i ->
        Facelets.indexOf(rotate(Facelets.position[i]), rotate(Facelets.normal[i]))
    }

    /** The face that [face] lands on after rotating the cube [times] times (0..2). */
    fun faceImage(face: Face, times: Int): Face = FACE_IMAGE[times][face.ordinal]

    /** [cube] physically rotated [times] times (0..2). */
    fun rotate(cube: FaceletCube, times: Int): FaceletCube {
        var faces = cube.toList()
        repeat(times) {
            val rotated = arrayOfNulls<Face>(Facelets.COUNT)
            for (i in 0 until Facelets.COUNT) rotated[FACELET_IMAGE[i]] = FACE_IMAGE[1][faces[i].ordinal]
            faces = rotated.map { checkNotNull(it) }
        }
        return FaceletCube.of(faces)
    }

    /** Maps a move made on the cube rotated [times] times back to the original orientation. */
    fun unrotate(move: Move, times: Int): Move {
        if (times == 0) return move
        val original = Face.entries.first { faceImage(it, times) == move.face }
        return Move.of(original, move.turns)
    }
}
