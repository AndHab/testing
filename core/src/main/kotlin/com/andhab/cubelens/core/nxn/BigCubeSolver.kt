package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.CubieCube
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.solver.TwoPhaseSolver

/**
 * Solves cubes of size 4 and up, piece type by piece type, with moves that never disturb what is
 * already solved:
 *
 * 1. **Frame.** Odd sizes: the corners, middle edges and fixed centers behave exactly like a 3×3
 *    cube under outer turns, so that 3×3 is solved with [TwoPhaseSolver]. Even sizes: the corners
 *    behave like a 2×2, solved optimally by [CornerSolver] with U, R and F turns (the DBL corner
 *    stays home).
 * 2. **Edges.** Every wing orbit is solved with pure 3-cycles from [CycleLibrary]. 3-cycles only
 *    make even permutations, so an orbit with an odd permutation first gets one inner-slice
 *    quarter turn at its depth: that 4-cycles four of its wings and touches no corner, middle edge
 *    or fixed center (it does move centers, which are solved afterwards).
 * 3. **Centers.** Every center orbit is solved with pure 3-cycles. Centers of one color are
 *    interchangeable, so there is never a parity problem.
 *
 * Cubes at most two block moves from solved get that short solution directly. Moves are merged and
 * cancelled within each stage ([MoveSequence]). Even cubes are only ever turned with moves that keep
 * the DBL corner in place (no outer D, L or B turns), so they end in the scanned orientation.
 */
internal object BigCubeSolver {

    fun solve(cube: NxNCube, scheme: Map<Face, CubeColor>, timeoutMillis: Long): List<SolveStage> {
        val n = cube.n
        require(n >= 4)
        val model = NxNModel.of(n)
        val target = IntArray(model.stickerCount) { scheme.getValue(model.geometry.faceOf(it)).ordinal }

        shortSolution(cube, target, model)?.let { return listOf(SolveStage(NxNSolver.STAGE_SOLVE, it)) }

        val library = CycleLibrary.of(n)
        val stages = ArrayList<SolveStage>()

        // 1. Frame.
        val frameMoves = if (n % 2 == 1) {
            val frame = FaceletCube.fromColors(List(54) { cube[model.frameSticker(it)] })
                ?: error("Fixed centers of a validated cube are distinct")
            TwoPhaseSolver.solve(frame, FRAME_TARGET_LENGTH, (timeoutMillis / 4).coerceIn(1, FRAME_MAX_MILLIS))
                .moves.map(LayerMove::of)
        } else {
            solveCorners(cube, model, scheme).map(LayerMove::of)
        }
        stages += SolveStage(if (n % 2 == 1) NxNSolver.STAGE_FRAME else NxNSolver.STAGE_CORNERS, frameMoves)
        var state = cube.apply(frameMoves)

        // 2. Edges: parity slices, then 3-cycles per wing orbit.
        val edges = MoveSequence(n)
        val colorOfFace = IntArray(6) { scheme.getValue(Face.entries[it]).ordinal }
        for ((o, orbit) in model.wingOrbits.withIndex()) {
            val home = NxNChecks.wingHomes(model, orbit, colorOfFace)
            if (CubieCube.permutationParity(wingState(state, orbit, home)) == 1) {
                val slice = bestParitySlice(state, target, model, orbit.depth)
                state = state.apply(model.layerMove(slice))
                edges.addCode(model, slice)
            }
            OrbitSolver.solve(library.wingCycles[o], wingState(state, orbit, home), IntArray(orbit.size) { it }, edges)
        }
        stages += SolveStage(NxNSolver.STAGE_EDGES, edges.toLayerMoves())

        // 3. Centers (the edge 3-cycles left them alone, so `state` still has the centers right).
        val centers = MoveSequence(n)
        for ((o, orbit) in model.centerOrbits.withIndex()) {
            val colors = IntArray(orbit.size) { state[orbit.slots[it][0]].ordinal }
            val goal = IntArray(orbit.size) { target[orbit.slots[it][0]] }
            OrbitSolver.solve(library.centerCycles[o], colors, goal, centers)
        }
        stages += SolveStage(NxNSolver.STAGE_CENTERS, centers.toLayerMoves())
        return stages
    }

