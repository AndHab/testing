package com.andhab.cubelens.ui.solve

import androidx.compose.runtime.saveable.SaverScope
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Behavior of [SolvePlayback] with a fake animator on virtual time (plain JVM, no Compose clock).
 *
 * The fake checks on every turn that the cube it shows is the state at [SolvePlayback.position] and
 * that the turn is the next move or the inverse of the previous one, so any desync fails loudly.
 */
class SolvePlaybackTest {

    private val moves = UserCube.solution
    private val n = moves.size

    @Test
    fun solutionFixtureSolvesTheUsersCube() {
        assertTrue(UserCube.cube.apply(moves).isSolved)
        val solved = FaceletCube.fromColors(UserCube.colorsAfter(n))
        assertNotNull(solved)
        assertTrue(solved!!.isSolved)
    }

    @Test
    fun startsAtTheScrambledCube() = runTest {
        val (playback, animator) = playback()
        assertEquals(0, playback.position)
        assertEquals(0, playback.currentIndex)
        assertEquals(moves[0], playback.currentMove)
        assertFalse(playback.isFinished)
        assertFalse(playback.isPlaying)
        assertFalse(playback.canGoBack)
        assertTrue(playback.canGoForward)
        assertEquals(0, playback.celebration)
        assertEquals(UserCube.startColors, animator.colors)
    }

    @Test
    fun everyPrefixStateMatchesTheMovesApplied() = runTest {
        val (playback, _) = playback()
        for (k in 0..n) assertEquals("k=$k", UserCube.colorsAfter(k), playback.colorsAt(k))
    }

    @Test
    fun nextAnimatesTheCurrentMoveThenAdvances() = runTest {
        val (playback, animator) = playback()
        playback.next()
        runCurrent()
        assertEquals(listOf(moves[0]), animator.turnedMoves)
        assertEquals(listOf(SolvePlayback.TURN_MILLIS), animator.durations)
        // Mid-turn: k only moves once the turn has finished, and the turning move is "current".
        assertEquals(0, playback.position)
        assertEquals(0, playback.currentIndex)

        advanceUntilIdle()
        assertEquals(1, playback.position)
        assertEquals(1, playback.currentIndex)
        assertTrue(playback.canGoBack)
        assertEquals(UserCube.colorsAfter(1), animator.colors)
    }

    @Test
    fun previousAnimatesTheInverseThenStepsBack() = runTest {
        val (playback, animator) = playback()
        playback.next()
        playback.next()
        advanceUntilIdle()
        assertEquals(2, playback.position)

        playback.previous()
        runCurrent()
        assertEquals(moves[1].inverse, animator.turnedMoves.last())
        // While undoing, the move being undone is the current one.
        assertEquals(1, playback.currentIndex)

        advanceUntilIdle()
        assertEquals(1, playback.position)
        assertEquals(1, playback.currentIndex)
        assertEquals(UserCube.colorsAfter(1), animator.colors)
    }

    @Test
    fun cannotStepPastEitherEnd() = runTest {
        val (playback, animator) = playback()
        playback.previous()
        advanceUntilIdle()
        assertEquals(0, playback.position)
        assertTrue(animator.turnedMoves.isEmpty())

        playback.jumpTo(n)
        playback.next()
        advanceUntilIdle()
        assertEquals(n, playback.position)
        assertFalse(playback.canGoForward)
        assertTrue(animator.turnedMoves.isEmpty())
    }

    @Test
    fun jumpCancelsTheRunningTurnAndSnaps() = runTest {
        val (playback, animator) = playback()
        playback.next()
        runCurrent()
        advanceTimeBy(150)
        assertNotNull(animator.inFlight)

        playback.jumpTo(7)
        assertEquals(7, playback.position)
        assertEquals(7, playback.currentIndex)
        assertEquals(UserCube.colorsAfter(7), animator.colors)

        advanceUntilIdle()
        // The abandoned turn is never committed.
        assertNull(animator.inFlight)
        assertEquals(7, playback.position)
        assertEquals(UserCube.colorsAfter(7), animator.colors)
    }

