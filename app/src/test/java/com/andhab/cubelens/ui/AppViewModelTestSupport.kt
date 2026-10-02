package com.andhab.cubelens.ui

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.core.nxn.NxNSolution
import com.andhab.cubelens.core.nxn.NxNSolver
import com.andhab.cubelens.core.nxn.SolveStage
import com.andhab.cubelens.core.vision.CubePaletteEstimate
import com.andhab.cubelens.core.vision.StickerSample
import com.andhab.cubelens.ui.review.ReviewState
import kotlin.random.Random

/** The review on screen; fails if another screen is showing. */
internal fun AppViewModel.review(): ReviewState = (state.value.screen as Screen.Review).review

/**
 * The six faces of [cube] as the camera delivers them: in the guided scan order (front, right,
 * back, left, top, bottom), each face row by row, every sticker a clean sample of its color in
 * [look] (the stock colors by default).
 */
internal fun scansOf(cube: NxNCube, look: Map<CubeColor, Int> = CubePaletteEstimate.STANDARD.colors): List<List<StickerSample>> {
    val samples = CubeColor.entries.associateWith { StickerSample.ofArgb(look.getValue(it)) }
    return AppViewModel.ScanOrder.map { face -> cube.face(face).map { samples.getValue(it) } }
}

/** A scrambled [n]×[n] cube, the same for the same [seed]. */
internal fun scrambledCube(n: Int, seed: Int = 11): NxNCube =
    NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(seed)))

/**
 * Design colors (0xAARRGGBB) of a pastel knock-off cube: warm white, lemon, mint, baby blue, pink
 * and peach.
 */
internal val PastelLook: Map<CubeColor, Int> = mapOf(
    CubeColor.WHITE to 0xFFF7F5EE.toInt(),
    CubeColor.YELLOW to 0xFFF3E58A.toInt(),
    CubeColor.GREEN to 0xFF9EDDB0.toInt(),
    CubeColor.BLUE to 0xFF93BFEA.toInt(),
    CubeColor.RED to 0xFFF2A0B4.toInt(),
    CubeColor.ORANGE to 0xFFF7BE92.toInt(),
)

/** Records calls; solves with a fixed one-move answer or fails as told. */
internal class FakeSolver(
    private val prepareError: Exception? = null,
    private val solveError: Exception? = null,
) : CubeSolver {
    val calls = mutableListOf<String>()

    override fun prepare() {
        calls += "prepare"
        prepareError?.let { throw it }
    }

    override fun prepareSize(n: Int) {
        calls += "prepareSize($n)"
    }

    override fun solve(cube: NxNCube): NxNSolution {
        calls += "solve"
        solveError?.let { throw it }
        return NxNSolution(cube.n, listOf(SolveStage(NxNSolver.STAGE_SOLVE, listOf(LayerMove.outer(Face.R, 1)))))
    }
}
