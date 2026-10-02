package com.andhab.cubelens.ui.solve

import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNSolver
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.random.Random

/**
 * [SolvePlayback] over real staged solutions from the app's solver for 2×2, 3×3, 4×4, 5×5 and 7×7
 * cubes (wide moves, inner slices and layer ranges included), with the checking [FakeAnimator]:
 * whatever the commands, the cube at rest is always the start colors with `moves[0 until k]`
 * applied, computed independently.
 */
@RunWith(Parameterized::class)
class SolvePlaybackSizesTest(private val n: Int) {

    private val fixture = RealSolutions.of(n)
    private val moves = fixture.moves
    private val count = moves.size

    @Test
    fun theSolverSolutionSolvesTheCubeInStages() {
        assertTrue("a ${n}x$n scramble needs moves", count > 0)
        assertTrue(NxNCube.of(n, fixture.colorsAfter(count)).isSolved)
        val names = fixture.solution.stages.map { it.name }
        if (n <= 3) {
            assertEquals(listOf(NxNSolver.STAGE_SOLVE), names)
        } else {
            assertEquals(if (n % 2 == 1) NxNSolver.STAGE_FRAME else NxNSolver.STAGE_CORNERS, names.first())
            assertTrue("$names", names.containsAll(listOf(NxNSolver.STAGE_EDGES, NxNSolver.STAGE_CENTERS)))
            assertTrue("big cubes turn more than outer layers", moves.any { !it.isOuter })
        }
    }

    @Test
    fun everyPrefixStateMatchesTheMovesApplied() = runTest {
        val (playback, animator) = playback()
        assertEquals(n, playback.n)
        assertEquals(moves, playback.moves)
        assertEquals(fixture.startColors, animator.colors)
        for (k in 0..count) assertEquals("k=$k", fixture.colorsAfter(k), playback.colorsAt(k))
    }

    @Test
    fun stagesSplitTheMovesInOrder() = runTest {
        val (playback, _) = playback()
        val stages = fixture.solution.stages
        assertEquals(stages, playback.stages)
        var start = 0
        stages.forEachIndexed { s, stage ->
            assertEquals(start, playback.stageStart(s))
            assertEquals(stage.moves.size, playback.stageSize(s))
            assertEquals(s, playback.stageOf(start))
            assertEquals(s, playback.stageOf(start + stage.moves.size - 1))
            start += stage.moves.size
        }
        assertEquals(count, start)
        assertEquals(stages.lastIndex, playback.stageOf(count))
        assertEquals(0, playback.currentStage)
    }

    @Test
    fun steppingThroughTheWholeSolutionKeepsTheCubeInStep() = runTest {
        val (playback, animator) = playback()
        repeat(count) { k ->
            playback.next()
            advanceUntilIdle()
            assertEquals(k + 1, playback.position)
            assertEquals("after move ${k + 1} (${moves[k]})", fixture.colorsAfter(k + 1), animator.colors)
        }
        assertTrue(playback.isFinished)
        assertEquals(1, playback.celebration)
        assertTrue(NxNCube.of(n, animator.colors).isSolved)
        // And all the way back, one undo at a time.
        repeat(count) {
            playback.previous()
            advanceUntilIdle()
        }
        assertEquals(fixture.startColors, animator.colors)
    }

    @Test
    fun playingAtFourTimesSpeedFinishesSolved() = runTest {
        val (playback, animator) = playback()
        playback.speed = PlaybackSpeed.Faster
        playback.play()
        advanceUntilIdle()
        assertTrue(playback.isFinished)
        assertEquals(moves, animator.turnedMoves)
        assertTrue(animator.durations.all { it == SolvePlayback.TURN_MILLIS / 4 })
        assertTrue(NxNCube.of(n, animator.colors).isSolved)
    }

