package com.andhab.cubelens.ui.cube

import androidx.compose.runtime.MonotonicFrameClock
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
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
