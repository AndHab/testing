package com.andhab.cubelens.ui.cube

import androidx.compose.runtime.MonotonicFrameClock
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.toLayerMove
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Behavior of [CubeViewState] turns and camera, driven by a fake frame clock (plain JVM). */
@OptIn(ExperimentalCoroutinesApi::class)
class CubeViewStateTest {

    private val solved: List<CubeColor?> = FaceletCube.SOLVED.toColors()

    @Test
    fun animateMoveCommitsThePermutedColors() = runTest(FakeFrameClock()) {
        val state = CubeViewState(solved)
        state.animateMove(Move.R1)
        assertEquals(FaceletCube.SOLVED.apply(Move.R1).toColors(), state.colors)
        assertNull(state.animatingMove)
        assertEquals(0f, state.moveProgress, 0f)

        state.animateMove(Move.U3, durationMillis = 0)
        assertEquals(FaceletCube.SOLVED.apply(Move.R1).apply(Move.U3).toColors(), state.colors)
    }

    @Test
    fun progressAdvancesDuringTheTurn() = runTest(FakeFrameClock()) {
        val state = CubeViewState(solved)
        val job = launch { state.animateMove(Move.F2, durationMillis = 400) }
        advanceTimeBy(150)
        runCurrent()
        assertEquals(Move.F2, state.animatingMove)
        assertTrue(state.moveProgress in 0.01f..0.99f)
        assertEquals(solved, state.colors)
        job.join()
        assertEquals(FaceletCube.SOLVED.apply(Move.F2).toColors(), state.colors)
    }

    @Test
    fun aNewTurnFinishesTheRunningOneInstantly() = runTest(FakeFrameClock()) {
        val state = CubeViewState(solved)
        val first = launch { state.animateMove(Move.R1) }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(Move.R1, state.animatingMove)

        state.animateMove(Move.U1)
        runCurrent()
        assertTrue(first.isCancelled)
        assertEquals(FaceletCube.SOLVED.apply(Move.R1).apply(Move.U1).toColors(), state.colors)
        assertNull(state.animatingMove)
    }

    @Test
    fun snapToCancelsWithoutCommitting() = runTest(FakeFrameClock()) {
        val state = CubeViewState(solved)
        val turn = launch { state.animateMove(Move.L1) }
        advanceTimeBy(100)
        runCurrent()
        val target = FaceletCube.SOLVED.apply(Move.D2).toColors()
        state.snapTo(target)
        runCurrent()
        assertTrue(turn.isCancelled)
        assertEquals(target, state.colors)
        assertNull(state.animatingMove)
    }

    @Test
    fun previewFreezesATurnAndIsNeverCommitted() = runTest(FakeFrameClock()) {
        val state = CubeViewState(solved)
        state.setPreview(Move.B3, 0.4f)
        assertEquals(Move.B3, state.animatingMove)
        assertEquals(0.4f, state.moveProgress, 0f)

        state.animateMove(Move.R1, durationMillis = 0)
        assertEquals(FaceletCube.SOLVED.apply(Move.R1).toColors(), state.colors)

        state.setPreview(Move.U1, 2f)
        assertEquals(1f, state.moveProgress, 0f)
        state.setPreview(null, 0.5f)
        assertNull(state.animatingMove)
        assertEquals(0f, state.moveProgress, 0f)
    }

    @Test
    fun cancelledCallerLeavesColorsUnchanged() = runTest(FakeFrameClock()) {
        val state = CubeViewState(solved)
        val turn = launch { state.animateMove(Move.R1) }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(Move.R1, state.animatingMove)
        turn.cancel()
        turn.join()
        assertEquals(solved, state.colors)
        assertNull(state.animatingMove)
    }

    @Test
    fun anglesAreWrappedAndClamped() {
        val state = CubeViewState(solved, initialYaw = 200f, initialPitch = 120f)
        assertEquals(-160f, state.yaw, 1e-4f)
        assertEquals(CubeViewState.MAX_PITCH, state.pitch, 0f)
        state.pitch = -500f
        assertEquals(CubeViewState.MIN_PITCH, state.pitch, 0f)
    }

