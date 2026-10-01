package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.MonotonicFrameClock
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.cube.CubeViewState
import com.andhab.cubelens.ui.cube.TurnEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun waitingCubeNudgesTheNextLayerWithoutChangingColors() = runTest(FakeFrameClock()) {
        val state = CubeViewState(UserCube.startColors)
        val playback = SolvePlayback(UserCube.startColors, moves, CubeViewAnimator(state), this)
        val hint = launch { state.hintTurnWhileWaiting(moves[0]) { playback.waitingIndex == 0 } }

        advanceTimeBy(TurnHint.FIRST_DELAY_MILLIS - 50)
        assertNull("no nudge straight away", state.animatingMove)

        // Near the height of the first nudge: the next move's layer is turned a little way.
        advanceTimeBy(50 + 330)
        assertEquals(moves[0], state.animatingMove)
        val turned = 90f * TurnEasing.transform(state.moveProgress)
        assertTrue("turned $turned°", turned in 10f..TurnHint.DEGREES + 0.5f)
        assertEquals(UserCube.startColors, state.colors)

        // It settles back, then nudges again.
        advanceTimeBy(TurnHint.NUDGE_MILLIS)
        assertNull(state.animatingMove)
        assertEquals(UserCube.startColors, state.colors)
        advanceTimeBy(TurnHint.INTERVAL_MILLIS + 200)
        assertEquals(moves[0], state.animatingMove)

        hint.cancel()
        advanceUntilIdle()
        assertNull("cancelling settles the layer", state.animatingMove)
        assertEquals(UserCube.startColors, state.colors)
    }

    @Test
    fun aNudgeNeverDisturbsTheTurnThatReplacesIt() = runTest(FakeFrameClock()) {
        val state = CubeViewState(UserCube.startColors)
        val playback = SolvePlayback(UserCube.startColors, moves, CubeViewAnimator(state), this)
        // Not cancelled when the wait ends, as an effect could be a frame late: it must stand aside.
        val hint = launch { state.hintTurnWhileWaiting(moves[0]) { playback.waitingIndex == 0 } }
        advanceTimeBy(TurnHint.FIRST_DELAY_MILLIS + 150)
        assertEquals(moves[0], state.animatingMove)

        playback.next()
        runCurrent()
        advanceTimeBy(100)
        assertEquals(moves[0], state.animatingMove)
        advanceTimeBy(SolvePlayback.TURN_MILLIS.toLong())
        assertEquals(1, playback.position)
        assertNull(state.animatingMove)
        assertEquals(UserCube.colorsAfter(1), state.colors)

        advanceTimeBy(10_000)
        assertNull(state.animatingMove)
        assertEquals(UserCube.colorsAfter(1), state.colors)
        hint.cancel()
    }

    @Test
    fun aNudgeTurnsQuarterAndHalfTurnsByTheSameAngle() {
        for (move in listOf(Move.R1, Move.R2, Move.R3)) {
            val progress = TurnHint.progressFor(move, TurnHint.DEGREES)
            val total = if (move == Move.R2) 180f else 90f
            assertEquals("$move", TurnHint.DEGREES, total * TurnEasing.transform(progress), 0.05f)
        }
        assertEquals(0f, TurnHint.lift(0), 0f)
        assertEquals(1f, TurnHint.lift(300), 0f)
        assertEquals(0f, TurnHint.lift(TurnHint.NUDGE_MILLIS), 0f)
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