    /** Optimal U/R/F solution for the corners of an even cube (DBL corner home by construction of [scheme]). */
    fun solveCorners(cube: NxNCube, model: NxNModel, scheme: Map<Face, CubeColor>): List<Move> {
        val faceOfColor = IntArray(6)
        for ((face, color) in scheme) faceOfColor[color.ordinal] = face.ordinal
        val cp = IntArray(8)
        val co = IntArray(8)
        for (i in 0 until 8) {
            val id = NxNChecks.identifyCorner(IntArray(3) { cube[model.corners[i][it]].ordinal }, faceOfColor)
            check(id >= 0) { "Corner $i of a validated cube is not a real piece" }
            cp[i] = id / 3
            co[i] = id % 3
        }
        return CornerSolver.solve(cp, co)
    }

    /** For each slot of a wing [orbit]: the home slot of the wing in it. */
    private fun wingState(state: NxNCube, orbit: Orbit, home: IntArray): IntArray =
        IntArray(orbit.size) { s ->
            val (a, b) = orbit.slots[s]
            home[state[a].ordinal * 6 + state[b].ordinal]
        }

    /**
     * Of the inner-slice quarter turns that fix the parity of the wing orbit at [depth], the one
     * leaving the most stickers right (so a cube one slice turn from solved is solved by it).
     */
    private fun bestParitySlice(state: NxNCube, target: IntArray, model: NxNModel, depth: Int): Int {
        var best = -1
        var bestScore = -1
        val layers = listOf(depth, model.n - 1 - depth).distinct()
        for (axis in 0 until 3) for (layer in layers) for (turns in intArrayOf(1, 3)) {
            val code = model.code(axis, layer, turns)
            val p = model.perm(code)
            var score = 0
            for (i in p.indices) if (state[p[i]].ordinal == target[i]) score++
            if (score > bestScore) {
                best = code
                bestScore = score
            }
        }
        return best
    }

    /**
     * A solution of at most two block moves, if one exists (allowed moves only: no whole-cube
     * rotations, and for even sizes nothing that turns the outer D, L or B layer).
     */
    fun shortSolution(cube: NxNCube, target: IntArray, model: NxNModel): List<LayerMove>? {
        val n = model.n
        val moves = ArrayList<LayerMove>()
        val axes = ArrayList<Int>()
        for (axis in 0 until 3) for (from in 0 until n) for (to in from until n) {
            if (from == 0 && to == n - 1) continue
            if (n % 2 == 0 && to == n - 1) continue
            for (turns in 1..3) {
                val t = IntArray(n)
                for (l in from..to) t[l] = turns
                moves += MoveSequence.blocks(axis, t, n).single()
                axes += axis
            }
        }
        val perms = moves.map { model.geometry.permutation(it) }
        val colors = IntArray(model.stickerCount) { cube[it].ordinal }
        for (i in moves.indices) if (matches(colors, perms[i], target)) return listOf(moves[i])
        val after = IntArray(colors.size)
        for (i in moves.indices) {
            val p = perms[i]
            for (k in after.indices) after[k] = colors[p[k]]
            for (j in moves.indices) {
                if (axes[j] == axes[i] && j <= i) continue
                if (matches(after, perms[j], target)) return listOf(moves[i], moves[j])
            }
        }
        return null
    }

    private fun matches(colors: IntArray, perm: IntArray, target: IntArray): Boolean {
        for (k in perm.indices) if (colors[perm[k]] != target[k]) return false
        return true
    }

    private const val FRAME_TARGET_LENGTH = 20
    private const val FRAME_MAX_MILLIS = 250L
}