    @Test
    fun animateViewTakesTheShortWayAround() = runTest(FakeFrameClock()) {
        val state = CubeViewState(solved, initialYaw = 170f, initialPitch = 0f)
        val job = launch { state.animateView(-170f, 30f) }
        var frames = 0
        while (job.isActive) {
            advanceTimeBy(FRAME_MILLIS)
            runCurrent()
            frames++
            // Going the short way passes through ±180, never through 0.
            assertTrue("yaw ${state.yaw}", abs(state.yaw) > 150f)
        }
        assertTrue(frames > 5)
        assertEquals(-170f, state.yaw, 0.5f)
        assertEquals(30f, state.pitch, 0.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongStickerCount() {
        CubeViewState(List(53) { CubeColor.WHITE })
    }

    @Test
    fun sizeIsInferredFromTheColorsAndFollowsSnapTo() {
        val state = CubeViewState(solved)
        assertEquals(3, state.size)
        state.snapTo(NxNCube.solved(7).toColors())
        assertEquals(7, state.size)
        assertEquals(294, state.colors.size)
        state.snapTo(NxNCube.solved(2).toColors())
        assertEquals(2, state.size)
        assertEquals(4, CubeViewState(NxNCube.solved(4).toColors()).size)
    }

    @Test
    fun layerMovesOfAnySizeCommitTheModelPermutation() = runTest(FakeFrameClock()) {
        val cases = listOf(
            4 to "Rw",
            4 to "2-3Lw'",
            5 to "2R",
            5 to "3U2",
            6 to "3Fw'",
            7 to "3Uw'",
            7 to "4R",
            2 to "U'",
            2 to "2Rw",
            3 to "3Rw",
            3 to "2F",
        )
        for ((n, notation) in cases) {
            val move = LayerMove.parse(notation)
            val start = NxNCube.solved(n).apply(TestCubes.scramble(n))
            val state = CubeViewState(start.toColors())
            state.animateMove(move)
            assertEquals("$n×$n $notation", start.apply(move).toColors(), state.colors)
            assertNull(state.animatingLayerMove)
        }
    }

    @Test
    fun outerMovesOnBigCubesMatchTheirLayerMove() = runTest(FakeFrameClock()) {
        val start = NxNCube.solved(5).apply(TestCubes.scramble(5))
        val state = CubeViewState(start.toColors())
        state.animateMove(Move.R3)
        assertEquals(start.apply(LayerMove.parse("R'")).toColors(), state.colors)
    }

    @Test
    fun animatingMoveIsOnlySetForOuterTurns() = runTest(FakeFrameClock()) {
        val state = CubeViewState(NxNCube.solved(4).toColors())
        val wide = launch { state.animateMove(LayerMove.parse("Rw")) }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(LayerMove.parse("Rw"), state.animatingLayerMove)
        assertNull(state.animatingMove)
        wide.join()

        val outer = launch { state.animateMove(LayerMove.parse("U2")) }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(Move.U2, state.animatingMove)
        outer.join()
        assertNull(state.animatingMove)
    }

    @Test
    fun layerPreviewFreezesAndNullReturnsToRest() {
        val state = CubeViewState(NxNCube.solved(5).toColors())
        state.setPreview(LayerMove.parse("2R"), 0.5f)
        assertEquals(LayerMove.parse("2R"), state.animatingLayerMove)
        assertEquals(0.5f, state.moveProgress, 0f)
        assertNull(state.animatingMove)
        state.setPreview(Move.F1, 0.3f)
        assertEquals(Move.F1, state.animatingMove)
        state.setPreview(null, 0.3f)
        assertNull(state.animatingLayerMove)
        assertEquals(0f, state.moveProgress, 0f)
        assertEquals(NxNCube.solved(5).toColors(), state.colors)

        // A nullable Move goes through the LayerMove overload; clearPreview returns to rest.
        val maybe: Move? = Move.D2
        state.setPreview(maybe?.toLayerMove(), 0.6f)
        assertEquals(Move.D2, state.animatingMove)
        state.clearPreview()
        assertNull(state.animatingLayerMove)
        assertEquals(0f, state.moveProgress, 0f)
        assertEquals(NxNCube.solved(5).toColors(), state.colors)
    }

    @Test
    fun clearPreviewCancelsARunningTurnWithoutCommitting() = runTest(FakeFrameClock()) {
        val state = CubeViewState(solved)
        val turn = launch { state.animateMove(Move.R1, durationMillis = 400) }
        runCurrent()
        assertEquals(Move.R1, state.animatingMove)
        state.clearPreview()
        runCurrent()
        assertTrue(turn.isCancelled)
        assertNull(state.animatingMove)
        assertEquals(solved, state.colors)
    }

    @Test
    fun movesDeeperThanTheCubeAreRejected() = runTest(FakeFrameClock()) {
        val state = CubeViewState(NxNCube.solved(3).toColors())
        assertTrue(runCatching { state.setPreview(LayerMove.parse("4Rw"), 0.5f) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { state.animateMove(LayerMove.parse("2-4Lw")) }.exceptionOrNull() is IllegalArgumentException)
        assertNull(state.animatingLayerMove)
        assertEquals(solved, state.colors)
    }

    @Test
    fun snappingToAnotherSizeCancelsTheTurn() = runTest(FakeFrameClock()) {
        val state = CubeViewState(NxNCube.solved(6).toColors())
        val turn = launch { state.animateMove(LayerMove.parse("3Rw")) }
        advanceTimeBy(100)
        runCurrent()
        state.snapTo(solved)
        runCurrent()
        assertTrue(turn.isCancelled)
        assertEquals(3, state.size)
        assertEquals(solved, state.colors)
        assertNull(state.animatingLayerMove)
    }

    @Test
    fun heavierTurnsTakeALittleLonger() {
        assertEquals(CubeViewState.DEFAULT_TURN_MILLIS, CubeViewState.defaultTurnMillis(LayerMove.parse("R")))
        assertEquals(CubeViewState.DEFAULT_TURN_MILLIS, CubeViewState.defaultTurnMillis(LayerMove.parse("3R2")))
        val wide = CubeViewState.defaultTurnMillis(LayerMove.parse("Rw"))
        val wider = CubeViewState.defaultTurnMillis(LayerMove.parse("3Rw"))
        val whole = CubeViewState.defaultTurnMillis(LayerMove.parse("7Rw"))
        assertTrue(CubeViewState.DEFAULT_TURN_MILLIS < wide && wide < wider && wider < whole)
        assertTrue(whole <= CubeViewState.DEFAULT_TURN_MILLIS * 1.3f)
    }

    /** One frame every 16 ms of the test's virtual time. */
    private class FakeFrameClock : MonotonicFrameClock {
        private var nanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(FRAME_MILLIS)
            nanos += FRAME_MILLIS * 1_000_000L
            return onFrame(nanos)
        }
    }

    private companion object {
        const val FRAME_MILLIS = 16L
    }
}
