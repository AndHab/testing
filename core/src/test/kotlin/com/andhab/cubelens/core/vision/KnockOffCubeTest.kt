package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Knock-off cubes end to end: pastel, candy, muted, stickerless and white-bodied cubes (and a vivid
 * standard one for comparison), rendered like phone photos and run through the sampler, the live
 * classifiers and the resolver, the way the scanning screen uses them.
 *
 * Each cube is one scanning session: the six faces are captured in random order, each held at a
 * random angle, each photo with its own exposure (0.65x to 1.25x) and auto white balance drift, under
 * the session's light:
 *  - normal: neutral, mildly warm or mildly cool light (what auto white balance usually leaves);
 *  - warm: incandescent light that auto white balance only partly corrects;
 *  - cool: shade or overcast daylight;
 *  - mixed: every photo with its own mild cast, as when walking around with the cube.
 * Photos have mild sensor noise, uneven illumination, a few specular highlights and a slightly
 * misaligned guide.
 */
class KnockOffCubeTest {

    private enum class Light { NORMAL, WARM, COOL, MIXED }

    private val mildCasts = listOf(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.12, 1.0, 0.8), doubleArrayOf(0.88, 1.0, 1.16))

    /** White balance gains of a whole session under [light] (MIXED: chosen per photo). */
    private fun sessionCast(light: Light, random: Random): DoubleArray = when (light) {
        Light.NORMAL -> mildCasts[random.nextInt(mildCasts.size)]
        Light.WARM -> doubleArrayOf(1.3, 1.0, 0.68)
        Light.COOL -> doubleArrayOf(0.84, 1.0, 1.24)
        Light.MIXED -> doubleArrayOf(1.0, 1.0, 1.0)
    }

    /** Conditions of one photo in a session under [light] with white balance [cast]. */
    private fun conditions(light: Light, cast: DoubleArray, random: Random): SyntheticFaces.Conditions {
        val wb = if (light == Light.MIXED) mildCasts[random.nextInt(mildCasts.size)] else cast
        val drift = DoubleArray(3) { random.nextDouble(0.96, 1.04) }
        return SyntheticFaces.Conditions(
            brightness = random.nextDouble(0.65, 1.25),
            whiteBalance = DoubleArray(3) { wb[it] * drift[it] },
            gradient = random.nextDouble(-0.2, 0.2) to random.nextDouble(-0.2, 0.2),
            shift = random.nextDouble(-0.03, 0.03) to random.nextDouble(-0.03, 0.03),
            scale = random.nextDouble(0.95, 1.05),
            rotationDegrees = random.nextDouble(-3.0, 3.0),
            keystone = random.nextDouble(-0.04, 0.04) to random.nextDouble(-0.04, 0.04),
            highlights = random.nextInt(0, 3),
            noise = random.nextDouble(2.0, 5.0),
        )
    }

    /** Running accuracy. */
    private class Tally {
        var correct = 0
        var total = 0
        fun add(ok: Boolean) {
            total++
            if (ok) correct++
        }

        val rate: Double get() = if (total == 0) 1.0 else correct.toDouble() / total
        override fun toString(): String = "%.2f%% (%d/%d)".format(100.0 * rate, correct, total)
    }

    /** Figures of one look under one light. */
    private class Stats {
        /** Resolver: valid and exactly the scanned cube. */
        val exact = Tally()

        /** Live preview, default rules ([LiveClassifier]). */
        val live = Tally()

        /** Live preview with [AdaptiveLiveClassifier] while the faces are being captured. */
        val adaptiveWhileScanning = Tally()

        /** [AdaptiveLiveClassifier] on new photos after learning the six centers with their true colors. */
        val adaptiveLearned = Tally()

        /** The same, with the centers learned as [AdaptiveLiveClassifier.labelForCenter] named them. */
        val adaptiveLabelled = Tally()

        /** [AdaptiveLiveClassifier.labelForCenter] naming the captured centers. */
        val centerLabels = Tally()

        val standardLike = Tally()
        var worstHueError = 0.0
        val failures = mutableListOf<String>()

        /** Live default errors, "true>read": count. */
        val liveErrors = sortedMapOf<String, Int>()

        /** Center label errors, "true>label@colors learned before": count. */
        val labelErrors = sortedMapOf<String, Int>()
    }

    /** One captured face: which face, what it showed (row-major as captured) and the samples. */
    private class Capture(val face: Face, val shown: List<CubeColor>, val samples: List<StickerSample>)

    private fun randomCube(random: Random): FaceletCube = FaceletCube.scrambled(List(random.nextInt(20, 31)) { Move.entries[random.nextInt(18)] })

    private fun capture(faces: SyntheticFaces, colors: List<CubeColor>, face: Face, conditions: SyntheticFaces.Conditions, random: Random): Capture {
        val shown = OrientationFixer.rotateFace(colors, face, random.nextInt(4)).subList(face.ordinal * 9, face.ordinal * 9 + 9)
        val (image, guide) = faces.render(shown, conditions, IMAGE_SIZE)
        return Capture(face, shown, GridSampler.sample(image, guide))
    }

    /**
     * Scans [cubes] random cubes of [look] under [light] and checks the resolver on every one;
     * returns the accuracy figures.
     */
    private fun scanSessions(look: CubeLook, light: Light, cubes: Int, random: Random): Stats {
        val faces = SyntheticFaces(random, look)
        val stats = Stats()
        val failures = stats.failures
        repeat(cubes) { n ->
            val cube = randomCube(random)
            val truth = cube.toColors()
            val cast = sessionCast(light, random)
            val captures = Face.entries.shuffled(random).map { capture(faces, truth, it, conditions(light, cast, random), random) }

            // Live preview with the default rules.
            for (c in captures) {
                c.samples.forEachIndexed { i, s ->
                    val read = LiveClassifier.classify(s)
                    stats.live.add(read == c.shown[i])
                    if (read != c.shown[i]) stats.liveErrors.merge("${c.shown[i].letter}>${read.letter}", 1, Int::plus)
                }
            }

            // Scanning screen: each captured center is named by labelForCenter (given its face) and
            // learned; for comparison, a second classifier learns the centers' true colors.
            val adaptive = AdaptiveLiveClassifier()
            val informed = AdaptiveLiveClassifier()
            for (c in captures) {
                c.samples.forEachIndexed { i, s -> stats.adaptiveWhileScanning.add(adaptive.classify(s) == c.shown[i]) }
                val label = adaptive.labelForCenter(c.samples[4], c.samples)
                stats.centerLabels.add(label == c.shown[4])
                if (label != c.shown[4]) stats.labelErrors.merge("${c.shown[4].letter}>${label.letter}@${adaptive.learned.size}", 1, Int::plus)
                adaptive.learn(label, c.samples[4])
                informed.learn(c.shown[4], c.samples[4])
            }
            // After learning six centers: new photos of the cube, in the same session.
            for (face in Face.entries) {
                val again = capture(faces, truth, face, conditions(light, cast, random), random)
                again.samples.forEachIndexed { i, s ->
                    stats.adaptiveLearned.add(informed.classify(s) == again.shown[i])
                    stats.adaptiveLabelled.add(adaptive.classify(s) == again.shown[i])
                }
            }

            // Final colors.
            val analysis = ScanResolver.resolve(captures.map { it.samples })
            val asCaptured = MutableList(Facelets.COUNT) { CubeColor.WHITE }
            for (c in captures) for (p in 0 until 9) asCaptured[c.face.ordinal * 9 + p] = c.shown[p]
            val wrong = (0 until Facelets.COUNT).filter { analysis.colors[it] != truth[it] }.toSet()
            stats.exact.add(analysis.isValid && wrong.isEmpty())
            when {
                !analysis.isValid -> failures += "cube $n: not valid, read ${analysis.rawColors.letters()}, truth ${asCaptured.letters()}"
                analysis.rawColors != asCaptured -> failures += "cube $n: misread ${analysis.rawColors.letters()}, truth ${asCaptured.letters()}"
                // Classified perfectly; then only an orientation that colors cannot decide may differ, and it must be flagged.
                !analysis.uncertain.containsAll(wrong) -> failures += "cube $n: wrong stickers $wrong not flagged"
            }
            assertEquals(Placement.CENTER_COLORS, analysis.placement)

            // Palette estimate.
            stats.standardLike.add(analysis.palette.isStandardLike)
            val design = KnockOffCubes.DESIGN_HEX.getValue(look)
            for (color in CubeColor.entries) {
                if (color == CubeColor.WHITE) continue
                val expected = StickerSample.ofArgb(design.getValue(color)).lab.hue
                val estimated = StickerSample.ofArgb(analysis.palette.colors.getValue(color)).lab.hue
                stats.worstHueError = maxOf(stats.worstHueError, hueDifference(expected, estimated))
            }
        }
        return stats
    }

    private fun hueDifference(x: Float, y: Float): Double {
        val d = abs(x - y).toDouble() % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /**
     * Runs [look] under every light ([NORMAL_CUBES] cubes in normal light, [OTHER_CUBES] in each of
     * the others), prints the figures, checks what holds for every look and returns the figures.
     */
    private fun runLook(look: CubeLook, seed: Int): Map<Light, Stats> {
        val random = Random(seed)
        val results = Light.entries.associateWith { scanSessions(look, it, if (it == Light.NORMAL) NORMAL_CUBES else OTHER_CUBES, random) }
        val report = StringBuilder("Knock-off cubes, $look:\n")
        for ((light, s) in results) {
            report.append("  ${light.name.lowercase()} light: resolver exact ${s.exact}; live default ${s.live}")
            if (s.liveErrors.isNotEmpty()) report.append(" errors ${s.liveErrors}")
            report.append("\n    adaptive: while scanning ${s.adaptiveWhileScanning}, after learning the 6 centers ${s.adaptiveLearned}")
            report.append(" (learned as labelForCenter named them ${s.adaptiveLabelled}); center labels ${s.centerLabels}")
            if (s.labelErrors.isNotEmpty()) report.append(" errors ${s.labelErrors}")
            report.append("\n    palette: standard-like ${s.standardLike}, worst hue error ${"%.1f".format(s.worstHueError)} deg\n")
        }
        print(report)
        for ((light, s) in results) {
            assertTrue("$look, $light light:\n${s.failures.joinToString("\n")}", s.failures.isEmpty())
            // Every cube resolves; the only possible difference is an orientation that colors cannot
            // decide (about 1 cube in 150 when every face is held at a random angle), and it is flagged.
            assertTrue("$look $light: exact ${s.exact}", s.exact.total - s.exact.correct <= maxOf(1, s.exact.total / 30))
        }
        val normal = results.getValue(Light.NORMAL)
        assertTrue("$look: live default ${normal.live}", normal.live.rate >= 0.95)
        assertTrue("$look: adaptive after learning ${normal.adaptiveLearned}", normal.adaptiveLearned.rate >= 0.99)
        assertTrue("$look: center labels ${normal.centerLabels}", normal.centerLabels.rate >= 0.98)
        assertTrue("$look: adaptive, labelled centers ${normal.adaptiveLabelled}", normal.adaptiveLabelled.rate >= 0.98)
        return results
    }

    @Test
    fun vividStandardCube() {
        val results = runLook(KnockOffCubes.VIVID, 101)
        val normal = results.getValue(Light.NORMAL)
        assertTrue("live ${normal.live}", normal.live.rate >= 0.99)
        for ((light, s) in results) assertEquals("$light: standard-like ${s.standardLike}", s.standardLike.total, s.standardLike.correct)
    }

    @Test
    fun pastelCube() {
        val results = runLook(KnockOffCubes.PASTEL, 102)
        for ((light, s) in results) {
            assertEquals("$light: standard-like ${s.standardLike}", 0, s.standardLike.correct)
            assertTrue("$light: worst hue error ${s.worstHueError}", s.worstHueError <= 15.0)
        }
    }

    @Test
    fun candyCube() {
        val results = runLook(KnockOffCubes.CANDY, 103)
        for ((light, s) in results) assertEquals("$light: standard-like ${s.standardLike}", 0, s.standardLike.correct)
    }

    @Test
    fun mutedCube() {
        val results = runLook(KnockOffCubes.MUTED, 104)
        for ((light, s) in results) assertEquals("$light: standard-like ${s.standardLike}", 0, s.standardLike.correct)
    }

    @Test
    fun stickerlessCube() {
        runLook(KnockOffCubes.STICKERLESS, 105)
    }

    @Test
    fun whiteBodiedCube() {
        // Standard sticker colors on a white body: still a standard look.
        val results = runLook(KnockOffCubes.WHITE_BODY, 106)
        for ((light, s) in results) assertEquals("$light: standard-like ${s.standardLike}", s.standardLike.total, s.standardLike.correct)
    }

    private companion object {
        const val IMAGE_SIZE = 128
        const val NORMAL_CUBES = 60
        const val OTHER_CUBES = 15
    }
}
