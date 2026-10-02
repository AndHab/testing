package com.andhab.cubelens.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.core.nxn.NxNSolution
// A star import: N×N scan resolution is an extension today and becomes a member of ScanResolver
// later; this resolves to either without change.
import com.andhab.cubelens.core.vision.*
import com.andhab.cubelens.ui.review.ReviewState
import com.andhab.cubelens.ui.review.clearTools
import com.andhab.cubelens.ui.review.closeFace
import com.andhab.cubelens.ui.review.focusFace
import com.andhab.cubelens.ui.review.stepFace
import com.andhab.cubelens.ui.review.tapColor
import com.andhab.cubelens.ui.review.tapSticker
import com.andhab.cubelens.ui.review.undo
import com.andhab.cubelens.ui.theme.StickerPalette
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
import kotlin.random.Random

/**
 * Holds the app's navigation and the state of the current screen in one immutable [AppUiState],
 * and runs the heavy work (resolving scans, solving) off the main thread. Works for every cube
 * size in [SupportedCubeSizes]: the size picked on Home (remembered in [sizeStore]) decides what a
 * new scan, manual entry or random scramble starts with.
 *
 * On creation it starts warming up the [solver] in the background (and then whatever the picked
 * size needs on top), so that by the time the user has scanned a cube the solver answers quickly.
 * Solving before the warm-up has finished simply waits for it.
 *
 * A scanned cube that doesn't look standard (e.g. pastel stickers) keeps its own colors as the
 * [StickerPalette] of its review and solution screens; everything else uses the stock colors.
 *
 * @param sizeStore where the picked cube size is remembered across launches.
 * @param workDispatcher where CPU-heavy work runs.
 * @param random source of random scrambles.
 */
