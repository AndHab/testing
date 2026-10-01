package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class AdaptiveLiveClassifierTest {

    private val pastel = KnockOffCubes.PASTEL

    /** [look]'s [color] at [exposure] (linear factor) under white balance [gains]. */
    private fun sample(look: CubeLook, color: CubeColor, exposure: Double = 1.0, gains: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0)): StickerSample {
        val c = look.linear.getValue(color)
        val v = IntArray(3) { ColorMath.linearToSrgb(c[it] * exposure * gains[it]) }
        return StickerSample.of(v[0], v[1], v[2])
    }

    private val exposures = listOf(0.65, 0.8, 1.0, 1.15, 1.25)
    private val mildCasts = listOf(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.06, 1.0, 0.92), doubleArrayOf(0.94, 1.0, 1.08))

    @Test
    fun withNothingLearnedItIsTheDefaultClassifier() {
        val adaptive = AdaptiveLiveClassifier()
        val random = Random(1)
        repeat(3000) {
            val s = StickerSample.of(random.nextInt(256), random.nextInt(256), random.nextInt(256))
            assertEquals("$s", LiveClassifier.classify(s), adaptive.classify(s))
        }
        assertTrue(adaptive.learned.isEmpty())
    }

    @Test
    fun learnedColorsDoNotStealOtherColors() {
        // One learned color at a time, from a center at normal exposure: every other color of the cube,
        // at any exposure and under mild casts, keeps its default reading, and the learned color is
        // recognized at any exposure.
        for (look in listOf(pastel, KnockOffCubes.MUTED, KnockOffCubes.CANDY, KnockOffCubes.VIVID)) {
            for (learnedColor in CubeColor.entries) {
                val adaptive = AdaptiveLiveClassifier()
                adaptive.learn(learnedColor, sample(look, learnedColor))
                for (color in CubeColor.entries) {
                    for (exposure in exposures) {
                        for (cast in mildCasts) {
                            val s = sample(look, color, exposure, cast)
                            assertEquals("$look: learned $learnedColor, $color at $exposure ${cast.toList()}", color, adaptive.classify(s))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun pinkLearnedAsRedLeavesPeachOrange() {
        val adaptive = AdaptiveLiveClassifier()
        adaptive.learn(CubeColor.RED, StickerSample.ofArgb(0xF2A0B4))
        assertEquals(CubeColor.ORANGE, adaptive.classify(StickerSample.ofArgb(0xF7BE92)))
        assertEquals(CubeColor.RED, adaptive.classify(StickerSample.ofArgb(0xF2A0B4)))
        assertEquals(CubeColor.WHITE, adaptive.classify(StickerSample.ofArgb(0xF7F5EE)))
        assertEquals(setOf(CubeColor.RED), adaptive.learned.keys)
    }

    @Test
    fun learnedColorsFixWhatTheDefaultRulesCannotTell() {
        // Under strong warm light a pastel baby blue looks white, and under cool light peach does: the
        // default rules can't tell from one sample. Learned from the centers in that light, they can.
        for (gains in listOf(doubleArrayOf(1.3, 1.0, 0.68), doubleArrayOf(0.8, 1.04, 1.3))) {
            val adaptive = AdaptiveLiveClassifier()
            for (color in CubeColor.entries) adaptive.learn(color, sample(pastel, color, 1.0, gains))
            var defaultCorrect = 0
            var total = 0
            for (color in CubeColor.entries) {
                for (exposure in exposures) {
                    val s = sample(pastel, color, exposure, gains)
                    assertEquals("$color at $exposure under ${gains.toList()}", color, adaptive.classify(s))
                    total++
                    if (LiveClassifier.classify(s) == color) defaultCorrect++
                }
            }
            assertTrue("the default rules should have trouble here", defaultCorrect < total)
        }
    }

    @Test
    fun learnedWhiteBalancesTheColorsNotLearnedYet() {
        // Strong warm light: with only white learned, baby blue is no longer taken for white.
        val warm = doubleArrayOf(1.3, 1.0, 0.68)
        val adaptive = AdaptiveLiveClassifier()
        adaptive.learn(CubeColor.WHITE, sample(pastel, CubeColor.WHITE, 1.0, warm))
        for (exposure in exposures) {
            for (color in CubeColor.entries) {
                assertEquals("$color at $exposure", color, adaptive.classify(sample(pastel, color, exposure, warm)))
            }
        }
    }

    @Test
    fun labelForCenterNamesSixCentersDistinctly() {
        val random = Random(7)
        val looks = KnockOffCubes.ALL
        var correct = 0
        var total = 0
        repeat(300) { n ->
            val look = looks[n % looks.size]
            val adaptive = AdaptiveLiveClassifier()
            val gains = doubleArrayOf(random.nextDouble(0.9, 1.12), 1.0, random.nextDouble(0.85, 1.12))
            val order = CubeColor.entries.shuffled(random)
            val labels = order.map { color ->
                val center = sample(look, color, random.nextDouble(0.65, 1.25), gains)
                adaptive.labelForCenter(center).also { adaptive.learn(it, center) }
            }
            assertEquals("labels $labels", 6, labels.toSet().size)
            correct += order.indices.count { labels[it] == order[it] }
            total += 6
        }
        println("labelForCenter on ideal knock-off centers: $correct/$total")
        assertTrue("$correct/$total", correct >= total * 0.97)

        // Even for nonsense input the six labels are distinct.
        for (garbage in listOf(List(6) { StickerSample.of(0, 0, 0) }, List(6) { StickerSample.of(128, 128, 128) }, List(6) { StickerSample.of(random.nextInt(256), random.nextInt(256), random.nextInt(256)) })) {
            val adaptive = AdaptiveLiveClassifier()
            val labels = garbage.map { adaptive.labelForCenter(it, garbage).also { label -> adaptive.learn(label, it) } }
            assertEquals(CubeColor.entries.toSet(), labels.toSet())
            assertEquals(CubeColor.entries.toSet(), adaptive.learned.keys)
        }
    }

    @Test
    fun aWhiterStickerOnTheFaceRulesOutWhiteForTheCenter() {
        // Peach under cool, slightly green light reads as white on its own; next to a real white sticker it doesn't.
        val cool = doubleArrayOf(0.8, 1.04, 1.3)
        val peach = sample(pastel, CubeColor.ORANGE, 1.0, cool)
        val white = sample(pastel, CubeColor.WHITE, 1.0, cool)
        assertEquals(CubeColor.WHITE, LiveClassifier.classify(peach))
        val face = listOf(white, peach, white, peach, peach, peach, peach, white, peach)
        assertEquals(CubeColor.WHITE, AdaptiveLiveClassifier().labelForCenter(peach))
        assertEquals(CubeColor.ORANGE, AdaptiveLiveClassifier().labelForCenter(peach, face))
        // A white center stays white among other whites.
        assertEquals(CubeColor.WHITE, AdaptiveLiveClassifier().labelForCenter(white, List(9) { if (it % 2 == 0) white else peach }))
    }

    @Test
    fun forgetAndReset() {
        val adaptive = AdaptiveLiveClassifier()
        val pink = StickerSample.ofArgb(0xF2A0B4)
        adaptive.learn(CubeColor.ORANGE, pink) // deliberately "wrong": the app decides
        assertEquals(CubeColor.ORANGE, adaptive.classify(pink))
        assertNotNull(adaptive.learned[CubeColor.ORANGE])
        adaptive.forget(CubeColor.ORANGE)
        assertNull(adaptive.learned[CubeColor.ORANGE])
        assertEquals(LiveClassifier.classify(pink), adaptive.classify(pink))
        adaptive.forget(CubeColor.ORANGE)
        for (color in CubeColor.entries) adaptive.learn(color, sample(pastel, color))
        assertEquals(6, adaptive.learned.size)
        assertEquals(CubeColor.BLUE, adaptive.labelForCenter(sample(pastel, CubeColor.BLUE)))
        adaptive.reset()
        assertTrue(adaptive.learned.isEmpty())
    }

    @Test
    fun isThreadSafe() {
        // The camera analyzer classifies while the UI thread learns, forgets and resets.
        val adaptive = AdaptiveLiveClassifier()
        val samples = CubeColor.entries.flatMap { c -> exposures.map { sample(pastel, c, it) } }
        val stop = AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val started = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        pool.execute {
            started.countDown()
            try {
                var i = 0
                while (!stop.get()) {
                    val s = samples[i++ % samples.size]
                    adaptive.classify(s)
                    adaptive.labelForCenter(s)
                    assertTrue(adaptive.learned.size <= 6)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        pool.execute {
            started.countDown()
            try {
                val random = Random(3)
                while (!stop.get()) {
                    val color = CubeColor.entries[random.nextInt(6)]
                    when (random.nextInt(10)) {
                        0 -> adaptive.reset()
                        in 1..3 -> adaptive.forget(color)
                        else -> adaptive.learn(color, sample(pastel, color, random.nextDouble(0.7, 1.2)))
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        started.await()
        Thread.sleep(300)
        stop.set(true)
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        failure.get()?.let { throw AssertionError("concurrent use failed", it) }
        // A consistent final state.
        for (color in CubeColor.entries) adaptive.learn(color, sample(pastel, color))
        for (color in CubeColor.entries) assertEquals(color, adaptive.classify(sample(pastel, color, 0.8)))
    }

    @Test
    fun isFast() {
        val adaptive = AdaptiveLiveClassifier()
        for (color in listOf(CubeColor.WHITE, CubeColor.RED, CubeColor.BLUE)) adaptive.learn(color, sample(pastel, color))
        val random = Random(9)
        val samples = List(9000) { StickerSample.of(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        samples.forEach { adaptive.classify(it) } // warm-up
        val start = System.nanoTime()
        samples.forEach { adaptive.classify(it) }
        val perFrameMs = (System.nanoTime() - start) / 1e6 / (samples.size / 9)
        println("AdaptiveLiveClassifier: %.3f ms per frame of nine stickers".format(perFrameMs))
        assertTrue("$perFrameMs ms per frame", perFrameMs < 2.0)
    }
}