    @Test
    fun stageJumpsSnapToStageStarts() = runTest {
        val (playback, animator) = playback()
        val stages = playback.stages.size
        assertFalse(playback.hasPreviousStage)
        assertEquals(stages > 1, playback.hasNextStage)

        // Forward through every stage.
        for (s in 1 until stages) {
            playback.nextStage()
            assertEquals(playback.stageStart(s), playback.position)
            assertEquals(s, playback.currentStage)
            assertEquals(fixture.colorsAfter(playback.position), animator.colors)
        }
        assertFalse(playback.hasNextStage)
        playback.nextStage()
        assertEquals(playback.stageStart(stages - 1), playback.position)

        // A move into the last stage, "previous" returns to its start; again, to the stage before.
        playback.next()
        advanceUntilIdle()
        playback.previousStage()
        assertEquals(playback.stageStart(stages - 1), playback.position)
        assertEquals(fixture.colorsAfter(playback.position), animator.colors)
        playback.previousStage()
        assertEquals(playback.stageStart((stages - 2).coerceAtLeast(0)), playback.position)
        assertEquals(fixture.colorsAfter(playback.position), animator.colors)
        assertNull(animator.inFlight)
    }

    @Test
    fun aStageJumpMidTurnCancelsTheTurn() = runTest {
        val (playback, animator) = playback()
        assumeTrue("needs stages", playback.stages.size > 1)
        playback.play()
        runCurrent()
        advanceTimeBy(100)
        assertNotNull(animator.inFlight)
        playback.nextStage()
        assertFalse(playback.isPlaying)
        val expected = playback.stageStart(1)
        assertEquals(expected, playback.position)
        assertEquals(fixture.colorsAfter(expected), animator.colors)
        advanceUntilIdle()
        // The abandoned turn is never committed.
        assertEquals(expected, playback.position)
        assertEquals(fixture.colorsAfter(expected), animator.colors)
    }

    @Test
    fun previousStageWhileUndoingQueuedStepsStaysConsistent() = runTest {
        val (playback, animator) = playback()
        val start = playback.stageStart(playback.stages.lastIndex)
        playback.jumpTo(start + 2.coerceAtMost(count - start))
        repeat(5) { playback.previous() }
        runCurrent()
        advanceTimeBy(30)
        playback.previousStage()
        advanceUntilIdle()
        assertEquals(playback.target, playback.position)
        assertEquals(fixture.colorsAfter(playback.position), animator.colors)
    }

    @Test
    fun rapidCommandsNeverDesyncTheCube() = runTest {
        val (playback, animator) = playback()
        val random = Random(9000 + n)
        repeat(700) { step ->
            when (random.nextInt(14)) {
                0, 1, 2 -> playback.next()
                3, 4 -> playback.previous()
                5 -> playback.jumpTo(random.nextInt(-1, count + 2))
                6, 7 -> playback.togglePlay()
                8 -> playback.cycleSpeed()
                9 -> playback.nextStage()
                10 -> playback.previousStage()
                11 -> playback.restart()
                12 -> repeat(random.nextInt(2, 9)) { playback.next() }
                else -> repeat(random.nextInt(2, 9)) { playback.previous() }
            }
            advanceTimeBy(random.nextLong(0, 400))
            assertTrue(playback.position in 0..count)
            assertTrue(playback.currentIndex in 0..count)
            if (animator.inFlight == null) {
                assertEquals("at rest after step $step", fixture.colorsAfter(playback.position), animator.colors)
            }
        }
        playback.pause()
        advanceUntilIdle()
        assertEquals(playback.target, playback.position)
        assertEquals(fixture.colorsAfter(playback.position), animator.colors)
        assertTrue(animator.turnedMoves.size > 50)
    }

    @Test
    fun replayAfterFinishingPlaysItAllAgain() = runTest {
        val (playback, animator) = playback()
        playback.jumpTo(count)
        assertEquals(1, playback.celebration)
        playback.restart()
        playback.speed = PlaybackSpeed.Faster
        playback.play()
        advanceUntilIdle()
        assertTrue(playback.isFinished)
        assertEquals(2, playback.celebration)
        assertEquals(moves, animator.turnedMoves)
        assertEquals(fixture.colorsAfter(count), animator.colors)
    }

    private fun TestScope.playback(): Pair<SolvePlayback, FakeAnimator> {
        val animator = FakeAnimator()
        val playback = SolvePlayback(fixture.startColors, fixture.solution, animator, this)
        animator.playback = playback
        return playback to animator
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}x{0}")
        fun sizes(): List<Int> = listOf(2, 3, 4, 5, 7)
    }
}