    @Test
    fun jumpClampsToTheSolution() = runTest {
        val (playback, animator) = playback()
        playback.jumpTo(99)
        assertEquals(n, playback.position)
        assertTrue(playback.isFinished)
        playback.jumpTo(-3)
        assertEquals(0, playback.position)
        assertEquals(UserCube.startColors, animator.colors)
    }

    @Test
    fun playAdvancesWithPausesAndStopsAtTheEnd() = runTest {
        val (playback, animator) = playback()
        playback.play()
        runCurrent()
        // The first move starts at once.
        assertTrue(playback.isPlaying)
        assertEquals(1, animator.turnedMoves.size)

        advanceTimeBy(SolvePlayback.TURN_MILLIS + 1L)
        assertEquals(1, playback.position)
        // Then a pause to read the next move before it turns.
        advanceTimeBy(SolvePlayback.PAUSE_MILLIS - 10)
        assertEquals(1, animator.turnedMoves.size)
        advanceTimeBy(20)
        assertEquals(2, animator.turnedMoves.size)

        advanceUntilIdle()
        assertEquals(n, playback.position)
        assertTrue(playback.isFinished)
        assertFalse(playback.isPlaying)
        assertEquals(1, playback.celebration)
        assertEquals(moves, animator.turnedMoves)
        assertTrue(FaceletCube.fromColors(animator.colors)!!.isSolved)
        assertNull(playback.currentMove)
    }

    @Test
    fun pauseLetsTheRunningTurnFinishThenStops() = runTest {
        val (playback, animator) = playback()
        playback.play()
        runCurrent()
        advanceTimeBy(100)
        playback.pause()
        assertFalse(playback.isPlaying)

        advanceUntilIdle()
        assertEquals(1, playback.position)
        assertEquals(1, animator.turnedMoves.size)
        assertEquals(UserCube.colorsAfter(1), animator.colors)
    }

    @Test
    fun pauseBetweenMovesStopsAtOnce() = runTest {
        val (playback, animator) = playback()
        playback.play()
        runCurrent()
        advanceTimeBy(SolvePlayback.TURN_MILLIS + 50L)
        assertEquals(1, playback.position)

        playback.pause()
        advanceUntilIdle()
        assertEquals(1, playback.position)
        assertEquals(1, animator.turnedMoves.size)
    }

    @Test
    fun aManualStepWhilePlayingPausesAndStepsRightAway() = runTest {
        val (playback, animator) = playback()
        playback.togglePlay()
        runCurrent()
        advanceTimeBy(SolvePlayback.TURN_MILLIS + 50L)
        assertEquals(1, playback.position)

        // In the pause between moves: the step does not wait for the pause to run out.
        playback.next()
        assertFalse(playback.isPlaying)
        runCurrent()
        assertEquals(2, animator.turnedMoves.size)

        advanceUntilIdle()
        assertEquals(2, playback.position)
        assertEquals(UserCube.colorsAfter(2), animator.colors)
    }

    @Test
    fun previousDuringAPlayedTurnUndoesIt() = runTest {
        val (playback, animator) = playback()
        playback.jumpTo(4)
        playback.play()
        runCurrent()
        advanceTimeBy(100)
        playback.previous()
        assertFalse(playback.isPlaying)
        // The turn in flight finishes, then is undone: back where it started.
        assertEquals(4, playback.currentIndex)

        advanceUntilIdle()
        assertEquals(listOf(moves[4], moves[4].inverse), animator.turnedMoves)
        assertEquals(4, playback.position)
        assertEquals(UserCube.colorsAfter(4), animator.colors)
    }

