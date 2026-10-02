package com.andhab.cubelens.ui

import androidx.lifecycle.SavedStateHandle
import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.core.nxn.PieceKind
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.random.Random

/** The whole flow (manual entry, scan, review, solve, random scramble) for cubes of each size [n]. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class AppViewModelSizesTest(private val n: Int) {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun manualEntryStartsBlankWithTheFixedCentersOfOddCubes() = runTest(dispatcher) {
        val vm = viewModel(FakeSolver())
        vm.selectSize(n)
        vm.openManualEntry()
        val review = vm.review()
        val geometry = NxNGeometry.of(n)
        assertEquals(n, review.n)
        assertEquals(ReviewSource.Manual, review.source)
        assertEquals(6 * n * n, review.colors.size)
        var locked = 0
        for (i in review.colors.indices) {
            val fixed = n % 2 == 1 && geometry.kindOf(i) == PieceKind.FIXED_CENTER
            if (fixed) locked++
            val expected = if (fixed) ColorScheme.STANDARD.colorOf(geometry.faceOf(i)) else null
            assertEquals("sticker $i", expected, review.colors[i])
            assertEquals("sticker $i", fixed, review.isLocked(i))
        }
        assertEquals(if (n % 2 == 1) 6 else 0, locked)
        assertEquals(ReviewCheck.Incomplete(6 * n * n - locked), review.check)
        assertEquals("the first sticker to fill is selected", review.entryOrder.first { !review.isLocked(it) }, review.selected)
        assertEquals(if (n >= 4) Face.U else null, review.focusedFace)
        assertEquals(StickerPalette.Standard, vm.state.value.palette)
    }

    @Test
    fun aScanResolvesIntoAValidReviewThatSolves() = runTest(dispatcher) {
        val cube = scrambledCube(n)
        val vm = viewModel(NxNCubeSolver(cacheDir = null))
        vm.selectSize(n)
        vm.openScan()
        assertEquals(Screen.Scan(size = n), vm.state.value.screen)
        vm.onScanned(scansOf(cube))
        advanceUntilIdle()

        val review = vm.review()
        assertEquals(ReviewSource.Scan, review.source)
        assertEquals(cube.toColors(), review.colors)
        assertEquals(ReviewCheck.Valid, review.check)
        assertEquals(StickerPalette.Standard, vm.state.value.palette)

        vm.solve()
        advanceUntilIdle()
        val solve = vm.state.value.screen as Screen.Solve
        assertEquals(cube.toColors(), solve.startColors)
        assertEquals(n, solve.solution.n)
        assertTrue("the solution solves the ${n}x$n cube", NxNCube.of(n, solve.startColors).apply(solve.solution.moves).isSolved)

        assertTrue(vm.back())
        assertEquals("back returns to the review", cube.toColors(), vm.review().colors)
        assertFalse(vm.review().solving)
    }

    @Test
    fun aRandomScrambleOfThePickedSizeGoesStraightToPlayback() = runTest(dispatcher) {
        val vm = viewModel(NxNCubeSolver(cacheDir = null))
        vm.selectSize(n)
        vm.playRandomScramble()
        assertTrue(vm.state.value.scrambling)
        advanceUntilIdle()
        val state = vm.state.value
        assertFalse(state.scrambling)
        val solve = state.screen as Screen.Solve
        assertNull(solve.returnTo)
        assertEquals(StickerPalette.Standard, solve.palette)
        val start = NxNCube.of(n, solve.startColors)
        assertFalse(start.isSolved)
        assertTrue(start.apply(solve.solution.moves).isSolved)
        assertTrue(vm.back())
        assertEquals(Screen.Home, vm.state.value.screen)
    }

    @Test
    fun everyScreenComesBackAfterProcessDeath() = runTest(dispatcher) {
        val cube = scrambledCube(n)
        val saved = SavedStateHandle()
        val vm = viewModel(NxNCubeSolver(cacheDir = null), saved)
        fun restored(): AppViewModel {
            val again = viewModel(FakeSolver(), afterProcessDeath(saved))
            assertEquals(vm.state.value.screen, again.state.value.screen)
            return again
        }
        vm.selectSize(n)
        vm.openScan()
        advanceUntilIdle()
        restored()

        vm.onScanned(scansOf(cube, PastelLook))
        advanceUntilIdle()
        // An edit, with its undo, and the brush picked up.
        val sticker = vm.review().entryOrder.first { !vm.review().isLocked(it) }
        vm.onStickerTap(sticker)
        vm.onColorTap(cube[sticker].let { color -> CubeColor.entries.first { it != color } })
        vm.onColorTap(CubeColor.BLUE)
        advanceUntilIdle()
        assertTrue(vm.state.value.palette != StickerPalette.Standard)
        val review = restored()
        assertTrue(review.review().canUndo)
        review.undo()
        assertEquals(cube.toColors(), review.review().colors)
        vm.undo()

        vm.rescan()
        advanceUntilIdle()
        assertTrue(restored().state.value.screen is Screen.Scan)
        vm.back()

        vm.solve()
        advanceUntilIdle()
        val solve = vm.state.value.screen as Screen.Solve
        assertTrue("a ${n}x$n solution fits easily", SavedScreen.encode(solve).size < 4_000)
        // The very same moves come back: the user may be halfway through them.
        val playback = restored()
        assertEquals(solve.solution, (playback.state.value.screen as Screen.Solve).solution)
        assertTrue(playback.back())
        assertEquals(solve.returnTo!!.review.colors, playback.review().colors)
    }

    @Test
    fun aCubeArrangedDifferentlyScannedByColorNamesIsReviewedAsItIs() = runTest(dispatcher) {
        assumeTrue("guided by color names: odd sizes only", n % 2 == 1)
        // Red and orange swapped (a mirrored knock-off).
        val scheme = ColorScheme.STANDARD.centers + mapOf(Face.R to CubeColor.ORANGE, Face.L to CubeColor.RED)
        for (seed in 1..3) {
            val cube = NxNCube.solved(n, scheme).apply(NxNScrambler.randomMoves(n, Random(seed * 31 + n)))
            val vm = viewModel(FakeSolver())
            vm.selectSize(n)
            vm.openScan()
            vm.onScanned(colorGuidedScansOf(cube))
            advanceUntilIdle()
            assertEquals("seed $seed", ReviewCheck.Valid, vm.review().check)
            assertEquals("seed $seed", cube.toColors(), vm.review().colors)
        }
    }

    private fun TestScope.viewModel(solver: CubeSolver, saved: SavedStateHandle = SavedStateHandle()) =
        AppViewModel(solver, InMemoryCubeSizeStore(), workDispatcher = StandardTestDispatcher(testScheduler), random = Random(n), savedState = saved)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}x{0}")
        fun sizes(): List<Int> = listOf(2, 3, 4, 5, 6, 7)
    }
}
