package com.andhab.cubelens.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.solver.Scrambler
import com.andhab.cubelens.core.vision.ScanResolver
import com.andhab.cubelens.core.vision.StickerSample
import com.andhab.cubelens.ui.review.ReviewState
import com.andhab.cubelens.ui.review.clearTools
import com.andhab.cubelens.ui.review.tapColor
import com.andhab.cubelens.ui.review.tapSticker
import com.andhab.cubelens.ui.review.undo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * Holds the app's navigation and the state of the current screen in one immutable [AppUiState],
 * and runs the heavy work (resolving scans, solving) off the main thread.
 *
 * On creation it starts warming up the [solver] in the background, so that by the time the user
 * has scanned a cube the solver answers instantly. Solving before the warm-up has finished simply
 * waits for it.
 *
 * @param workDispatcher where CPU-heavy work runs.
 * @param random source of random scrambles.
 */
class AppViewModel(
    private val solver: CubeSolver,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(AppUiState())

    /** The current UI state. */
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val warmUp: Job = viewModelScope.launch {
        val ready = try {
            withContext(workDispatcher) { solver.prepare() }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not fatal: solving prepares the tables again if needed (without the cache file).
            false
        }
        _state.update { it.copy(solverReady = ready) }
    }

    /** The running scan resolution, solve or scramble, if any. */
    private var work: Job? = null

    /** Home → camera scan. */
    fun openScan() {
        cancelWork()
        _state.update { it.copy(screen = Screen.Scan, scrambling = false) }
    }

    /** Home or Scan → manual entry on a blank cube. */
    fun openManualEntry() {
        cancelWork()
        _state.update { it.copy(screen = Screen.Review(ReviewState.manual()), scrambling = false) }
    }

    /** Review → scan the cube again. */
    fun rescan() {
        if (_state.value.screen !is Screen.Review) return
        cancelWork()
        _state.update { it.copy(screen = Screen.Scan) }
    }

    /**
     * The camera delivered all six faces: resolves them into a cube (in the background) and moves on
     * to the review, even when the colors are not a valid cube yet; the review shows what to fix.
     */
    fun onScanned(scans: List<List<StickerSample>>) {
        if (_state.value.screen != Screen.Scan) return
        cancelWork()
        work = viewModelScope.launch {
            val analysis = withContext(workDispatcher) { ScanResolver.resolve(scans) }
            _state.update {
                if (it.screen == Screen.Scan) it.copy(screen = Screen.Review(ReviewState.fromScan(analysis))) else it
            }
        }
    }

    /** A tap on sticker [index] on the review screen; see [tapSticker]. */
    fun onStickerTap(index: Int) = updateReview { it.tapSticker(index) }

    /** A tap on [color] in the review palette; see [tapColor]. */
    fun onColorTap(color: CubeColor) = updateReview { it.tapColor(color) }

    /** Takes back the last edit on the review screen. */
    fun undo() = updateReview { it.undo() }

    /**
     * Review → solve the cube and play the solution. Does nothing unless the colors form a valid
     * cube. The review shows a busy button meanwhile; failures leave it with a friendly message.
     */
    fun solve() {
        val review = (_state.value.screen as? Screen.Review)?.review ?: return
        if (review.solving || !review.canSolve) return
        val colors = review.colors.requireNoNulls()
        val cube = FaceletCube.fromColors(colors) ?: return
        cancelWork()
        updateReview { it.clearTools().copy(solving = true) }
        work = viewModelScope.launch {
            try {
                val moves = solution(cube)
                _state.update { state ->
                    val current = state.screen as? Screen.Review ?: return@update state
                    val returnTo = Screen.Review(current.review.copy(solving = false))
                    state.copy(screen = Screen.Solve(colors, moves, returnTo))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateReview { it.copy(solving = false) }
                _state.update { it.copy(message = AppMessage.SolveFailed) }
            }
        }
    }

    /** Home → mix up a random cube, solve it and go straight to playback. */
    fun playRandomScramble() {
        val state = _state.value
        if (state.screen != Screen.Home || state.scrambling) return
        cancelWork()
        _state.update { it.copy(scrambling = true) }
        work = viewModelScope.launch {
            try {
                val cube = withContext(workDispatcher) { Scrambler.randomState(random) }
                val moves = solution(cube)
                _state.update {
                    if (it.screen == Screen.Home) {
                        it.copy(screen = Screen.Solve(cube.toColors(), moves, returnTo = null), scrambling = false)
                    } else {
                        it.copy(scrambling = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(scrambling = false, message = AppMessage.ScrambleFailed) }
            }
        }
    }

    /** Solve → the user finished (or wants another cube): back to Home. */
    fun onSolveDone() {
        cancelWork()
        _state.update { it.copy(screen = Screen.Home) }
    }

    /**
     * System back: Solve → Review (or Home after a random scramble), Review → Home, Scan → Home.
     *
     * @return false on Home, where back should leave the app.
     */
    fun back(): Boolean {
        val screen = _state.value.screen
        val target = when (screen) {
            Screen.Home -> return false
            Screen.Scan, is Screen.Review -> Screen.Home
            is Screen.Solve -> screen.returnTo ?: Screen.Home
        }
        cancelWork()
        _state.update { it.copy(screen = target, scrambling = false) }
        return true
    }

    /** Hides the current [AppUiState.message]. */
    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Waits for the warm-up, then solves [cube] on the work dispatcher (cancellable). */
    private suspend fun solution(cube: FaceletCube): List<Move> {
        warmUp.join()
        return runInterruptible(workDispatcher) { solver.solve(cube) }
    }

    private fun updateReview(transform: (ReviewState) -> ReviewState) = _state.update { state ->
        val screen = state.screen as? Screen.Review ?: return@update state
        state.copy(screen = Screen.Review(transform(screen.review)))
    }

    private fun cancelWork() {
        work?.cancel()
        work = null
    }

    companion object {
        /** Name of the solver's table cache in the app's files directory. */
        const val SOLVER_CACHE_FILE = "solver-tables.bin"

        /** Creates the view model with the real solver, caching its tables in the app's files. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
                    "AppViewModel needs the Application in its creation extras"
                }
                AppViewModel(TwoPhaseCubeSolver(File(application.filesDir, SOLVER_CACHE_FILE)))
            }
        }
    }
}
