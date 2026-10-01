package com.andhab.cubelens.core.nxn

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
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

/** Validation and color-scheme inference for any size. STUB bodies. */
object NxNValidator {
    fun validate(cube: NxNCube): NxNValidation = TODO()
}

/**
 * Solves cubes of any supported size: 2×2 optimally, 3×3 with the two-phase algorithm,
 * 4×4 and larger by reduction (centers, edge pairing, 3×3 stage, parity fixes). STUB bodies.
 */
object NxNSolver {
    /** Prepares lookup tables (2×2 and 3×3), loading/saving them in [cacheDir] when given. Thread-safe, idempotent. */
    fun prepare(cacheDir: File? = null): Unit = TODO()

    /**
     * Returns a solution whose moves, applied to [cube], leave every face a single color.
     * Thread-safe; blocks for up to about [timeoutMillis] (plus table preparation).
     *
     * @throws UnsolvableNxNException if [cube] is impossible.
     */
    fun solve(cube: NxNCube, timeoutMillis: Long = 3_000): NxNSolution = TODO()
}

/** Random scrambles for any size. STUB bodies. */
object NxNScrambler {
    /** WCA-style random-move scramble with a length suited to [n] (2×2: ~11, 3×3: ~20, 4×4: ~40, 5×5: 60, 6×6: 80, 7×7: 100). */
    fun randomMoves(n: Int, random: Random = Random.Default, count: Int = defaultLength(n)): List<LayerMove> = TODO()

    fun defaultLength(n: Int): Int = TODO()
}
