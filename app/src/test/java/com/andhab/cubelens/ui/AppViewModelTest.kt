package com.andhab.cubelens.ui

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.ui.review.ReviewCheck
import com.andhab.cubelens.ui.review.ReviewSource
import com.andhab.cubelens.ui.theme.StickerPalette
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** Navigation, editing, solving and size handling of [AppViewModel] (mostly on a 3×3). */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val userCube = NxNCube.of(3, UserCubeColors)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun startsOnHomeAndWarmsUpTheSolverForThePickedSize() = runTest(dispatcher) {
        val solver = FakeSolver()
        val vm = viewModel(solver)
        assertEquals(Screen.Home, vm.state.value.screen)
        assertEquals(DEFAULT_CUBE_SIZE, vm.state.value.size)
        assertFalse(vm.state.value.solverReady)
        advanceUntilIdle()
        assertTrue(vm.state.value.solverReady)
        assertEquals(listOf("prepare", "prepareSize(3)"), solver.calls)
    }

    @Test
    fun aFailedWarmUpIsNotFatal() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver(prepareError = IllegalStateException("disk full")))
        advanceUntilIdle()
        assertFalse(vm.state.value.solverReady)
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun theRememberedSizeComesBack() = runTest(dispatcher) {
        val store = InMemoryCubeSizeStore(5)
        val solver = FakeSolver()
        val vm = viewModel(solver, store)
        assertEquals(5, vm.state.value.size)
        advanceUntilIdle()
        assertEquals(listOf("prepare", "prepareSize(5)"), solver.calls)
    }

    @Test
    fun pickingASizeRemembersItAndWarmsItUp() = runTest(dispatcher) {
        val store = InMemoryCubeSizeStore()
        val solver = FakeSolver()
        val vm = viewModel(solver, store)
        advanceUntilIdle()

        vm.selectSize(6)
        vm.selectSize(4) // changed their mind before 6×6 was ready: only 4×4 is prepared
        advanceUntilIdle()
        assertEquals(4, vm.state.value.size)
        assertEquals(4, store.load())
        assertEquals(listOf("prepare", "prepareSize(3)", "prepareSize(4)"), solver.calls)

        vm.openScan()
        assertEquals(Screen.Scan(size = 4), vm.state.value.screen)
        vm.selectSize(2) // only Home picks sizes
        assertEquals(4, vm.state.value.size)
    }

    @Test
    fun pickingASizeDropsAScrambleOfTheOldSize() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.playRandomScramble()
        assertTrue(vm.state.value.scrambling)
        vm.selectSize(2)
        advanceUntilIdle()
        assertFalse(vm.state.value.scrambling)
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun backNavigation() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        assertFalse("back on Home leaves the app", vm.back())

        vm.openScan()
        assertEquals(Screen.Scan(size = 3), vm.state.value.screen)
        assertTrue(vm.back())
        assertEquals(Screen.Home, vm.state.value.screen)

        vm.openManualEntry()
        assertTrue(vm.state.value.screen is Screen.Review)
        assertTrue("nothing entered yet, so nothing to lose", vm.back())
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun backFromAReviewWithWorkInItAsksFirst() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openManualEntry()
        vm.onColorTap(CubeColor.RED)

        assertTrue(vm.back())
        assertTrue("asks before dropping the cube", vm.review().confirmingLeave)
        assertTrue(vm.back())
        assertFalse("back again keeps editing", vm.review().confirmingLeave)
        assertEquals(CubeColor.RED, vm.review().colors[0])

        vm.back()
        vm.keepEditing()
        assertFalse(vm.review().confirmingLeave)

        vm.back()
        vm.leaveReview()
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun backFromAScannedReviewAsksEvenWithoutEdits() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        assertFalse(vm.review().canUndo)
        vm.back()
        assertTrue(vm.review().confirmingLeave)
    }

    @Test
    fun scanningAgainAndBackingOutKeepsTheReview() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        vm.onStickerTap(10)
        vm.onColorTap(CubeColor.WHITE)
        val edited = vm.review()

        vm.rescan()
        val scan = vm.state.value.screen as Screen.Scan
        assertEquals(3, scan.size)
        assertEquals(edited.colors, scan.returnTo?.review?.colors)
        assertTrue(vm.back())
        assertEquals("back from the camera returns to the edited review", edited.colors, vm.review().colors)
        assertTrue(vm.review().canUndo)

        // Typing colors in instead of scanning also returns to that review, ready to edit by hand.
        vm.rescan()
        vm.openManualEntry()
        assertEquals(edited.colors, vm.review().colors)
        assertEquals(ReviewSource.Scan, vm.review().source)
    }

    @Test
    fun aNewScanReplacesTheReviewItWasStartedFrom() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        vm.onStickerTap(10)
        vm.onColorTap(CubeColor.WHITE)
        vm.rescan()
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        assertEquals(UserCubeColors, vm.review().colors)
        assertFalse(vm.review().canUndo)
    }

    @Test
    fun actionsOfOtherScreensAreIgnored() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openManualEntry()
        val review = vm.state.value.screen
        // E.g. a late tap on Home's buttons while the review slides in.
        vm.openScan()
        vm.playRandomScramble()
        vm.onSolveDone()
        assertEquals(review, vm.state.value.screen)
        assertFalse(vm.state.value.scrambling)
    }

    @Test
    fun manualEntryFromTheCameraUsesTheScannedSize() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.selectSize(5)
        vm.openScan()
        vm.openManualEntry() // e.g. the camera is not available
        assertEquals(5, vm.review().n)
        assertEquals(ReviewSource.Manual, vm.review().source)
    }

    @Test
    fun scanningResolvesIntoAValidReview() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scansOf(userCube))
        assertEquals("resolving runs in the background", Screen.Scan(size = 3), vm.state.value.screen)
        advanceUntilIdle()
        val review = vm.review()
        assertEquals(ReviewSource.Scan, review.source)
        assertEquals(UserCubeColors, review.colors)
        assertEquals(ReviewCheck.Valid, review.check)
        assertEquals(0, review.straightenedFaces)
        assertEquals("a stock cube keeps the stock colors", StickerPalette.Standard, vm.state.value.palette)
    }

    @Test
    fun facesHeldAtAnAngleAreStraightened() = runTest(dispatcher) {
        val scans = scansOf(userCube).toMutableList()
        // The right face was captured a quarter turn clockwise.
        val right = AppViewModel.ScanOrder.indexOf(Face.R)
        val asSeen = scans[right]
        scans[right] = listOf(6, 3, 0, 7, 4, 1, 8, 5, 2).map { asSeen[it] }
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scans)
        advanceUntilIdle()
        val review = vm.review()
        assertEquals(UserCubeColors, review.colors)
        assertEquals(1, review.straightenedFaces)
        assertTrue(review.canSolve)
    }

    @Test
    fun aPastelCubeIsDrawnInItsOwnColorsUntilItIsDone() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scansOf(userCube, PastelLook))
        advanceUntilIdle()
        val review = vm.state.value.screen as Screen.Review
        assertEquals(UserCubeColors, review.review.colors)
        val pastel = vm.state.value.palette
        assertNotEquals(StickerPalette.Standard, pastel)
        assertEquals(pastel, review.palette)

        // Scanning again keeps the colors while the review waits; so does playing the solution.
        vm.rescan()
        assertEquals(pastel, vm.state.value.palette)
        vm.back()
        vm.solve()
        advanceUntilIdle()
        val solve = vm.state.value.screen as Screen.Solve
        assertEquals(pastel, solve.palette)
        vm.back()
        assertEquals(pastel, vm.state.value.palette)
        vm.solve()
        advanceUntilIdle()

        // Done: every other cube is drawn in the stock colors again.
        vm.onSolveDone()
        assertEquals(StickerPalette.Standard, vm.state.value.palette)
        vm.openManualEntry()
        assertEquals(StickerPalette.Standard, vm.state.value.palette)
        vm.back()
        vm.playRandomScramble()
        advanceUntilIdle()
        assertTrue(vm.state.value.screen is Screen.Solve)
        assertEquals(StickerPalette.Standard, vm.state.value.palette)
        vm.back()
        vm.openScan()
        assertEquals(StickerPalette.Standard, vm.state.value.palette)
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        assertEquals("a new, stock cube", StickerPalette.Standard, vm.state.value.palette)
    }

    @Test
    fun scansArriveOnlyWhileScanning() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun editingTheReview() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openManualEntry()
        vm.onColorTap(CubeColor.RED)
        vm.onColorTap(CubeColor.BLUE)
        assertEquals(listOf(CubeColor.RED, CubeColor.BLUE), vm.review().colors.subList(0, 2))
        assertEquals(2, vm.review().selected)
        vm.undo()
        assertNull(vm.review().colors[1])
        assertEquals(1, vm.review().selected)
        vm.onStickerTap(1) // deselect
        vm.onColorTap(CubeColor.GREEN) // pick up a brush
        vm.onStickerTap(10)
        assertEquals(CubeColor.GREEN, vm.review().colors[10])
    }

    @Test
    fun aBigCubeIsEditedFaceByFace() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.selectSize(4)
        vm.openManualEntry()
        assertEquals("manual entry opens the top face right away", Face.U, vm.review().focusedFace)
        repeat(16) { vm.onColorTap(CubeColor.WHITE) }
        assertEquals("filling the top face moves on to the left face", Face.L, vm.review().focusedFace)

        vm.onFaceStep(1)
        assertEquals(Face.F, vm.review().focusedFace)
        vm.onFaceStep(-2)
        assertEquals(Face.U, vm.review().focusedFace)

        assertTrue(vm.back())
        assertNull("back closes the face first", vm.review().focusedFace)
        assertFalse(vm.review().confirmingLeave)
        vm.onFaceTap(Face.D)
        assertEquals(Face.D, vm.review().focusedFace)
        vm.onFaceClose()
        assertNull(vm.review().focusedFace)
        vm.back()
        assertTrue("then asks before dropping the work", vm.review().confirmingLeave)
    }

    @Test
    fun solvingPlaysAWorkingSolutionAndBackReturnsToTheReview() = runTest(dispatcher) {
        val vm = viewModel(NxNCubeSolver(cacheDir = null))
        vm.openScan()
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        val before = vm.review()

        vm.solve()
        assertTrue("the button shows progress", vm.review().solving)
        advanceUntilIdle()

        val solve = vm.state.value.screen as Screen.Solve
        assertEquals(UserCubeColors, solve.startColors)
        assertTrue("${solve.solution.moves} should solve the cube", userCube.apply(solve.solution.moves).isSolved)
        assertTrue(solve.solution.length > 0)

        assertTrue(vm.back())
        val after = vm.review()
        assertEquals(before.colors, after.colors)
        assertFalse(after.solving)
    }

    @Test
    fun solvingWaitsForTheWarmUp() = runTest(dispatcher) {
        val solver = FakeSolver()
        val vm = viewModel(solver)
        vm.openScan()
        vm.onScanned(scansOf(userCube))
        vm.solve() // still scanning: ignored
        advanceUntilIdle()
        assertEquals(listOf("prepare", "prepareSize(3)"), solver.calls)
        vm.solve()
        advanceUntilIdle()
        assertEquals(listOf("prepare", "prepareSize(3)", "solve"), solver.calls)
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
        assertFalse("solve" in solver.calls)
    }

    @Test
    fun aFailedSolveShowsAFriendlyMessage() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver(solveError = IllegalStateException("bug")))
        vm.openScan()
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        vm.solve()
        advanceUntilIdle()
        assertTrue(vm.state.value.screen is Screen.Review)
        assertFalse(vm.review().solving)
        assertEquals(AppMessage.SolveFailed, vm.state.value.message)
        vm.dismissMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun backWhileSolvingCancelsTheSolveAndAsks() = runTest(dispatcher) {
        val solver = FakeSolver()
        val vm = viewModel(solver)
        vm.openScan()
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        vm.solve()
        vm.back()
        advanceUntilIdle()
        assertTrue(vm.state.value.screen is Screen.Review)
        assertFalse(vm.review().solving)
        assertTrue(vm.review().confirmingLeave)
        assertFalse("the solve never ran", "solve" in solver.calls)
        vm.leaveReview()
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun rescanAndFinish() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.openScan()
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        vm.rescan()
        assertTrue(vm.state.value.screen is Screen.Scan)
        vm.onScanned(scansOf(userCube))
        advanceUntilIdle()
        vm.solve()
        advanceUntilIdle()
        vm.onSolveDone()
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

    private fun TestScope.viewModel(solver: CubeSolver, store: CubeSizeStore = InMemoryCubeSizeStore()) =
        AppViewModel(solver, store, workDispatcher = StandardTestDispatcher(testScheduler), random = Random(42))
}
