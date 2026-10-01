package com.andhab.cubelens.core.cube

/**
 * Cube state at piece (cubie) level, Kociemba conventions.
 *
 * Corner positions/cubies: URF, UFL, ULB, UBR, DFR, DLF, DBL, DRB (0..7).
 * Edge positions/cubies:   UR, UF, UL, UB, DR, DF, DL, DB, FR, FL, BL, BR (0..11).
 *
 * `cp[i]` is the corner cubie sitting at corner position `i` and `co[i]` its twist (0..2);
 * `ep[i]` / `eo[i]` likewise for edges (flip 0..1).
 */
class CubieCube(
    val cp: IntArray = IntArray(8) { it },
    val co: IntArray = IntArray(8),
    val ep: IntArray = IntArray(12) { it },
    val eo: IntArray = IntArray(12),
) {
    init {
        require(cp.size == 8 && co.size == 8 && ep.size == 12 && eo.size == 12)
    }

    fun copy(): CubieCube = CubieCube(cp.copyOf(), co.copyOf(), ep.copyOf(), eo.copyOf())

    /** Returns this * [other]: first apply this, then [other]. */
    fun multiply(other: CubieCube): CubieCube {
        val ncp = IntArray(8) { cp[other.cp[it]] }
        val nco = IntArray(8) { (co[other.cp[it]] + other.co[it]) % 3 }
        val nep = IntArray(12) { ep[other.ep[it]] }
        val neo = IntArray(12) { (eo[other.ep[it]] + other.eo[it]) % 2 }
        return CubieCube(ncp, nco, nep, neo)
    }

    fun apply(move: Move): CubieCube = multiply(moveCube(move))

    fun apply(moves: Iterable<Move>): CubieCube = moves.fold(this) { c, m -> c.apply(m) }

    fun inverse(): CubieCube {
        val icp = IntArray(8)
        val ico = IntArray(8)
        val iep = IntArray(12)
        val ieo = IntArray(12)
        for (i in 0 until 8) icp[cp[i]] = i
        for (i in 0 until 8) ico[i] = (3 - co[icp[i]]) % 3
        for (i in 0 until 12) iep[ep[i]] = i
        for (i in 0 until 12) ieo[i] = eo[iep[i]]
        return CubieCube(icp, ico, iep, ieo)
    }

    fun cornerParity(): Int = permutationParity(cp)
    fun edgeParity(): Int = permutationParity(ep)

    val isSolved: Boolean
        get() = (0 until 8).all { cp[it] == it && co[it] == 0 } && (0 until 12).all { ep[it] == it && eo[it] == 0 }

    fun toFaceletCube(): FaceletCube {
        val f = Array(Facelets.COUNT) { Facelets.faceOf(it) }
        for (i in 0 until 8) {
            val j = cp[i]
            val ori = co[i]
            for (n in 0 until 3) f[CORNER_FACELET[i][(n + ori) % 3]] = CORNER_COLOR[j][n]
        }
        for (i in 0 until 12) {
            val j = ep[i]
            val ori = eo[i]
            for (n in 0 until 2) f[EDGE_FACELET[i][(n + ori) % 2]] = EDGE_COLOR[j][n]
        }
        return FaceletCube.of(f.toList())
    }

    override fun equals(other: Any?): Boolean =
        other is CubieCube && cp.contentEquals(other.cp) && co.contentEquals(other.co) &&
            ep.contentEquals(other.ep) && eo.contentEquals(other.eo)

    override fun hashCode(): Int =
        ((cp.contentHashCode() * 31 + co.contentHashCode()) * 31 + ep.contentHashCode()) * 31 + eo.contentHashCode()

    override fun toString(): String =
        "CubieCube(cp=${cp.toList()}, co=${co.toList()}, ep=${ep.toList()}, eo=${eo.toList()})"

    companion object {
        const val URF = 0; const val UFL = 1; const val ULB = 2; const val UBR = 3
        const val DFR = 4; const val DLF = 5; const val DBL = 6; const val DRB = 7

        const val UR = 0; const val UF = 1; const val UL = 2; const val UB = 3
        const val DR = 4; const val DF = 5; const val DL = 6; const val DB = 7
        const val FR = 8; const val FL = 9; const val BL = 10; const val BR = 11

        val CORNER_NAMES = listOf("URF", "UFL", "ULB", "UBR", "DFR", "DLF", "DBL", "DRB")
        val EDGE_NAMES = listOf("UR", "UF", "UL", "UB", "DR", "DF", "DL", "DB", "FR", "FL", "BL", "BR")

        private fun fi(face: Face, n: Int) = face.ordinal * 9 + n - 1

        /** Facelet indices of each corner position, U/D sticker first, then clockwise. */
        val CORNER_FACELET: List<IntArray> = listOf(
            intArrayOf(fi(Face.U, 9), fi(Face.R, 1), fi(Face.F, 3)),
            intArrayOf(fi(Face.U, 7), fi(Face.F, 1), fi(Face.L, 3)),
            intArrayOf(fi(Face.U, 1), fi(Face.L, 1), fi(Face.B, 3)),
            intArrayOf(fi(Face.U, 3), fi(Face.B, 1), fi(Face.R, 3)),
            intArrayOf(fi(Face.D, 3), fi(Face.F, 9), fi(Face.R, 7)),
            intArrayOf(fi(Face.D, 1), fi(Face.L, 9), fi(Face.F, 7)),
            intArrayOf(fi(Face.D, 7), fi(Face.B, 9), fi(Face.L, 7)),
            intArrayOf(fi(Face.D, 9), fi(Face.R, 9), fi(Face.B, 7)),
        )

        /** Facelet indices of each edge position. */
        val EDGE_FACELET: List<IntArray> = listOf(
            intArrayOf(fi(Face.U, 6), fi(Face.R, 2)),
            intArrayOf(fi(Face.U, 8), fi(Face.F, 2)),
            intArrayOf(fi(Face.U, 4), fi(Face.L, 2)),
            intArrayOf(fi(Face.U, 2), fi(Face.B, 2)),
            intArrayOf(fi(Face.D, 6), fi(Face.R, 8)),
            intArrayOf(fi(Face.D, 2), fi(Face.F, 8)),
            intArrayOf(fi(Face.D, 4), fi(Face.L, 8)),
            intArrayOf(fi(Face.D, 8), fi(Face.B, 8)),
            intArrayOf(fi(Face.F, 6), fi(Face.R, 4)),
            intArrayOf(fi(Face.F, 4), fi(Face.L, 6)),
            intArrayOf(fi(Face.B, 6), fi(Face.L, 4)),
            intArrayOf(fi(Face.B, 4), fi(Face.R, 6)),
        )

        /** Face colors of each corner cubie, in the same order as [CORNER_FACELET]. */
        val CORNER_COLOR: List<Array<Face>> = listOf(
            arrayOf(Face.U, Face.R, Face.F),
            arrayOf(Face.U, Face.F, Face.L),
            arrayOf(Face.U, Face.L, Face.B),
            arrayOf(Face.U, Face.B, Face.R),
            arrayOf(Face.D, Face.F, Face.R),
            arrayOf(Face.D, Face.L, Face.F),
            arrayOf(Face.D, Face.B, Face.L),
            arrayOf(Face.D, Face.R, Face.B),
        )

        /** Face colors of each edge cubie, in the same order as [EDGE_FACELET]. */
        val EDGE_COLOR: List<Array<Face>> = listOf(
            arrayOf(Face.U, Face.R),
            arrayOf(Face.U, Face.F),
            arrayOf(Face.U, Face.L),
            arrayOf(Face.U, Face.B),
            arrayOf(Face.D, Face.R),
            arrayOf(Face.D, Face.F),
            arrayOf(Face.D, Face.L),
            arrayOf(Face.D, Face.B),
            arrayOf(Face.F, Face.R),
            arrayOf(Face.F, Face.L),
            arrayOf(Face.B, Face.L),
            arrayOf(Face.B, Face.R),
        )

        val SOLVED: CubieCube get() = CubieCube()

        private val MOVE_CUBES: List<CubieCube> by lazy {
            Move.entries.map { m ->
                CubieCubeConverter.fromFacelets(FaceletCube.SOLVED.apply(m))
                    ?: error("Move ${m.notation} produced an invalid cube")
            }
        }

        /** The cubie-level effect of [move] (derived from the facelet geometry, so the two always agree). */
        fun moveCube(move: Move): CubieCube = MOVE_CUBES[move.ordinal]

        fun fromFacelets(cube: FaceletCube): CubieCube? = CubieCubeConverter.fromFacelets(cube)

        internal fun permutationParity(p: IntArray): Int {
            var s = 0
            for (i in p.indices) for (j in i + 1 until p.size) if (p[i] > p[j]) s++
            return s % 2
        }
    }
}