    @Test
    fun replayAfterFinishingStartsOver() = runTest {
        val (playback, animator) = playback()
        playback.jumpTo(n - 1)
        playback.next()
        advanceUntilIdle()
        assertTrue(playback.isFinished)
        assertEquals(1, playback.celebration)

        playback.restart()
        assertFalse(playback.isFinished)
        assertEquals(0, playback.position)
        assertEquals(UserCube.startColors, animator.colors)

        // Finishing again is a new celebration.
        playback.play()
        advanceUntilIdle()
        assertTrue(playback.isFinished)
        assertEquals(2, playback.celebration)
    }

    @Test
    fun playFromTheSolvedEndStartsOver() = runTest {
        val (playback, animator) = playback()
        playback.jumpTo(n)
        playback.play()
        assertEquals(0, playback.position)
        assertEquals(UserCube.startColors, animator.colors)
        runCurrent()
        assertEquals(listOf(moves[0]), animator.turnedMoves)
    }

    @Test
    fun playAfterQuickStepsBackDropsTheBacklog() = runTest {
        val (playback, animator) = playback()
        playback.jumpTo(8)
        repeat(4) { playback.previous() }
        runCurrent()
        advanceTimeBy(60)
        // One undo is turning, three more are queued.
        assertEquals(moves[7].inverse, animator.inFlight)
        assertEquals(4, playback.target)

        playback.play()
        // Only the undo in flight is kept; then playback heads forward from there.
        assertEquals(7, playback.target)
        advanceTimeBy(SolvePlayback.CATCH_UP_TURN_MILLIS.toLong())
        assertEquals(7, playback.position)
        runCurrent()
        assertEquals(listOf(moves[7].inverse, moves[7]), animator.turnedMoves)
        assertTrue(playback.isPlaying)

        advanceUntilIdle()
        assertTrue(playback.isFinished)
        assertEquals(listOf(moves[7].inverse) + moves.drop(7), animator.turnedMoves)
    }

    @Test
    fun playWithStepsBackQueuedButNoTurnYetStaysPut() = runTest {
        val (playback, animator) = playback()
        playback.jumpTo(5)
        playback.previous()
        playback.previous()
        // Nothing has started turning yet: play cancels both steps back.
        playback.play()
        assertEquals(5, playback.target)
        runCurrent()
        assertEquals(listOf(moves[5]), animator.turnedMoves)
    }

    @Test
    fun waitingIndexIsSetOnlyWhileStandingStill() = runTest {
        val (playback, _) = playback()
        assertEquals(0, playback.waitingIndex)
        playback.next()
        // Turning: not waiting.
        assertNull(playback.waitingIndex)
        advanceUntilIdle()
        assertEquals(1, playback.waitingIndex)

        playback.play()
        assertNull(playback.waitingIndex)
        advanceTimeBy(SolvePlayback.TURN_MILLIS + 100L)
        // In the pause between played moves: still not waiting for the person.
        assertNull(playback.waitingIndex)
        playback.pause()
        assertEquals(2, playback.waitingIndex)

        playback.jumpTo(n)
        assertNull(playback.waitingIndex)
    }

    @Test
    fun rapidTapsQueueUpAndCatchUpQuickly() = runTest {
        val (playback, animator) = playback()
        repeat(5) { playback.next() }
        assertEquals(5, playback.target)
        advanceUntilIdle()
        assertEquals(5, playback.position)
        assertEquals(moves.take(5), animator.turnedMoves)
        // The backlog plays in quick turns; the last one at the normal pace.
        assertEquals(
            List(4) { SolvePlayback.CATCH_UP_TURN_MILLIS } + SolvePlayback.TURN_MILLIS,
            animator.durations,
        )
        assertEquals(UserCube.colorsAfter(5), animator.colors)
    }

    @Test
    fun tapsBeyondTheEndsAreIgnored() = runTest {
        val (playback, _) = playback()
        repeat(n + 10) { playback.next() }
        assertEquals(n, playback.target)
        repeat(3) { playback.previous() }
        assertEquals(n - 3, playback.target)
        advanceUntilIdle()
        assertEquals(n - 3, playback.position)
    }

