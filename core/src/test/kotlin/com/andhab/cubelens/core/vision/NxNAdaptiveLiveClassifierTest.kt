package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [AdaptiveLiveClassifier.learnFaces] in simulated N×N scanning sessions ([NxNSessions]): after every
 * capture the scan screen passes all faces captured so far, and the live preview classifies stickers
 * with what was learned.
 */
class NxNAdaptiveLiveClassifierTest {

    private class Rate {
        var correct = 0
        var total = 0
        fun add(ok: Boolean) {
            total++
            if (ok) correct++
        }

        val rate: Double get() = if (total == 0) 1.0 else correct.toDouble() / total
        override fun toString() = "%.2f%% (%d/%d)".format(100 * rate, correct, total)
    }

    /**
     * Scans [cubes] cubes of size [n] and [look]; returns the preview accuracy while scanning, the
     * accuracy on new photos after all six faces, center names (odd sizes), and when six colors were
     * first learned (even sizes, faces captured).
     */
    private fun sessions(n: Int, look: CubeLook, cubes: Int, seed: Int): String {
        val random = Random(seed)
        val renderer = SyntheticFaces(random, look)
        val whileScanning = Rate()
        val after = Rate()
        val defaults = Rate()
        val centerNames = Rate()
        val learnedAt = mutableListOf<Int>()
        repeat(cubes) {
            val truth = NxNSessions.scrambled(n, random).toColors()
            val session = NxNSessions.scan(renderer, n, truth, NxNSessions.Light.NORMAL, random)
            val adaptive = AdaptiveLiveClassifier()
            var firstLearned = 0
            for (k in 0 until 6) {
                session.scans[k].forEachIndexed { p, s ->
                    whileScanning.add(adaptive.classify(s) == session.shown[k][p])
                    defaults.add(LiveClassifier.classify(s) == session.shown[k][p])
                }
                val captured = session.scans.subList(0, k + 1)
                val names = adaptive.learnFaces(captured, n)
                if (n % 2 == 1) {
                    val center = (n / 2) * n + n / 2
                    assertEquals(AdaptiveLiveClassifier().learnCenters(captured.map { it[center] }, captured), names)
                    assertEquals(k + 1, names!!.toSet().size)
                    if (k == 5) for (f in 0 until 6) centerNames.add(names[f] == session.shown[f][center])
                } else {
                    assertNull(names)
                    if (firstLearned == 0 && adaptive.learned.size == 6) firstLearned = k + 1
                }
            }
            if (n % 2 == 0) {
                assertEquals("six faces of a ${n}x$n cube teach all six colors", 6, adaptive.learned.size)
                learnedAt += firstLearned
            }
            // New photos of the same cube in the same session's light.
            val again = NxNSessions.scan(renderer, n, truth, NxNSessions.Light.NORMAL, random, cast = session.cast)
            again.scans.forEachIndexed { k, scan -> scan.forEachIndexed { p, s -> after.add(adaptive.classify(s) == again.shown[k][p]) } }
        }
        val learning = if (n % 2 == 1) "center names $centerNames" else "six colors learned after ${learnedAt.groupingBy { it }.eachCount().toSortedMap()} faces (count of cubes)"
        val line = "${n}x$n $look: default rules $defaults, adaptive while scanning $whileScanning, after six faces $after; $learning"
        if (after.rate < 0.98) failures += line
        if (n % 2 == 1 && centerNames.rate < 0.98) failures += line
        return line
    }

    private val failures = mutableListOf<String>()

    @Test
    fun learnsTheColorsOfEverySize() {
        val report = StringBuilder("AdaptiveLiveClassifier.learnFaces (normal light):\n")
        for (look in listOf(KnockOffCubes.VIVID, KnockOffCubes.PASTEL, KnockOffCubes.LAVENDER)) {
            for (n in listOf(2, 4, 5, 6, 7)) report.append("  ").append(sessions(n, look, CUBES, 300 * n + look.name.length)).append('\n')
        }
        print(report)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun evenSizesLearnNothingUntilTheEvidenceIsClear() {
        val random = Random(3)
        val look = KnockOffCubes.PASTEL
        val adaptive = AdaptiveLiveClassifier()
        // One 2x2 face: four stickers are not enough.
        val renderer = SyntheticFaces(random, look)
        val truth = NxNSessions.scrambled(2, random).toColors()
        val session = NxNSessions.scan(renderer, 2, truth, NxNSessions.Light.NORMAL, random)
        assertNull(adaptive.learnFaces(session.scans.take(1), 2))
        assertTrue(adaptive.learned.isEmpty())
        // Grey surfaces never teach colors, and a later real capture replaces everything.
        val grey = List(6) { k -> List(16) { StickerSample.of(60 + 30 * k, 60 + 30 * k, 60 + 30 * k) } }
        assertNull(adaptive.learnFaces(grey, 4))
        assertTrue(adaptive.learned.isEmpty())
        // Nothing captured: nothing learned, the default rules apply.
        assertNull(adaptive.learnFaces(emptyList(), 4))
        assertTrue(adaptive.learned.isEmpty())
        val s = StickerSample.ofArgb(0xF2A0B4)
        assertEquals(LiveClassifier.classify(s), adaptive.classify(s))
    }

    @Test
    fun rejectsMalformedArguments() {
        val adaptive = AdaptiveLiveClassifier()
        val face = List(16) { StickerSample.of(200, 30, 30) }
        assertThrows(IllegalArgumentException::class.java) { adaptive.learnFaces(listOf(face), 1) }
        assertThrows(IllegalArgumentException::class.java) { adaptive.learnFaces(listOf(face), 11) }
        assertThrows(IllegalArgumentException::class.java) { adaptive.learnFaces(List(7) { face }, 4) }
        assertThrows(IllegalArgumentException::class.java) { adaptive.learnFaces(listOf(face), 5) }
        // Odd sizes delegate to learnCenters: names for every face.
        val faces5 = List(3) { k -> List(25) { StickerSample.ofArgb(listOf(0xF7F5EE, 0xF2A0B4, 0x93BFEA)[k]) } }
        assertEquals(3, adaptive.learnFaces(faces5, 5)!!.toSet().size)
        assertEquals(3, adaptive.learned.size)
    }

    private companion object {
        const val CUBES = 6
    }
}
