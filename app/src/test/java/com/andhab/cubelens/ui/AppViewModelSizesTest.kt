package com.andhab.cubelens.ui

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNGeometry
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

    private fun TestScope.viewModel(solver: CubeSolver) =
        AppViewModel(solver, InMemoryCubeSizeStore(), workDispatcher = StandardTestDispatcher(testScheduler), random = Random(n))

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}x{0}")
        fun sizes(): List<Int> = listOf(2, 3, 4, 5, 7)
    }
}
