package com.andhab.cubelens.ui

import androidx.compose.runtime.Immutable
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.review.ReviewState

/** The screen the app shows, with everything that screen needs. */
@Immutable
sealed interface Screen {
    /** Position in the forward flow (Home → Scan → Review → Solve); drives transition direction. */
    val depth: Int

    /** The landing screen. */
    data object Home : Screen {
        override val depth = 0
    }

    /** Scanning the six faces with the camera. */
    data object Scan : Screen {
        override val depth = 1
    }

    /** Checking and fixing the colors before solving. */
    data class Review(val review: ReviewState) : Screen {
        override val depth = 2
    }

    /**
     * Playing back a solution.
     *
     * @param startColors the scrambled cube, 54 colors in facelet order (standard orientation).
     * @param moves the solution; applying them to [startColors] gives a solved cube.
     * @param returnTo where back leads: the review the cube came from, or `null` (Home) for a
     *   random scramble.
     */
    data class Solve(
        val startColors: List<CubeColor>,
        val moves: List<Move>,
        val returnTo: Review?,
    ) : Screen {
        override val depth = 3
    }
}

/** A friendly, transient message about something that went wrong. */
enum class AppMessage {
    /** Finding a solution for the user's cube failed. */
    SolveFailed,

    /** Mixing up or solving a random scramble failed. */
    ScrambleFailed,
}

/**
 * The whole UI state of the app.
 *
 * @property screen what is on screen.
 * @property solverReady the solver's tables are loaded, so solving starts right away.
 * @property scrambling a random scramble is being mixed up and solved (Home shows progress).
 * @property message a message to show briefly, until [AppViewModel.dismissMessage].
 */
@Immutable
data class AppUiState(
    val screen: Screen = Screen.Home,
    val solverReady: Boolean = false,
    val scrambling: Boolean = false,
    val message: AppMessage? = null,
)