    @Test
    fun randomCommandStormNeverDesyncsTheCube() = runTest {
        val (playback, animator) = playback()
        val random = Random(20261001)
        repeat(600) {
            when (random.nextInt(10)) {
                0, 1, 2 -> playback.next()
                3, 4 -> playback.previous()
                5 -> playback.jumpTo(random.nextInt(-1, n + 2))
                6, 7 -> playback.togglePlay()
                8 -> playback.cycleSpeed()
                else -> playback.restart()
            }
            advanceTimeBy(random.nextLong(0, 700))
            if (animator.inFlight == null) {
                assertEquals("at rest after step $it", UserCube.colorsAfter(playback.position), animator.colors)
            }
        }
        playback.pause()
        advanceUntilIdle()
        assertEquals(playback.target, playback.position)
        assertEquals(UserCube.colorsAfter(playback.position), animator.colors)
        assertTrue(animator.turnedMoves.size > 50)
    }

    @Test
    fun reducedMotionSnapsInsteadOfAnimating() = runTest {
        val (playback, animator) = playback(motionScale = 0f)
        playback.next()
        playback.next()
        advanceUntilIdle()
        assertEquals(2, playback.position)
        assertTrue(animator.turnedMoves.isEmpty())
        assertEquals(UserCube.colorsAfter(2), animator.colors)

        // Playing still leaves time to read each move.
        val before = currentTime
        playback.play()
        advanceUntilIdle()
        assertTrue(playback.isFinished)
        assertTrue(animator.turnedMoves.isEmpty())
        assertEquals((n - 3) * SolvePlayback.PAUSE_MILLIS, currentTime - before)
    }

    @Test
    fun speedScalesTurnsAndPauses() = runTest {
        val (playback, animator) = playback()
        playback.speed = PlaybackSpeed.Fast
        playback.next()
        advanceUntilIdle()
        playback.cycleSpeed()
        assertEquals(PlaybackSpeed.Slow, playback.speed)
        playback.next()
        advanceUntilIdle()
        assertEquals(listOf(SolvePlayback.TURN_MILLIS / 2, SolvePlayback.TURN_MILLIS * 2), animator.durations)

        playback.speed = PlaybackSpeed.Fast
        playback.play()
        runCurrent()
        advanceTimeBy(SolvePlayback.TURN_MILLIS / 2 + SolvePlayback.PAUSE_MILLIS / 2 + 1)
        assertEquals(4, animator.durations.size)
    }

    @Test
    fun speedLabelsAndCycle() {
        assertEquals(listOf("0.5×", "1×", "2×"), PlaybackSpeed.entries.map { it.label })
        assertEquals(PlaybackSpeed.Fast, PlaybackSpeed.Normal.next())
        assertEquals(PlaybackSpeed.Slow, PlaybackSpeed.Fast.next())
    }

    @Test
    fun anEmptySolutionIsFinishedFromTheStart() = runTest {
        val solved = FaceletCube.SOLVED.toColors()
        val (playback, animator) = playback(moves = emptyList(), start = solved)
        assertTrue(playback.isFinished)
        assertEquals(1, playback.celebration)
        assertNull(playback.currentMove)
        playback.next()
        playback.play()
        advanceUntilIdle()
        assertFalse(playback.isPlaying)
        assertEquals(0, playback.position)
        assertTrue(animator.turnedMoves.isEmpty())
        assertEquals(solved, animator.colors)
    }

    @Test
    fun savedStateRestoresPositionSpeedAndCelebration() = runTest {
        val (playback, _) = playback()
        playback.jumpTo(n)
        playback.jumpTo(6)
        playback.speed = PlaybackSpeed.Slow
        val saver = SolvePlayback.saver(UserCube.startColors, moves, FakeAnimator(), backgroundScope)
        val saved = with(saver) { SaverScope { true }.save(playback) }!!

        val restoredAnimator = FakeAnimator()
        val restored = SolvePlayback.saver(UserCube.startColors, moves, restoredAnimator, backgroundScope).restore(saved)!!
        assertEquals(6, restored.position)
        assertEquals(PlaybackSpeed.Slow, restored.speed)
        assertEquals(1, restored.celebration)
        // The restored playback puts the cube where it left off.
        assertEquals(UserCube.colorsAfter(6), restoredAnimator.colors)
    }

