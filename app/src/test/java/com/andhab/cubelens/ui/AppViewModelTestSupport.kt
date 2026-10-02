package com.andhab.cubelens.ui

import androidx.lifecycle.SavedStateHandle
import com.andhab.cubelens.core.cube.ColorScheme
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

/**
 * The six faces of [cube] (odd size) as someone scans them who follows the color names of the
 * guided steps rather than the turns: for each step, the cube held with the step's standard color
 * in front and its standard top color on top (or any top, where the cube can't be held that way),
 * the front face photographed. Clean samples in the stock colors.
 */
internal fun colorGuidedScansOf(cube: NxNCube): List<List<StickerSample>> {
    val n = cube.n
    fun center(of: NxNCube, face: Face) = of[of.geometry.index(face, n / 2, n / 2)]
    val holds = (0 until 64).map { k ->
        val moves = listOf(Face.R to k % 4, Face.U to k / 4 % 4, Face.F to k / 16).filter { it.second > 0 }.map { (face, turns) -> LayerMove(face, 1, n, turns) }
        cube.apply(moves)
    }
    val samples = CubeColor.entries.associateWith { StickerSample.ofArgb(CubePaletteEstimate.STANDARD.colors.getValue(it)) }
    val standard = ColorScheme.STANDARD
    return AppViewModel.ScanOrder.map { face ->
        val front = standard.colorOf(face)
        val top = standard.colorOf(
            when (face) {
                Face.U -> Face.B
                Face.D -> Face.F
                else -> Face.U
            },
        )
        val held = holds.firstOrNull { center(it, Face.F) == front && center(it, Face.U) == top } ?: holds.first { center(it, Face.F) == front }
        held.face(Face.F).map { samples.getValue(it) }
    }
}

/** A copy of [handle]'s values, as a new process gets them back after process death. */
internal fun afterProcessDeath(handle: SavedStateHandle): SavedStateHandle =
    SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) })

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