class AppViewModel(
    private val solver: CubeSolver,
    private val sizeStore: CubeSizeStore = InMemoryCubeSizeStore(),
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val random: Random = Random.Default,
) : ViewModel() {

    private val _state = MutableStateFlow(AppUiState(size = sizeStore.load() ?: DEFAULT_CUBE_SIZE))

    /** The current UI state. */
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val warmUp: Job = viewModelScope.launch {
        val ready = try {
            runInterruptible(workDispatcher) { solver.prepare() }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Not fatal: solving prepares the tables again if needed (without the cache file).
            false
        }
        _state.update { it.copy(solverReady = ready) }
    }

    /** Prepares what the picked size needs on top of [warmUp]; replaced when another size is picked. */
    private var sizeWarmUp: Job? = null

    /** The size [sizeWarmUp] prepares. */
    private var sizeWarmingUp = 0

    /** The running scan resolution, solve or scramble, if any. */
    private var work: Job? = null

    init {
        warmUpSize(_state.value.size)
    }

    /**
     * Home → picks the cube size to scan, enter or scramble next, remembers it and prepares the
     * solver for it in the background. A random scramble being mixed up for the old size is
     * dropped.
     */
    fun selectSize(size: Int) {
        require(size in SupportedCubeSizes) { "Unsupported cube size $size" }
        val state = _state.value
        if (state.screen != Screen.Home || size == state.size) return
        cancelWork()
        _state.update { it.copy(size = size, scrambling = false) }
        sizeStore.save(size)
        warmUpSize(size)
    }

    /** Home → camera scan of a cube of the picked size. */
    fun openScan() {
        val state = _state.value
        if (state.screen != Screen.Home) return
        cancelWork()
        _state.update { it.copy(screen = Screen.Scan(size = state.size), scrambling = false) }
    }

    /**
     * Home or Scan → manual entry on a blank cube of the picked (or scanned) size. When the scan was
     * started from a review ("Scan again"), that review comes back instead: its colors can be edited
     * by hand right there.
     */
    fun openManualEntry() {
        val state = _state.value
        val target = when (val screen = state.screen) {
            Screen.Home -> Screen.Review(ReviewState.manual(state.size))
            is Screen.Scan -> screen.returnTo ?: Screen.Review(ReviewState.manual(screen.size))
            else -> return
        }
        cancelWork()
        _state.update { it.copy(screen = target, scrambling = false) }
    }

    /** Review → scan the cube again; backing out of the camera returns to this review, edits intact. */
    fun rescan() {
        val screen = _state.value.screen as? Screen.Review ?: return
        cancelWork()
        val review = screen.review.clearTools().copy(solving = false, confirmingLeave = false)
        val returnTo = screen.copy(review = review)
        _state.update { it.copy(screen = Screen.Scan(size = review.n, returnTo = returnTo)) }
    }

    /**
     * The camera delivered all six faces (in the guided order front, right, back, left, top,
     * bottom): resolves them into a cube (in the background) and moves on to the review, even when
     * the colors are not a valid cube yet; the review shows what to fix. A cube with unusual
     * colors is drawn in its own colors from here on. A new scan replaces the review it was started
     * from, if any; if the scans can't be read at all, back leads where it would and a message says
     * so.
     */
    fun onScanned(scans: List<List<StickerSample>>) {
        val scan = _state.value.screen as? Screen.Scan ?: return
        cancelWork()
        work = viewModelScope.launch {
            val target = try {
                val analysis = runInterruptible(workDispatcher) { ScanResolver.resolve(scan.size, scans, ScanOrder) }
                Screen.Review(ReviewState.fromScan(analysis), paletteOf(analysis.palette))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _state.update {
                when {
                    it.screen !is Screen.Scan -> it
                    target != null -> it.copy(screen = target)
                    else -> it.copy(screen = scan.returnTo ?: Screen.Home, message = AppMessage.ScanFailed)
                }
            }
        }
    }

    /** A tap on sticker [index] on the review screen; see [tapSticker]. */
    fun onStickerTap(index: Int) = updateReview { it.tapSticker(index) }

    /** A tap on [color] in the review palette; see [tapColor]. */
    fun onColorTap(color: CubeColor) = updateReview { it.tapColor(color) }

    /** A tap on [face] of a big cube's net: opens it in the face editor; see [focusFace]. */
    fun onFaceTap(face: Face) = updateReview { it.focusFace(face) }

    /** Moves the face editor on to the next (+1) or previous (-1) face; see [stepFace]. */
    fun onFaceStep(steps: Int) = updateReview { it.stepFace(steps) }

    /** Closes the face editor. */
    fun onFaceClose() = updateReview { it.closeFace() }

    /** Takes back the last edit on the review screen. */
    fun undo() = updateReview { it.undo() }

    /**
     * Review → solve the cube and play the solution. Does nothing unless the colors form a valid
     * cube. The review shows a busy button meanwhile; back cancels the solve; failures leave the
     * review with a friendly message.
     */
    fun solve() {
        val review = (_state.value.screen as? Screen.Review)?.review ?: return
        if (review.solving || !review.canSolve) return
        val colors = review.colors.requireNoNulls()
        val cube = NxNCube.of(review.n, colors)
        cancelWork()
        updateReview { it.clearTools().copy(solving = true) }
        work = viewModelScope.launch {
            try {
                val solution = solution(cube)
                _state.update { state ->
                    val current = state.screen as? Screen.Review ?: return@update state
                    val returnTo = current.copy(review = current.review.copy(solving = false))
                    state.copy(screen = Screen.Solve(colors, solution, returnTo, current.palette))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateReview { it.copy(solving = false) }
                _state.update { it.copy(message = AppMessage.SolveFailed) }
            }
        }
    }

    /** Home → mix up a random cube of the picked size, solve it and go straight to playback. */
    fun playRandomScramble() {
        val state = _state.value
        if (state.screen != Screen.Home || state.scrambling) return
        val size = state.size
        cancelWork()
        _state.update { it.copy(scrambling = true) }
        work = viewModelScope.launch {
            try {
                val cube = runInterruptible(workDispatcher) { scrambled(size) }
                val solution = solution(cube)
                _state.update {
                    if (it.screen == Screen.Home) {
                        it.copy(screen = Screen.Solve(cube.toColors(), solution, returnTo = null), scrambling = false)
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
        if (_state.value.screen !is Screen.Solve) return
        cancelWork()
        _state.update { it.copy(screen = Screen.Home) }
    }

    /**
     * System back (and the top bar's back button):
     *  - Solve → the review it came from (Home after a random scramble);
     *  - Scan → the review it was started from, or Home;
     *  - Review → closes the face editor if it is open; otherwise Home, but when that would throw
     *    away work (any edit, or a scan) it first asks (see [ReviewState.confirmingLeave]); back
     *    while asking means "keep editing".
     *
     * @return false on Home, where back should leave the app.
     */
    fun back(): Boolean {
        val target = when (val screen = _state.value.screen) {
            Screen.Home -> return false
            is Screen.Scan -> screen.returnTo ?: Screen.Home
            is Screen.Review -> {
                val review = screen.review
                when {
                    review.confirmingLeave -> screen.copy(review = review.copy(confirmingLeave = false))
                    review.focusedFace != null -> screen.copy(review = review.closeFace())
                    review.hasWorkToLose -> screen.copy(review = review.copy(solving = false, confirmingLeave = true))
                    else -> Screen.Home
                }
            }
            is Screen.Solve -> screen.returnTo ?: Screen.Home
        }
        cancelWork()
        _state.update { it.copy(screen = target, scrambling = false) }
        return true
    }

    /** Review → Home, dropping the cube: the answer to "Leave this cube?". */
    fun leaveReview() {
        if (_state.value.screen !is Screen.Review) return
        cancelWork()
        _state.update { it.copy(screen = Screen.Home) }
    }

    /** Stays on the review: the other answer to "Leave this cube?". */
    fun keepEditing() = updateReview { it.copy(confirmingLeave = false) }

    /** Hides the current [AppUiState.message]. */
    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Prepares the solver for [size] once the general warm-up is done, replacing an earlier one. */
    private fun warmUpSize(size: Int) {
        sizeWarmUp?.cancel()
        sizeWarmingUp = size
        sizeWarmUp = viewModelScope.launch {
            warmUp.join()
            try {
                runInterruptible(workDispatcher) { solver.prepareSize(size) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not fatal: the first solve of this size prepares it instead.
            }
        }
    }

    /**
     * Waits for the warm-up (and for that of [cube]'s size, if it is under way), then solves [cube]
     * on the work dispatcher; cancelling the caller stops the solver.
     */
    private suspend fun solution(cube: NxNCube): NxNSolution {
        warmUp.join()
        if (sizeWarmingUp == cube.n) sizeWarmUp?.join()
        return runInterruptible(workDispatcher) { solver.solve(cube) }
    }

    /** A random-move scramble of an [size]×[size] cube (never one that happens to be solved). */
    private fun scrambled(size: Int): NxNCube {
        val solved = NxNCube.solved(size)
        while (true) {
            val cube = solved.apply(NxNScrambler.randomMoves(size, random))
            if (!cube.isSolved) return cube
        }
    }

    private fun updateReview(transform: (ReviewState) -> ReviewState) = _state.update { state ->
        val screen = state.screen as? Screen.Review ?: return@update state
        state.copy(screen = screen.copy(review = transform(screen.review)))
    }

    private fun cancelWork() {
        work?.cancel()
        work = null
    }

    companion object {
        /**
         * The order the scan flow asks for the faces in: front, then turning right (right, back,
         * left), then top and bottom.
         */
        val ScanOrder: List<Face> = listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D)

        /** The colors to draw a scanned cube in: its own when they don't look standard, else the stock ones. */
        fun paletteOf(estimate: CubePaletteEstimate): StickerPalette =
            if (estimate.isStandardLike) StickerPalette.Standard else StickerPalette.fromArgb(estimate.colors)

        /** Creates the view model with the real solver (caching its tables in the app's files) and size memory. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) {
                    "AppViewModel needs the Application in its creation extras"
                }
                AppViewModel(
                    solver = NxNCubeSolver(application.filesDir),
                    sizeStore = PreferencesCubeSizeStore(application),
                )
            }
        }
    }
}
