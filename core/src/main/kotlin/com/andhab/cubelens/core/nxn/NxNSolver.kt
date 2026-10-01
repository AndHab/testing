package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.solver.TwoPhaseSolver
import java.io.File
import kotlin.random.Random

/** A problem that makes a scanned/entered N×N cube impossible. */
data class NxNError(
    /** Short, friendly, jargon-free explanation for the user. */
    val message: String,
    /** Stickers to highlight (may be empty for global problems). */
    val stickers: Set<Int> = emptySet(),
)

data class NxNValidation(
    val errors: List<NxNError>,
    /**
     * The face each color belongs to in the solved orientation: from the fixed centers for odd
     * sizes, inferred from the corner pieces (colors and their clockwise order) for even sizes.
     * Null if it could not be determined.
     */
    val scheme: Map<Face, CubeColor>?,
) {
    val isValid: Boolean get() = errors.isEmpty() && scheme != null
    val flaggedStickers: Set<Int> get() = errors.flatMap { it.stickers }.toSet()
}

/** One stage of a solution, e.g. "Centers", "Edges", "3×3 stage", "Parity fix". */
data class SolveStage(val name: String, val moves: List<LayerMove>)

data class NxNSolution(val n: Int, val stages: List<SolveStage>) {
    val moves: List<LayerMove> get() = stages.flatMap { it.moves }
    val length: Int get() = stages.sumOf { it.moves.size }
}

/** Thrown when asked to solve an impossible N×N cube. */
class UnsolvableNxNException(val errors: List<NxNError>) :
    IllegalArgumentException("Cube cannot be solved: " + errors.joinToString("; ") { it.message })

/**
 * Validation and color-scheme inference for any size.
 *
 * Colors are labels, so any six distinct colors in any arrangement (knock-off cubes included) are
 * accepted as long as they could come from a real cube. Odd sizes read the arrangement from the
 * fixed centers. Even sizes infer it from the corner pieces (opposite colors never share a corner;
 * the clockwise order of a corner's colors tells a cube from its mirror image) and fix the
 * orientation so that the corner currently at DBL is home: its three colors define the D, B and L
 * faces.
 *
 * Checks, each reported as a friendly [NxNError] with the stickers to highlight (global problems
 * such as a twisted corner flag none): every color N² times; six different colors; the corners are
 * eight different real pieces whose twists add up; odd sizes: distinct fixed centers that match the
 * corners, twelve different real middle edges whose flips add up, corner and middle-edge
 * permutations of equal parity; every wing orbit holds each of its 24 pieces once (a wing's
 * handedness tells its two color orders apart, so a mirrored wing shows up as a duplicate); every
 * center orbit holds each color four times. For a 3×3 the verdict agrees with
 * [com.andhab.cubelens.core.cube.CubeValidator]. Thread-safe.
 */
object NxNValidator {
    fun validate(cube: NxNCube): NxNValidation = NxNChecks.validate(cube)
}

/**
 * Solves cubes of any supported size (2 to 10): 2×2 optimally, 3×3 with the two-phase algorithm,
 * 4×4 and larger piece type by piece type with pure 3-cycles.
 *
 * Stages: 2×2 and 3×3 have one stage, [STAGE_SOLVE]. Larger cubes have [STAGE_FRAME] (odd sizes:
 * corners, middle edges and fixed centers, solved as a 3×3) or [STAGE_CORNERS] (even sizes), then
 * [STAGE_EDGES] and [STAGE_CENTERS]; a cube at most two moves from solved gets a single
 * [STAGE_SOLVE] stage instead. Stages without moves are left out, so an already solved cube gets a
 * solution with no stages at all.
 *
 * Solutions end with every face a single color in the scanned orientation: odd cubes keep their
 * fixed centers, and even cubes are only turned with moves that keep the DBL corner in place (outer
 * U, R and F turns, inner slices, and wide turns that leave the outer D, L and B layers alone).
 * Moves are block turns: a wide or slice turn counts as one move.
 */
object NxNSolver {
    const val STAGE_SOLVE = "Solve"
    const val STAGE_CORNERS = "Corners"
    const val STAGE_FRAME = "Corners & middle edges"
    const val STAGE_EDGES = "Edges"
    const val STAGE_CENTERS = "Centers"

    /** Name of the two-phase solver's table cache inside the directory given to [prepare]. */
    const val CACHE_FILE_NAME = "solver-tables.bin"

