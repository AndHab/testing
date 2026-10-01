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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Knock-off cubes end to end: pastel, candy, muted, stickerless and white-bodied cubes (and a vivid
 * standard one for comparison), other pastel palettes than the user's daughter's cube, and random
 * pastel palettes, rendered like phone photos and run through the sampler, the live classifiers and
 * the resolver, the way the scanning screen uses them.
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
 *
 * The scanning screen is simulated as recommended: after each capture, the centers of all faces
 * captured so far are named jointly and learned ([AdaptiveLiveClassifier.learnCenters]). The
 * incremental alternative ([AdaptiveLiveClassifier.labelForCenter] and [AdaptiveLiveClassifier.learn])
 * is measured for comparison.
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

        fun add(other: Tally) {
            correct += other.correct
            total += other.total
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

        /** Live preview with [AdaptiveLiveClassifier] while the faces are being captured (centers learned jointly). */
        val adaptiveWhileScanning = Tally()

        /** [AdaptiveLiveClassifier] on new photos after learning the six centers with their true colors. */
        val adaptiveTrue = Tally()

        /** The same, with the six centers learned by [AdaptiveLiveClassifier.learnCenters] (the recommended flow). */
        val adaptive = Tally()

        /** [AdaptiveLiveClassifier.learnCenters] naming the six captured centers. */
        val centerLabels = Tally()

        /** [AdaptiveLiveClassifier.labelForCenter] naming the centers one by one, for comparison. */
        val incrementalLabels = Tally()

        /** [AdaptiveLiveClassifier] after learning the centers as [incrementalLabels] named them. */
        val adaptiveIncremental = Tally()

        val standardLike = Tally()

        /** Worst hue angle error of the estimated palette, over colors of design chroma 20 and up (degrees). */
        var worstHueError = 0.0

        /**
         * Worst metric hue difference (CIE delta H) of the estimated palette over colors of design
         * chroma below 20, whose hue angle is too unstable to compare.
         */
        var worstHueDifference = 0.0
        val failures = mutableListOf<String>()

        /** Live default errors, "true>read": count. */
        val liveErrors = sortedMapOf<String, Int>()

        /** Joint center label errors, "true>label": count. */
        val labelErrors = sortedMapOf<String, Int>()

        fun add(other: Stats) {
            exact.add(other.exact)
            live.add(other.live)
            adaptiveWhileScanning.add(other.adaptiveWhileScanning)
            adaptiveTrue.add(other.adaptiveTrue)
            adaptive.add(other.adaptive)
            centerLabels.add(other.centerLabels)
            incrementalLabels.add(other.incrementalLabels)
            adaptiveIncremental.add(other.adaptiveIncremental)
            standardLike.add(other.standardLike)
            worstHueError = maxOf(worstHueError, other.worstHueError)
            worstHueDifference = maxOf(worstHueDifference, other.worstHueDifference)
            failures += other.failures
            other.liveErrors.forEach { (k, v) -> liveErrors.merge(k, v, Int::plus) }
            other.labelErrors.forEach { (k, v) -> labelErrors.merge(k, v, Int::plus) }
        }

        fun report(): String = buildString {
            append("resolver exact $exact; live default $live")
            if (liveErrors.isNotEmpty()) append(" errors $liveErrors")
            append("\n    adaptive (centers learned jointly): while scanning $adaptiveWhileScanning, after six centers $adaptive;")
            append(" center labels $centerLabels")
            if (labelErrors.isNotEmpty()) append(" errors $labelErrors")
            append("\n    for comparison: true centers learned $adaptiveTrue; centers named one by one $incrementalLabels -> $adaptiveIncremental")
            append("\n    palette: standard-like $standardLike, worst hue error ${"%.1f".format(worstHueError)} deg, worst delta H ${"%.1f".format(worstHueDifference)}")
        }
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
     * Scans [cubes] random cubes of [look] (designed in the colors [design]) under [light] and checks
     * the resolver on every one; returns the accuracy figures.
     */
    private fun scanSessions(look: CubeLook, design: Map<CubeColor, Int>, light: Light, cubes: Int, random: Random): Stats {
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

            // Scanning screen: after each capture, all centers so far are named jointly and learned.
            // For comparison, one classifier learns the centers' true colors and one names each
            // center once, as it comes in.
            val adaptive = AdaptiveLiveClassifier()
            val informed = AdaptiveLiveClassifier()
            val incremental = AdaptiveLiveClassifier()
            var labels = emptyList<CubeColor>()
            captures.forEachIndexed { k, c ->
                c.samples.forEachIndexed { i, s -> stats.adaptiveWhileScanning.add(adaptive.classify(s) == c.shown[i]) }
                val captured = captures.subList(0, k + 1)
                labels = adaptive.learnCenters(captured.map { it.samples[4] }, captured.map { it.samples })
                assertEquals("labels $labels", k + 1, labels.toSet().size)
                informed.learn(c.shown[4], c.samples[4])
                val label = incremental.labelForCenter(c.samples[4], c.samples)
                stats.incrementalLabels.add(label == c.shown[4])
                incremental.learn(label, c.samples[4])
            }
            captures.forEachIndexed { k, c ->
                stats.centerLabels.add(labels[k] == c.shown[4])
                if (labels[k] != c.shown[4]) stats.labelErrors.merge("${c.shown[4].letter}>${labels[k].letter}", 1, Int::plus)
            }
            // After learning six centers: new photos of the cube, in the same session.
            for (face in Face.entries) {
                val again = capture(faces, truth, face, conditions(light, cast, random), random)
                again.samples.forEachIndexed { i, s ->
                    stats.adaptiveTrue.add(informed.classify(s) == again.shown[i])
                    stats.adaptive.add(adaptive.classify(s) == again.shown[i])
                    stats.adaptiveIncremental.add(incremental.classify(s) == again.shown[i])
                }
            }

            // Final colors.
            val analysis = ScanResolver.resolve(captures.map { it.samples })
            val asCaptured = MutableList(Facelets.COUNT) { CubeColor.WHITE }
            for (c in captures) for (p in 0 until 9) asCaptured[c.face.ordinal * 9 + p] = c.shown[p]
            val wrong = (0 until Facelets.COUNT).filter { analysis.colors[it] != truth[it] }.toSet()
            stats.exact.add(analysis.isValid && wrong.isEmpty())
            when {
                !analysis.isValid -> failures += "$look cube $n: not valid, read ${analysis.rawColors.letters()}, truth ${asCaptured.letters()}"
                analysis.rawColors != asCaptured -> failures += "$look cube $n: misread ${analysis.rawColors.letters()}, truth ${asCaptured.letters()}"
                // Classified perfectly; then only an orientation that colors cannot decide may differ, and it must be flagged.
                !analysis.uncertain.containsAll(wrong) -> failures += "$look cube $n: wrong stickers $wrong not flagged"
            }
            assertEquals(Placement.CENTER_COLORS, analysis.placement)

            // Palette estimate.
            stats.standardLike.add(analysis.palette.isStandardLike)
            for (color in CubeColor.entries) {
                if (color == CubeColor.WHITE) continue
                val expected = StickerSample.ofArgb(design.getValue(color)).lab
                val estimated = StickerSample.ofArgb(analysis.palette.colors.getValue(color)).lab
                if (expected.chroma >= HUE_CHROMA) {
                    stats.worstHueError = maxOf(stats.worstHueError, hueAngleDifference(expected, estimated))
                } else {
                    stats.worstHueDifference = maxOf(stats.worstHueDifference, metricHueDifference(expected, estimated))
                }
            }
        }
        return stats
    }

    private fun hueAngleDifference(x: Lab, y: Lab): Double {
        val d = abs(x.hue - y.hue).toDouble() % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    /**
     * CIE metric hue difference (delta H) between [x] and [y]: the hue angle difference weighted by
     * chroma, i.e. how far apart the two hues are as colors (the hue angle of a nearly grey color is
     * meaningless).
     */
    private fun metricHueDifference(x: Lab, y: Lab): Double =
        2.0 * sqrt(x.chroma.toDouble() * y.chroma) * sin(Math.toRadians(hueAngleDifference(x, y)) / 2.0)

    /**
     * Runs [look] under every light ([normalCubes] cubes in normal light, [otherCubes] in each of the
     * others), prints the figures, checks what holds for every look and returns the figures.
     * [minLive] is the accuracy the default live rules reach at least in normal light: single samples
     * of some pastel shades look exactly like a white sticker under a mild cast.
     */
    private fun runLook(look: CubeLook, seed: Int, minLive: Double, normalCubes: Int = NORMAL_CUBES, otherCubes: Int = OTHER_CUBES): Map<Light, Stats> {
        val random = Random(seed)
        val design = KnockOffCubes.DESIGN_HEX.getValue(look)
        val results = Light.entries.associateWith { scanSessions(look, design, it, if (it == Light.NORMAL) normalCubes else otherCubes, random) }
        val report = StringBuilder("Knock-off cubes, $look:\n")
        for ((light, s) in results) report.append("  ${light.name.lowercase()} light: ${s.report()}\n")
        print(report)
        for ((light, s) in results) {
            assertTrue("$look, $light light:\n${s.failures.joinToString("\n")}", s.failures.isEmpty())
            // Every cube resolves; the only possible difference is an orientation that colors cannot
            // decide (about 1 cube in 150 when every face is held at a random angle), and it is flagged.
            assertTrue("$look $light: exact ${s.exact}", s.exact.total - s.exact.correct <= maxOf(1, s.exact.total / 30))
            if (light != Light.MIXED) {
                // In one light, the six centers are named right and teach the live preview this cube's colors.
                assertTrue("$look $light: center labels ${s.centerLabels}", s.centerLabels.rate >= 0.97)
                assertTrue("$look $light: adaptive ${s.adaptive}", s.adaptive.rate >= 0.96)
            }
        }
        val normal = results.getValue(Light.NORMAL)
        assertTrue("$look: live default ${normal.live}", normal.live.rate >= minLive)
        assertTrue("$look: adaptive, true centers ${normal.adaptiveTrue}", normal.adaptiveTrue.rate >= 0.98)
        assertTrue("$look: center labels ${normal.centerLabels}", normal.centerLabels.rate >= 0.99)
        assertTrue("$look: adaptive ${normal.adaptive}", normal.adaptive.rate >= 0.98)
        return results
    }

    @Test
    fun vividStandardCube() {
        val results = runLook(KnockOffCubes.VIVID, 101, minLive = 0.99)
        for ((light, s) in results) assertEquals("$light: standard-like ${s.standardLike}", s.standardLike.total, s.standardLike.correct)
    }

    @Test
    fun pastelCube() {
        // The user's daughter's cube.
        val results = runLook(KnockOffCubes.PASTEL, 102, minLive = 0.95)
        for ((light, s) in results) {
            assertEquals("$light: standard-like ${s.standardLike}", 0, s.standardLike.correct)
            assertTrue("$light: worst hue error ${s.worstHueError}", s.worstHueError <= 15.0)
        }
    }

    @Test
    fun candyCube() {
        val results = runLook(KnockOffCubes.CANDY, 103, minLive = 0.99)
        for ((light, s) in results) assertEquals("$light: standard-like ${s.standardLike}", 0, s.standardLike.correct)
    }

    @Test
    fun mutedCube() {
        val results = runLook(KnockOffCubes.MUTED, 104, minLive = 0.99)
        for ((light, s) in results) assertEquals("$light: standard-like ${s.standardLike}", 0, s.standardLike.correct)
    }

    @Test
    fun stickerlessCube() {
        runLook(KnockOffCubes.STICKERLESS, 105, minLive = 0.99)
    }

    @Test
    fun whiteBodiedCube() {
        // Standard sticker colors on a white body: still a standard look.
        val results = runLook(KnockOffCubes.WHITE_BODY, 106, minLive = 0.99)
        for ((light, s) in results) assertEquals("$light: standard-like ${s.standardLike}", s.standardLike.total, s.standardLike.correct)
    }

    @Test
    fun otherPastelCubes() {
        // Pastel palettes other than the one above, with the shades that are hardest to tell from
        // white or from each other one sample at a time (lavender, very light yellow and sky blue,
        // greyish blue, a pastel orange close to yellow). Minimum live accuracy per palette: what the
        // default rules can do; the adaptive classifier and the resolver must not depend on the palette.
        val looks = listOf(
            KnockOffCubes.LAVENDER to 0.88,
            KnockOffCubes.CORAL to 0.92,
            KnockOffCubes.LIGHT_YELLOW to 0.82,
            KnockOffCubes.MINT_SKY to 0.85,
            KnockOffCubes.MACARON to 0.88,
            KnockOffCubes.GREYISH to 0.7,
            KnockOffCubes.PALE to 0.55,
        )
        looks.forEachIndexed { i, (look, minLive) ->
            val results = runLook(look, 110 + i, minLive, normalCubes = 25, otherCubes = 8)
            for ((light, s) in results) {
                assertEquals("$look $light: standard-like ${s.standardLike}", 0, s.standardLike.correct)
                assertTrue("$look $light: worst hue error ${s.worstHueError}", s.worstHueError <= 15.0)
                // Colors of chroma below 20 (the greyish blue has 9): their hue angle is unstable, so
                // their hue is compared as a color difference. Sticker-to-sticker variation alone moves
                // them by about 3 in a*b*; showing the greyish blue greyish green would be 12.
                assertTrue("$look $light: worst delta H ${s.worstHueDifference}", s.worstHueDifference <= 8.0)
            }
        }
    }

    @Test
    fun randomPastelCubes() {
        // Pastel palettes drawn at random (see KnockOffCubes.randomPastel), so that nothing here is
        // tuned to a particular palette: a few cubes of each, in normal light.
        val random = Random(120)
        val total = Stats()
        val worst = mutableListOf<String>()
        repeat(RANDOM_PALETTES) { p ->
            val design = KnockOffCubes.randomPastel(random)
            val look = CubeLook("random pastel $p", KnockOffCubes.captured(design, 0.66))
            val s = scanSessions(look, design, Light.NORMAL, RANDOM_CUBES, random)
            total.add(s)
            if (s.centerLabels.rate < 1.0 || s.live.rate < 0.8) {
                worst += "$look ${design.values.joinToString { "%06X".format(it) }}: live ${s.live}, center labels ${s.centerLabels}"
            }
            assertEquals("$look: standard-like ${s.standardLike}", 0, s.standardLike.correct)
        }
        println("Random pastel cubes ($RANDOM_PALETTES palettes x $RANDOM_CUBES cubes, normal light): ${total.report()}")
        if (worst.isNotEmpty()) println("  hardest: ${worst.joinToString("\n    ")}")
        assertTrue(total.failures.joinToString("\n"), total.exact.total - total.exact.correct <= maxOf(1, total.exact.total / 30))
        assertTrue(total.failures.joinToString("\n"), total.failures.isEmpty())
        assertTrue("center labels ${total.centerLabels}", total.centerLabels.rate >= 0.98)
        assertTrue("adaptive ${total.adaptive}", total.adaptive.rate >= 0.98)
        assertTrue("worst hue error ${total.worstHueError}", total.worstHueError <= 15.0)
    }

    private companion object {
        const val IMAGE_SIZE = 128
        const val NORMAL_CUBES = 60
        const val OTHER_CUBES = 15
        const val RANDOM_PALETTES = 30
        const val RANDOM_CUBES = 3

        /** Hue angles are compared for design colors of at least this chroma; below, only delta H. */
        const val HUE_CHROMA = 20f
    }
}