    @Test
    fun savedStateResumesPlayingOnceShown() = runTest {
        val (playback, _) = playback()
        playback.jumpTo(3)
        playback.play()
        runCurrent()
        advanceTimeBy(100)
        val saved = with(SolvePlayback.saver(UserCube.startColors, moves, FakeAnimator(), this)) {
            SaverScope { true }.save(playback)
        }!!
        playback.pause()
        advanceUntilIdle()

        val restoredAnimator = FakeAnimator()
        val restored = SolvePlayback.saver(UserCube.startColors, moves, restoredAnimator, this).restore(saved)!!
        restoredAnimator.playback = restored
        // Restored where the last finished turn left the cube, and not playing until shown.
        assertEquals(3, restored.position)
        assertFalse(restored.isPlaying)

        restored.resumeAfterRestore()
        assertTrue(restored.isPlaying)
        runCurrent()
        assertEquals(listOf(moves[3]), restoredAnimator.turnedMoves)
        // Only once.
        restored.pause()
        restored.resumeAfterRestore()
        assertFalse(restored.isPlaying)
        advanceUntilIdle()
        assertEquals(UserCube.colorsAfter(restored.position), restoredAnimator.colors)
    }

    @Test
    fun aPausedSavedStateStaysPaused() = runTest {
        val (playback, _) = playback()
        playback.jumpTo(3)
        val saved = with(SolvePlayback.saver(UserCube.startColors, moves, FakeAnimator(), this)) {
            SaverScope { true }.save(playback)
        }!!
        val restored = SolvePlayback.saver(UserCube.startColors, moves, FakeAnimator(), this).restore(saved)!!
        restored.resumeAfterRestore()
        assertFalse(restored.isPlaying)
        assertEquals(3, restored.position)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCentersThatRepeat() = runTest {
        val bad = UserCube.startColors.toMutableList().apply { this[Facelets.center(Face.U)] = CubeColor.GREEN }
        playback(start = bad)
    }

    private fun TestScope.playback(
        moves: List<Move> = this@SolvePlaybackTest.moves,
        start: List<CubeColor> = UserCube.startColors,
        motionScale: Float = 1f,
    ): Pair<SolvePlayback, FakeAnimator> {
        val animator = FakeAnimator()
        val playback = SolvePlayback(start, moves, animator, this, motionScale = { motionScale })
        animator.playback = playback
        return playback to animator
    }

    /**
     * Stands in for the 3D cube: a turn takes its duration in virtual time and is only applied if it
     * runs to the end, like [com.andhab.cubelens.ui.cube.CubeViewState.animateMove].
     */
    private class FakeAnimator : CubeAnimator {
        var playback: SolvePlayback? = null
        var colors: List<CubeColor> = emptyList()
            private set
        var inFlight: Move? = null
            private set
        val turnedMoves = mutableListOf<Move>()
        val durations = mutableListOf<Int>()

        override suspend fun turn(move: Move, durationMillis: Int) {
            playback?.let { p ->
                val k = p.position
                assertEquals("cube shown at the start of a turn from k=$k", p.colorsAt(k), colors)
                assertTrue("$move is neither the next move nor an undo at k=$k", move == p.moves.getOrNull(k) || move == p.moves.getOrNull(k - 1)?.inverse)
            }
            check(inFlight == null) { "Two turns at once" }
            turnedMoves += move
            durations += durationMillis
            inFlight = move
            try {
                delay(durationMillis.toLong())
            } finally {
                inFlight = null
            }
            val permutation = move.permutation
            val before = colors
            colors = List(Facelets.COUNT) { before[permutation[it]] }
        }

        override fun snapTo(colors: List<CubeColor>) {
            inFlight = null
            this.colors = colors
        }
    }
}
