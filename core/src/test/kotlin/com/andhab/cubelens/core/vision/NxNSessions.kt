package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
import kotlin.math.max
import kotlin.random.Random

/**
 * Simulated scanning sessions of N×N cubes for tests: a random scramble ([NxNScrambler]), each face
 * rendered like a phone photo ([SyntheticFaces.render] with a size) after being held at a random
 * quarter turn, sampled with [GridSampler], in the app's guided order F, R, B, L, U, D.
 *
 * The light of a session is as in [KnockOffCubeTest]: every photo has its own exposure (0.65x to
 * 1.25x) and auto white balance drift, under
 *  - normal light: neutral, mildly warm or mildly cool (what auto white balance usually leaves);
 *  - warm light: incandescent light that auto white balance only partly corrects;
 *  - cool light: shade or overcast daylight;
 *  - mixed light: every photo with its own mild cast.
 */
object NxNSessions {

    /** The app's guided scan order. */
    val GUIDED: List<Face> = listOf(Face.F, Face.R, Face.B, Face.L, Face.U, Face.D)

    /** The light of a scanning session. */
    enum class Light { NORMAL, WARM, COOL, MIXED }

    private val mildCasts = listOf(doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(1.12, 1.0, 0.8), doubleArrayOf(0.88, 1.0, 1.16))

    /** White balance gains of a whole session under [light] (MIXED: chosen per photo). */
    fun sessionCast(light: Light, random: Random): DoubleArray = when (light) {
        Light.NORMAL -> mildCasts[random.nextInt(mildCasts.size)]
        Light.WARM -> doubleArrayOf(1.3, 1.0, 0.68)
        Light.COOL -> doubleArrayOf(0.84, 1.0, 1.24)
        Light.MIXED -> doubleArrayOf(1.0, 1.0, 1.0)
    }

