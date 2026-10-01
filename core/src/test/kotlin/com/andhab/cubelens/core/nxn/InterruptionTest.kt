package com.andhab.cubelens.core.nxn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Cancellation: every long-running part of the N×N solver stops with [InterruptedException] soon
 * after its thread is interrupted, clears the interrupted status, and leaves nothing half-built
 * behind.
 */
class InterruptionTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun prepare() {
            NxNSolver.prepare()
        }

        /** Generous bound (the machine may be busy) on how long work may continue after an interrupt. */
        private const val MAX_STOP_MILLIS = 1_000.0
    }

    /**
     * Runs [work] on a new thread, interrupts it after [delayMillis] and returns what it threw and
     * how long after the interrupt it ended. Fails if [work] finished before the interrupt.
     */
    private fun interruptAfter(delayMillis: Long, work: () -> Unit): Pair<Throwable?, Double> {
        val outcome = AtomicReference<Throwable?>()
        val finishedEarly = AtomicReference(false)
        val worker = thread {
            try {
                work()
                finishedEarly.set(true)
            } catch (e: Throwable) {
                outcome.set(e)
                // The interrupted status is cleared along with the exception.
                if (e is InterruptedException && Thread.currentThread().isInterrupted) {
                    outcome.set(AssertionError("interrupted status not cleared"))
                }
            }
        }
        Thread.sleep(delayMillis)
        val interruptedAt = System.nanoTime()
        worker.interrupt()
        worker.join(10_000)
        val stopMillis = (System.nanoTime() - interruptedAt) / 1e6
        assertFalse("still running", worker.isAlive)
        assertFalse("finished before the interrupt", finishedEarly.get())
        return outcome.get() to stopMillis
    }

    private fun assertStoppedPromptly(result: Pair<Throwable?, Double>) {
        val (thrown, millis) = result
        assertTrue("threw $thrown", thrown is InterruptedException)
        assertTrue("stopped $millis ms after the interrupt", millis < MAX_STOP_MILLIS)
    }

    @Test
    fun solveThrowsWhenAlreadyInterrupted() {
        for (n in 2..5) {
            val cube = NxNTestCubes.scrambled(n, Random(n))
            Thread.currentThread().interrupt()
            assertThrows(InterruptedException::class.java) { NxNSolver.solve(cube) }
            assertFalse(Thread.interrupted())
            // Nothing is left in a bad state: the next solve works.
            assertTrue(cube.apply(NxNSolver.solve(cube).moves).isSolved)
        }
    }

    @Test
    fun bigCubeSolvesStopPromptly() {
        NxNSolver.prepareSize(7)
        val random = Random(9)
        val cubes = List(4) { NxNTestCubes.scrambled(7, random) }
        // Solving one 7×7 takes about 0.1 s, so the interrupt lands in the middle of one.
        assertStoppedPromptly(interruptAfter(250) { while (true) for (cube in cubes) NxNSolver.solve(cube) })
    }

    /** The 3×3 search stops at the interrupt (with a solution), which solve then reports. */
    @Test
    fun threeByThreeSolvesStopPromptly() {
        // Takes the full THREE_BY_THREE_SEARCH_MILLIS when not interrupted.
        val slow = NxNCube.parse(3, "OGWWRWOWYGRBYOOGWRYBBRWOROBOGRGYOGYBGYWYBBRRWYBYRGBWGO")
        assertStoppedPromptly(interruptAfter(100) { NxNSolver.solve(slow) })
    }

    @Test
    fun libraryBuildsStopPromptly() {
        Thread.currentThread().interrupt()
        assertThrows(InterruptedException::class.java) { CycleLibrary.buildUncached(6) }
        assertFalse(Thread.interrupted())
        // A cold 10×10 build takes a second or more.
        assertStoppedPromptly(interruptAfter(100) { CycleLibrary.buildUncached(10) })
    }

    @Test
    fun cornerTableBuildStopsPromptly() {
        Thread.currentThread().interrupt()
        assertThrows(InterruptedException::class.java) { CornerSolver.build() }
        assertFalse(Thread.interrupted())
        // Building takes a few tenths of a second.
        assertStoppedPromptly(interruptAfter(30) { CornerSolver.build() })
    }

    @Test
    fun orbitSearchChecksForInterrupts() {
        val library = CycleLibrary.of(4)
        val state = IntArray(24) { it }.also { it[0] = 1; it[1] = 2; it[2] = 0 }
        Thread.currentThread().interrupt()
        assertThrows(InterruptedException::class.java) {
            OrbitSolver.solve(library.wingCycles[0], state, IntArray(24) { it }, MoveSequence(4))
        }
        assertFalse(Thread.interrupted())
        assertEquals(1, OrbitSolver.solve(library.wingCycles[0], state, IntArray(24) { it }, MoveSequence(4)))
    }

    @Test
    fun failedBuildsAreNotCached() {
        var attempts = 0
        val once = BuildOnce {
            attempts++
            if (attempts == 1) throw InterruptedException()
            "built"
        }
        assertThrows(InterruptedException::class.java) { once.get() }
        assertFalse(once.isBuilt)
        assertEquals("built", once.get())
        assertEquals("built", once.get())
        assertEquals(2, attempts)
        assertTrue(once.isBuilt)
    }

    /** A thread waiting for another thread's build can be interrupted; the build itself goes on. */
    @Test
    fun waitingForABuildCanBeInterrupted() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val once = BuildOnce {
            started.countDown()
            release.await()
            42
        }
        val builder = thread { once.get() }
        started.await()
        val (thrown, millis) = interruptAfter(50) { once.get() }
        assertTrue("threw $thrown", thrown is InterruptedException)
        assertTrue(millis < MAX_STOP_MILLIS)
        release.countDown()
        builder.join(10_000)
        assertEquals(42, once.get())
    }
}