internal object CubieCubeConverter {
    /**
     * Reads the pieces off a facelet cube. Returns null if any corner/edge position holds a sticker
     * combination that is not a real piece (use [CubeValidator] for detailed diagnostics).
     * Does not check duplicates, twist, flip or parity.
     */
    fun fromFacelets(cube: FaceletCube): CubieCube? {
        val cp = IntArray(8)
        val co = IntArray(8)
        val ep = IntArray(12)
        val eo = IntArray(12)
        for (i in 0 until 8) {
            val (j, ori) = identifyCorner(cube, i) ?: return null
            cp[i] = j
            co[i] = ori
        }
        for (i in 0 until 12) {
            val (j, ori) = identifyEdge(cube, i) ?: return null
            ep[i] = j
            eo[i] = ori
        }
        return CubieCube(cp, co, ep, eo)
    }

    /** (cubie, twist) for the corner at position [i], or null if the stickers are not a real corner. */
    fun identifyCorner(cube: FaceletCube, i: Int): Pair<Int, Int>? {
        val fl = CubieCube.CORNER_FACELET[i]
        val faces = fl.map { cube[it] }
        val udCount = faces.count { it == Face.U || it == Face.D }
        if (udCount != 1) return null
        val ori = faces.indexOfFirst { it == Face.U || it == Face.D }
        val c0 = faces[ori]
        val c1 = faces[(ori + 1) % 3]
        val c2 = faces[(ori + 2) % 3]
        for (j in 0 until 8) {
            val col = CubieCube.CORNER_COLOR[j]
            if (c0 == col[0] && c1 == col[1] && c2 == col[2]) return j to ori
        }
        return null
    }

    /** (cubie, flip) for the edge at position [i], or null if the stickers are not a real edge. */
    fun identifyEdge(cube: FaceletCube, i: Int): Pair<Int, Int>? {
        val fl = CubieCube.EDGE_FACELET[i]
        val a = cube[fl[0]]
        val b = cube[fl[1]]
        for (j in 0 until 12) {
            val col = CubieCube.EDGE_COLOR[j]
            if (a == col[0] && b == col[1]) return j to 0
            if (a == col[1] && b == col[0]) return j to 1
        }
        return null
    }
}
