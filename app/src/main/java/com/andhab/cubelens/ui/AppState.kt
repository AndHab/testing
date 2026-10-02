package com.andhab.cubelens.ui

import androidx.compose.runtime.Immutable
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.nxn.NxNSolution
import com.andhab.cubelens.ui.review.ReviewState
import com.andhab.cubelens.ui.theme.StickerPalette

/** The screen the app shows, with everything that screen needs. */
@Immutable
sealed interface Screen {
    /** Position in the forward flow (Home → Scan → Review → Solve); drives transition direction. */
    val depth: Int

    /**
     * The sticker colors of the cube this screen shows: the user's own (e.g. pastel) colors for a
     * scanned cube that doesn't look standard, the stock colors otherwise.
     */
    val palette: StickerPalette get() = StickerPalette.Standard

    /** The landing screen. */
    data object Home : Screen {
        override val depth = 0
    }

    /**
     * Scanning the six faces of an [size]×[size] cube with the camera.
     *
     * @param returnTo the review this scan was started from ("Scan again"), which back returns to
     *   with every edit intact; `null` when scanning from Home. Scanning again shows the same cube,
     *   so the scan is drawn in that review's [palette].
     */
    data class Scan(val size: Int = DEFAULT_CUBE_SIZE, val returnTo: Review? = null) : Screen {
        override val depth = 1
        override val palette: StickerPalette get() = returnTo?.palette ?: StickerPalette.Standard
    }

    /** Checking and fixing the colors before solving, drawn in [palette]. */
    data class Review(
        val review: ReviewState,
        override val palette: StickerPalette = StickerPalette.Standard,
    ) : Screen {
        override val depth = 2
    }

    /**
     * Playing back a solution.
     *
     * @param startColors the scrambled cube, 6·N² colors in [com.andhab.cubelens.core.nxn.NxNGeometry]
     *   order, as it was scanned or entered.
     * @param solution the solution; applying its moves to [startColors] gives a solved cube.
     * @param returnTo where back leads: the review the cube came from, or `null` (Home) for a
     *   random scramble.
     * @param palette the colors the cube is drawn in.
     */
    data class Solve(
        val startColors: List<CubeColor>,
        val solution: NxNSolution,
        val returnTo: Review?,
        override val palette: StickerPalette = returnTo?.palette ?: StickerPalette.Standard,
    ) : Screen {
        override val depth = 3
    }
}

/** A friendly, transient message about something that went wrong. */
enum class AppMessage {
    /** Turning the camera scans into a cube failed. */
    ScanFailed,

    /** Finding a solution for the user's cube failed. */
    SolveFailed,

    /** Mixing up or solving a random scramble failed. */
    ScrambleFailed,
}

/**
 * The whole UI state of the app.
 *
 * @property screen what is on screen.
 * @property size the cube size picked on Home (2 for a 2×2 … 7 for a 7×7): what a new scan, manual
 *   entry or random scramble starts with.
 * @property solverReady the solver's tables are loaded, so solving starts right away.
 * @property scrambling a random scramble is being mixed up and solved (Home shows progress).
 * @property message a message to show briefly, until [AppViewModel.dismissMessage].
 */
@Immutable
data class AppUiState(
    val screen: Screen = Screen.Home,
    val size: Int = DEFAULT_CUBE_SIZE,
    val solverReady: Boolean = false,
    val scrambling: Boolean = false,
    val message: AppMessage? = null,
) {
    /**
     * The sticker colors of the cube in play: the scanned cube's own colors while its review or
     * solution is on screen (or waits behind "Scan again"), the stock colors everywhere else.
     */
    val palette: StickerPalette get() = screen.palette
}
