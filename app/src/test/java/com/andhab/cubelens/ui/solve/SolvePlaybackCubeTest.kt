package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.MonotonicFrameClock
import com.andhab.cubelens.ui.cube.CubeViewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * [SolvePlayback] driving the real [CubeViewState] (through [CubeViewAnimator]) on a fake frame
 * clock: whatever the user taps, the cube at rest always shows the moves applied so far.
 */
class SolvePlaybackCubeTest {

    private val moves = UserCube.solution

    @Test
    fun turnsLandOnTheCube() = runTest(FakeFrameClock()) {
        val state = CubeViewState(UserCube.startColors)
        val playback = SolvePlayback(UserCube.startColors, moves, CubeViewAnimator(state), this)
        playback.next()
        runCurrent()
        advanceTimeBy(200)
        assertEquals(moves[0], state.animatingMove)
        advanceUntilIdle()
        assertNull(state.animatingMove)
        assertEquals(1, playback.position)
        assertEquals(UserCube.colorsAfter(1), state.colors)

        playback.previous()
        advanceUntilIdle()
        assertEquals(UserCube.startColors, state.colors)
    }

    @Test
    fun jumpingMidTurnSnapsTheCube() = runTest(FakeFrameClock()) {
        val state = CubeViewState(UserCube.startColors)
        val playback = SolvePlayback(UserCube.startColors, moves, CubeViewAnimator(state), this)
        playback.play()
        runCurrent()
        advanceTimeBy(150)
        playback.jumpTo(12)
        assertNull(state.animatingMove)
        assertEquals(UserCube.colorsAfter(12), state.colors)
        advanceUntilIdle()
        assertEquals(12, playback.position)
        assertEquals(UserCube.colorsAfter(12), state.colors)
    }

    @Test
    fun rapidCommandsKeepTheCubeInStep() = runTest(FakeFrameClock()) {
        val state = CubeViewState(UserCube.startColors)
        val playback = SolvePlayback(UserCube.startColors, moves, CubeViewAnimator(state), this)
        val random = Random(4242)
        repeat(400) { step ->
            when (random.nextInt(9)) {
                0, 1, 2 -> playback.next()
                3, 4 -> playback.previous()
                5 -> playback.jumpTo(random.nextInt(0, moves.size + 1))
                6, 7 -> playback.togglePlay()
                else -> playback.cycleSpeed()
            }
            advanceTimeBy(random.nextLong(0, 500))
            if (state.animatingMove == null) {
                assertEquals("at rest after step $step", UserCube.colorsAfter(playback.position), state.colors)
            }
        }
        playback.pause()
        advanceUntilIdle()
        assertEquals(playback.target, playback.position)
        assertEquals(UserCube.colorsAfter(playback.position), state.colors)
    }

    /** A 60 Hz frame clock on the test's virtual time. */
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