    /**
     * Prepares lookup tables (2×2 and 3×3), loading/saving the 3×3 tables in [cacheDir] (file
     * [CACHE_FILE_NAME]) when given; the 2×2 table (3.7 MB) is built in memory. Thread-safe,
     * idempotent. Blocks for up to about a second on a desktop JVM (a few times longer on a phone),
     * so call it early and off the main thread.
     */
    fun prepare(cacheDir: File? = null) {
        TwoPhaseSolver.prepare(cacheDir?.let { File(it, CACHE_FILE_NAME) })
        CornerSolver.prepare()
    }

    /**
     * Builds the algorithm library for [n]×[n] cubes now instead of during the first [solve] of
     * that size (n >= 4; smaller sizes need nothing beyond [prepare]). Thread-safe, idempotent.
     */
    fun prepareSize(n: Int) {
        NxNGeometry.of(n)
        if (n >= 4) CycleLibrary.of(n)
    }

    /**
     * Returns a solution whose moves, applied to [cube], leave every face a single color.
     * Thread-safe; blocks for up to about [timeoutMillis] (plus table preparation).
     *
     * @throws UnsolvableNxNException if [cube] is impossible.
     */
    fun solve(cube: NxNCube, timeoutMillis: Long = 3_000): NxNSolution {
        val validation = NxNValidator.validate(cube)
        if (!validation.isValid) throw UnsolvableNxNException(validation.errors)
        val scheme = checkNotNull(validation.scheme)
        if (cube.isSolved) return NxNSolution(cube.n, emptyList())
        val stages = when (cube.n) {
            2 -> listOf(SolveStage(STAGE_SOLVE, BigCubeSolver.solveCorners(cube, NxNModel.of(2), scheme).map(LayerMove::of)))
            3 -> {
                val facelets = checkNotNull(cube.toFaceletCube()) { "Centers of a validated cube are distinct" }
                listOf(SolveStage(STAGE_SOLVE, TwoPhaseSolver.solve(facelets, 20, timeoutMillis).moves.map(LayerMove::of)))
            }
            else -> BigCubeSolver.solve(cube, scheme, timeoutMillis)
        }
        val solution = NxNSolution(cube.n, stages.filter { it.moves.isNotEmpty() })
        check(cube.apply(solution.moves) == NxNCube.solved(cube.n, scheme)) {
            "Internal error: ${LayerMove.format(solution.moves)} does not solve $cube"
        }
        return solution
    }
}

/**
 * Random scrambles for any size, in the style of WCA random-move scrambles: outer turns for the 2×2
 * (U, R and F only) and 3×3; outer and wide turns up to half the cube for larger sizes (for even
 * sizes the half-cube wide turns, e.g. `Rw` on a 4×4, only on U, R and F). Never the same face twice
 * in a row and never more than two turns in a row on one axis, so no turn cancels or merges with
 * the turns before it.
 */
object NxNScrambler {
    /** WCA-style random-move scramble with a length suited to [n] (2×2: ~11, 3×3: ~20, 4×4: ~40, 5×5: 60, 6×6: 80, 7×7: 100). */
    fun randomMoves(n: Int, random: Random = Random.Default, count: Int = defaultLength(n)): List<LayerMove> {
        NxNGeometry.of(n)
        require(count >= 0) { "count must not be negative, got $count" }
        val faces = if (n == 2) listOf(Face.U, Face.R, Face.F) else Face.entries
        val options = faces.flatMap { face ->
            val maxWidth = when {
                n <= 3 -> 1
                n % 2 == 1 -> (n - 1) / 2
                face == Face.U || face == Face.R || face == Face.F -> n / 2
                else -> n / 2 - 1
            }
            (1..maxWidth).map { width -> face to width }
        }
        val moves = ArrayList<LayerMove>(count)
        while (moves.size < count) {
            val (face, width) = options[random.nextInt(options.size)]
            val last = moves.lastOrNull()
            if (last != null && last.face == face) continue
            if (last != null && last.face == face.opposite && moves.size >= 2 && moves[moves.size - 2].face == face) continue
            moves += LayerMove(face, 1, width, random.nextInt(1, 4))
        }
        return moves
    }

    fun defaultLength(n: Int): Int {
        NxNGeometry.of(n)
        return when (n) {
            2 -> 11
            3 -> 20
            else -> 20 * (n - 2)
        }
    }
}
