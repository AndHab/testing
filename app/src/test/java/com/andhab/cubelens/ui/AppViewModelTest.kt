package com.andhab.cubelens.ui

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.vision.ColorMath
import com.andhab.cubelens.core.vision.LiveClassifier
import com.andhab.cubelens.core.vision.StickerSample
import com.andhab.cubelens.ui.review.ReviewCheck
import com.andhab.cubelens.ui.review.ReviewSource
import com.andhab.cubelens.ui.review.ReviewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startsOnHomeAndWarmsUpTheSolver() = runTest(dispatcher) {
        val solver = FakeSolver()
        val vm = viewModel(solver)
        assertEquals(Screen.Home, vm.state.value.screen)
        assertFalse(vm.state.value.solverReady)
        advanceUntilIdle()
        assertTrue(vm.state.value.solverReady)
        assertEquals(listOf("prepare"), solver.calls)
    }

    @Test
    fun aFailedWarmUpIsNotFatal() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver(prepareError = IllegalStateException("disk full")))
        advanceUntilIdle()
        assertFalse(vm.state.value.solverReady)
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun backNavigation() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        assertFalse("back on Home leaves the app", vm.back())

        vm.openScan()
        assertEquals(Screen.Scan, vm.state.value.screen)
        assertTrue(vm.back())
        assertEquals(Screen.Home, vm.state.value.screen)

        vm.openManualEntry()
        assertTrue(vm.state.value.screen is Screen.Review)
        assertTrue(vm.back())
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun manualEntryStartsWithFixedCenters() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.openManualEntry() // e.g. the camera is not available
        val review = review(vm)
        assertEquals(ReviewSource.Manual, review.source)
        for (i in 0 until Facelets.COUNT) {
            val expected = if (ReviewState.isCenter(i)) ColorScheme.STANDARD.colorOf(Facelets.faceOf(i)) else null
            assertEquals(expected, review.colors[i])
        }
        assertEquals(ReviewCheck.Incomplete(48), review.check)
    }

    @Test
    fun scanningResolvesIntoAValidReview() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scansOf(UserCubeColors))
        assertEquals("resolving runs in the background", Screen.Scan, vm.state.value.screen)
        advanceUntilIdle()
        val review = review(vm)
        assertEquals(ReviewSource.Scan, review.source)
        assertEquals(UserCubeColors, review.colors)
        assertEquals(ReviewCheck.Valid, review.check)
        assertEquals(0, review.straightenedFaces)
    }

    @Test
    fun facesHeldAtAnAngleAreStraightened() = runTest(dispatcher) {
        val scans = scansOf(UserCubeColors).toMutableList()
        // The right face was captured a quarter turn clockwise.
        val asSeen = scans[Face.R.ordinal]
        scans[Face.R.ordinal] = listOf(6, 3, 0, 7, 4, 1, 8, 5, 2).map { asSeen[it] }
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scans.shuffled(Random(3)))
        advanceUntilIdle()
        val review = review(vm)
        assertEquals(UserCubeColors, review.colors)
        assertEquals(1, review.straightenedFaces)
        assertTrue(review.canSolve)
    }

    @Test
    fun scansArriveOnlyWhileScanning() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.onScanned(scansOf(UserCubeColors))
        advanceUntilIdle()
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun editingTheReview() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openManualEntry()
        vm.onColorTap(CubeColor.RED)
        vm.onColorTap(CubeColor.BLUE)
        assertEquals(listOf(CubeColor.RED, CubeColor.BLUE), review(vm).colors.subList(0, 2))
        assertEquals(2, review(vm).selected)
        vm.undo()
        assertNull(review(vm).colors[1])
        assertEquals(1, review(vm).selected)
        vm.onStickerTap(1) // deselect
        vm.onColorTap(CubeColor.GREEN) // pick up a brush
        vm.onStickerTap(10)
        assertEquals(CubeColor.GREEN, review(vm).colors[10])
    }

    @Test
    fun solvingPlaysAWorkingSolutionAndBackReturnsToTheReview() = runTest(dispatcher) {
        val vm = viewModel(TwoPhaseCubeSolver(cacheFile = null))
        vm.openScan()
        vm.onScanned(scansOf(UserCubeColors))
        advanceUntilIdle()
        val before = review(vm)

        vm.solve()
        assertTrue("the button shows progress", review(vm).solving)
        advanceUntilIdle()

        val solve = vm.state.value.screen as Screen.Solve
        assertEquals(UserCubeColors, solve.startColors)
        val start = checkNotNull(FaceletCube.fromColors(solve.startColors))
        assertTrue("${solve.moves} should solve the cube", start.apply(solve.moves).isSolved)
        assertTrue(solve.moves.isNotEmpty())

        assertTrue(vm.back())
        val after = review(vm)
        assertEquals(before.colors, after.colors)
        assertFalse(after.solving)
    }

    @Test
    fun solvingWaitsForTheWarmUp() = runTest(dispatcher) {
        val solver = FakeSolver()
        val vm = viewModel(solver)
        vm.openScan()
        vm.onScanned(scansOf(UserCubeColors))
        vm.solve() // still scanning: ignored
        advanceUntilIdle()
        assertEquals(listOf("prepare"), solver.calls)
        vm.solve()
        advanceUntilIdle()
        assertEquals(listOf("prepare", "solve"), solver.calls)
        assertTrue(vm.state.value.screen is Screen.Solve)
    }

    @Test
    fun solvingAnUnfinishedCubeIsIgnored() = runTest(dispatcher) {
        val solver = FakeSolver()
        val vm = viewModel(solver)
        vm.openManualEntry()
        vm.solve()
        advanceUntilIdle()
        assertTrue(vm.state.value.screen is Screen.Review)
        assertEquals(listOf("prepare"), solver.calls)
    }

    @Test
    fun aFailedSolveShowsAFriendlyMessage() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver(solveError = IllegalStateException("bug")))
        vm.openScan()
        vm.onScanned(scansOf(UserCubeColors))
        advanceUntilIdle()
        vm.solve()
        advanceUntilIdle()
        assertTrue(vm.state.value.screen is Screen.Review)
        assertFalse(review(vm).solving)
        assertEquals(AppMessage.SolveFailed, vm.state.value.message)
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun leavingWhileSolvingCancelsTheSolve() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scansOf(UserCubeColors))
        advanceUntilIdle()
        vm.solve()
        vm.back()
        advanceUntilIdle()
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun rescanAndFinish() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scansOf(UserCubeColors))
        advanceUntilIdle()
        vm.rescan()
        assertEquals(Screen.Scan, vm.state.value.screen)
        vm.onScanned(scansOf(UserCubeColors))
        advanceUntilIdle()
        vm.solve()
        advanceUntilIdle()
        vm.onSolveDone()
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun randomScrambleGoesStraightToPlayback() = runTest(dispatcher) {
        val vm = viewModel(TwoPhaseCubeSolver(cacheFile = null))
        vm.playRandomScramble()
        assertTrue(vm.state.value.scrambling)
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.scrambling)
        val solve = state.screen as Screen.Solve
        assertNull(solve.returnTo)
        val start = checkNotNull(FaceletCube.fromColors(solve.startColors))
        assertFalse(start.isSolved)
        assertTrue(start.apply(solve.moves).isSolved)
        assertTrue(vm.back())
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun aFailedScrambleShowsAFriendlyMessage() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver(solveError = IllegalStateException("bug")))
        vm.playRandomScramble()
        advanceUntilIdle()
        assertEquals(Screen.Home, vm.state.value.screen)
        assertFalse(vm.state.value.scrambling)
        assertEquals(AppMessage.ScrambleFailed, vm.state.value.message)
    }

    private fun TestScope.viewModel(solver: CubeSolver) =
        AppViewModel(solver, workDispatcher = StandardTestDispatcher(testScheduler), random = Random(42))

    private fun review(vm: AppViewModel): ReviewState = (vm.state.value.screen as Screen.Review).review

    /** Six scans of [colors] in facelet order, as clean samples of the classifier's reference colors. */
    private fun scansOf(colors: List<CubeColor>): List<List<StickerSample>> {
        val samples = CubeColor.entries.associateWith { StickerSample.ofArgb(ColorMath.labToArgb(LiveClassifier.reference.getValue(it))) }
        return colors.map { samples.getValue(it) }.chunked(9)
    }

    /** Records calls; solves with a fixed answer or fails as told. */
    private class FakeSolver(
        private val prepareError: Exception? = null,
        private val solveError: Exception? = null,
    ) : CubeSolver {
        val calls = mutableListOf<String>()

        override fun prepare() {
            calls += "prepare"
            prepareError?.let { throw it }
        }

        override fun solve(cube: FaceletCube): List<Move> {
            calls += "solve"
            solveError?.let { throw it }
            return listOf(Move.R1, Move.U1)
        }
    }
}
