package com.andhab.cubelens.ui.components

import com.andhab.cubelens.core.cube.CubeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConfettiSimulationTest {

    private fun burst(count: Int = 150, density: Float = 3f) =
        ConfettiSimulation(Random(42)).apply { burst(originX = 600f, originY = 900f, density = density, count = count) }

    private fun ConfettiSimulation.run(seconds: Float, dt: Float = 1f / 60f, floorY: Float = Float.POSITIVE_INFINITY) {
        repeat((seconds / dt).toInt()) { step(dt, floorY) }
    }

    @Test
    fun burstLaunchesParticlesInAllSixStickerColors() {
        val sim = burst()
        assertEquals(150, sim.particles.size)
        assertFalse(sim.isIdle)
        val colors = sim.particles.map { it.colorIndex }.toSet()
        assertEquals((0 until CubeColor.entries.size).toSet(), colors)
        assertEquals(ConfettiShape.entries.toSet(), sim.particles.map { it.shape }.toSet())
    }

    @Test
    fun burstIsBiasedUpwardThenGravityPullsEverythingDown() {
        val sim = burst()
        val meanVy0 = sim.particles.map { it.vy }.average()
        assertTrue("initial kick should be upward on average, was $meanVy0", meanVy0 < 0)

        sim.run(0.15f)
        val earlyY = sim.particles.map { it.y }.average()
        sim.run(1.2f)
        val lateY = sim.particles.map { it.y }.average()
        assertTrue("particles should fall over time ($earlyY -> $lateY)", lateY > earlyY)
        assertTrue("every particle should be falling by now", sim.particles.all { it.vy > 0 })
    }

    @Test
    fun dragKeepsSpeedsBounded() {
        val sim = burst(density = 1f)
        sim.run(2f)
        // Terminal velocity under gravity g and linear drag k is g / k (in dp/s at density 1).
        val terminal = ConfettiSimulation.GravityDp / ConfettiSimulation.Drag
        sim.particles.forEach { assertTrue(it.vy < terminal * 1.2f) }
    }

    @Test
    fun burstFadesOutAndGoesIdle() {
        val sim = burst()
        sim.run(1f)
        assertTrue(sim.particles.all { sim.alphaOf(it) == 1f })
        sim.run(ConfettiSimulation.MaxLife)
        assertTrue(sim.isIdle)
    }

    @Test
    fun alphaPopsInAndFadesOutAtEndOfLife() {
        val sim = burst(count = 1)
        val p = sim.particles.single()
        assertEquals(0f, sim.alphaOf(p), 0f)
        p.age = p.life / 2f
        assertEquals(1f, sim.alphaOf(p), 0f)
        p.age = p.life - ConfettiSimulation.FadeSeconds / 2f
        assertEquals(0.5f, sim.alphaOf(p), 1e-4f)
        p.age = p.life
        assertEquals(0f, sim.alphaOf(p), 0f)
    }

    @Test
    fun particlesBelowTheFloorAreDropped() {
        val sim = burst()
        sim.run(1.5f, floorY = 1000f)
        assertTrue(sim.particles.all { it.y <= 1000f + 40f * 3f })
        assertTrue(sim.particles.size < 150)
    }

    @Test
    fun longStallsAreClampedToOneStep() {
        val sim = burst(count = 1)
        sim.step(10f)
        assertEquals(ConfettiSimulation.MaxStep, sim.particles.single().age, 1e-6f)
        sim.step(-1f)
        assertEquals(ConfettiSimulation.MaxStep, sim.particles.single().age, 1e-6f)
    }

    @Test
    fun repeatedBurstsAccumulate() {
        val sim = burst(count = 10)
        sim.burst(0f, 0f, density = 3f, count = 5)
        assertEquals(15, sim.particles.size)
    }
}