    /** Conditions of one photo in a session under [light] with white balance [cast]. */
    fun conditions(light: Light, cast: DoubleArray, random: Random): SyntheticFaces.Conditions {
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

    /**
     * Guide size (pixels) for rendering an [n]x[n] face in tests: about 22 pixels per cell, at least
     * 100 pixels (a phone's analysis frame has about twice that; smaller cells are the harder case).
     */
    fun guideSize(n: Int): Int = max(100, 22 * n)

    /** A random scramble of an [n]x[n] cube in [scheme]. */
    fun scrambled(n: Int, random: Random, scheme: Map<Face, CubeColor> = ColorScheme.STANDARD.centers): NxNCube =
        NxNCube.solved(n, scheme).apply(NxNScrambler.randomMoves(n, random))

    /** The stickers of [face] of the [n]x[n] cube [colors] after [quarterTurns] clockwise quarter turns, row-major. */
    fun faceAsHeld(n: Int, colors: List<CubeColor>, face: Face, quarterTurns: Int): List<CubeColor> {
        val per = n * n
        return NxNOrientationFixer.rotateFace(n, colors, face, quarterTurns).subList(face.ordinal * per, face.ordinal * per + per)
    }

    /** One scanning session: what each scan showed (row-major as captured), its samples and photo conditions, in scan order. */
    class Session(
        val n: Int,
        val faces: List<Face>,
        val shown: List<List<CubeColor>>,
        val scans: List<List<StickerSample>>,
        val heldAt: List<Int>,
        val conditions: List<SyntheticFaces.Conditions>,
        /** White balance of the session's light (see [sessionCast]). */
        val cast: DoubleArray,
    )

    /**
     * Stickers of [session] (scan, position) that the sampler misread: whose sample is nearer
     * ([ColorMath.deltaE]) to another color of [look] as rendered at that place than to its own.
     */
    fun samplerErrors(session: Session, look: CubeLook): List<Pair<Int, Int>> {
        val n = session.n
        val errors = ArrayList<Pair<Int, Int>>()
        for (k in session.scans.indices) {
            val c = session.conditions[k]
            for (p in 0 until n * n) {
                val light = c.brightness * (1.0 + c.gradient.first * (p % n - (n - 1) / 2.0) / n + c.gradient.second * (p / n - (n - 1) / 2.0) / n)
                val nearest = CubeColor.entries.minBy { color ->
                    val linear = look.linear.getValue(color)
                    val rendered = IntArray(3) { ColorMath.linearToSrgb(linear[it] * light * c.whiteBalance[it]) }
                    ColorMath.deltaE(session.scans[k][p].lab, StickerSample.of(rendered[0], rendered[1], rendered[2]).lab)
                }
                if (nearest != session.shown[k][p]) errors += k to p
            }
        }
        return errors
    }

    /**
     * Photographs the [n]x[n] cube [colors] face by face in [order], each face held at a random quarter
     * turn (or [heldAt] when given), under [light] (with the session white balance [cast] when given,
     * e.g. to take more photos in the light of an earlier session).
     */
    fun scan(
        renderer: SyntheticFaces,
        n: Int,
        colors: List<CubeColor>,
        light: Light,
        random: Random,
        order: List<Face> = GUIDED,
        heldAt: List<Int>? = null,
        cast: DoubleArray = sessionCast(light, random),
    ): Session {
        val turns = heldAt ?: List(6) { random.nextInt(4) }
        val shown = order.mapIndexed { k, face -> faceAsHeld(n, colors, face, turns[k]) }
        val photos = shown.map { conditions(light, cast, random) }
        val scans = shown.mapIndexed { k, face ->
            val (image, guide) = renderer.render(n, face, photos[k], guideSize(n))
            GridSampler.sample(image, guide, 0, n)
        }
        return Session(n, order, shown, scans, turns, photos, cast)
    }

    /** How a resolved scan compares with the truth. */
    enum class Outcome {
        /** Valid and exactly the scanned cube. */
        EXACT,

        /** Valid, not the scanned cube, but every differing sticker flagged as uncertain (an ambiguous reading). */
        FLAGGED,

        /** Valid, not the scanned cube, with an unflagged wrong sticker: a silent failure. */
        WRONG,

        /** Not a valid cube. */
        INVALID,
    }

    fun outcome(analysis: NxNScanAnalysis, truth: List<CubeColor>): Outcome {
        if (!analysis.isValid) return Outcome.INVALID
        val wrong = truth.indices.filter { analysis.colors[it] != truth[it] }
        return when {
            wrong.isEmpty() -> Outcome.EXACT
            analysis.uncertain.containsAll(wrong) -> Outcome.FLAGGED
            else -> Outcome.WRONG
        }
    }

    /** Counts of [Outcome]s, with a few example failures. */
    class Tally {
        val counts = IntArray(Outcome.entries.size)
        val failures = mutableListOf<String>()
        var uncertain = 0L
        var cubes = 0
        var nanos = 0L

        fun add(outcome: Outcome, analysis: NxNScanAnalysis, description: () -> String, elapsedNanos: Long) {
            counts[outcome.ordinal]++
            cubes++
            uncertain += analysis.uncertain.size
            nanos += elapsedNanos
            if (outcome == Outcome.WRONG || outcome == Outcome.INVALID) failures += description()
        }

        operator fun get(outcome: Outcome): Int = counts[outcome.ordinal]

        /** Valid and right, or every wrong sticker flagged. */
        val resolved: Int get() = this[Outcome.EXACT] + this[Outcome.FLAGGED]

        override fun toString(): String =
            "exact ${this[Outcome.EXACT]}/$cubes, ambiguous+flagged ${this[Outcome.FLAGGED]}, invalid ${this[Outcome.INVALID]}, " +
                "wrong ${this[Outcome.WRONG]}; uncertain stickers per cube ${"%.1f".format(uncertain.toDouble() / maxOf(1, cubes))}, " +
                "resolve ${"%.1f".format(nanos / 1e6 / maxOf(1, cubes))} ms per cube"
    }
}

/** [colors] of an [n]x[n] cube as letters, one group per face. */
fun List<CubeColor>.nxnLetters(n: Int): String = joinToString("") { it.letter.toString() }.chunked(n * n).joinToString(" ")
