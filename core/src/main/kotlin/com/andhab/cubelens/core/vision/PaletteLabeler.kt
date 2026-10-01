package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor

/**
 * Names the colors of a cube by how they relate to each other, not by how close they are to fixed
 * reference colors.
 *
 * Absolute references fail on unusual cubes: a pastel blue is closer to a standard white than to a
 * standard blue, and a pink "red" is far from any standard red. What every cube keeps is the order of
 * its colors around the color wheel: red, orange, yellow, green, blue and back to red (pink and
 * magenta reds included). So a labelling is scored by
 *
 *  - how white-like the color called white is ([LiveClassifier.whiteDistance], cast-aware);
 *  - how far each other color's hue is from its usual hue band ([LiveClassifier.costs]), a soft
 *    preference only;
 *  - how badly the hues break the cyclic order of the wheel (strong): consecutive colors must follow
 *    each other in the right direction, with the gap measured as the hue difference closest to the
 *    usual one;
 *  - for a whole cube, red being lighter than orange (weak: red is the darker of the two on every
 *    cube, standard or not).
 *
 * For a whole cube ([labelAll]) all six candidates for white are tried; each hypothesis white-balances
 * the other colors with the candidate before reading their hues, which removes a common color cast.
 */
internal object PaletteLabeler {

    /** The chromatic colors in hue order around the color wheel. */
    private val WHEEL = listOf(CubeColor.RED, CubeColor.ORANGE, CubeColor.YELLOW, CubeColor.GREEN, CubeColor.BLUE)

    /** Typical hue angle (degrees) of each [WHEEL] color, used for the expected gaps between them. */
    private val ANCHOR = doubleArrayOf(20.0, 60.0, 95.0, 150.0, 270.0)

    /** Smallest acceptable hue step (degrees) from one color to the next around the wheel. */
    private const val MIN_GAP = 3.0

    /** Degrees below [MIN_GAP] per unit of (squared) order cost. */
    private const val ORDER_SOFTNESS = 5.0

    /** [labelAll]: L* by which red is lighter than orange per unit of (squared) cost. */
    private const val RED_ORANGE_LIGHTNESS_SOFTNESS = 10.0

    /**
     * [labelOne]: warmth ([LiveClassifier.warmth]) up to which a white reading costs nothing extra
     * (mildly warm light), and the softness of the cost beyond.
     */
    private const val MILD_WARMTH = 0.45
    private const val WARMTH_SOFTNESS = 0.35

    /** White-balance gains of a hypothesis are clamped to this range. */
    private const val MIN_GAIN = 0.4
    private const val MAX_GAIN = 2.5

    private val wheelIndex: IntArray = IntArray(CubeColor.entries.size) { -1 }.also { index ->
        WHEEL.forEachIndexed { i, c -> index[c.ordinal] = i }
    }

    /** All orderings of the five [WHEEL] positions. */
    private val PERMUTATIONS: List<IntArray> = permutations(5)

    /**
     * Labels six distinct colors given in linear RGB (e.g. the lighting-compensated sticker colors of
     * a scanned cube) with the six [CubeColor]s: element k of the result names [linear] element k, and
     * the result is a permutation of [CubeColor.entries].
     */
    fun labelAll(linear: List<DoubleArray>): List<CubeColor> {
        require(linear.size == 6 && linear.all { it.size == 3 }) { "Need six linear RGB colors" }
        val whiteCost = DoubleArray(6) { k ->
            val d = LiveClassifier.whiteDistance(linear[k][0], linear[k][1], linear[k][2])
            if (d.isFinite()) d * d else UNUSABLE
        }
        var bestCost = Double.POSITIVE_INFINITY
        var best: IntArray? = null // color ordinal per input
        val others = IntArray(5)
        val costs = Array(5) { DoubleArray(6) }
        val hues = DoubleArray(5)
        val lightness = DoubleArray(5)
        val byWheel = DoubleArray(5)
        for (white in (0 until 6).sortedBy { whiteCost[it] }) {
            if (whiteCost[white] >= bestCost) break
            val gains = neutralizingGains(linear[white])
            var n = 0
            for (k in 0 until 6) {
                if (k == white) continue
                others[n] = k
                val c = linear[k]
                val r = c[0] * gains[0]
                val g = c[1] * gains[1]
                val b = c[2] * gains[2]
                val lab = ColorMath.linearToLab(r, g, b)
                costs[n] = LiveClassifier.costs(LiveClassifier.whiteDistance(r, g, b), lab)
                hues[n] = lab.hue.toDouble()
                lightness[n] = lab.l.toDouble()
                n++
            }
            for (perm in PERMUTATIONS) {
                // perm[j]: wheel position given to others[j].
                var cost = whiteCost[white]
                for (j in 0 until 5) cost += costs[j][WHEEL[perm[j]].ordinal]
                if (cost >= bestCost) continue
                var redLightness = 0.0
                var orangeLightness = 0.0
                for (j in 0 until 5) {
                    byWheel[perm[j]] = hues[j]
                    if (perm[j] == 0) redLightness = lightness[j]
                    if (perm[j] == 1) orangeLightness = lightness[j]
                }
                cost += orderCost(byWheel)
                if (redLightness > orangeLightness) {
                    val x = (redLightness - orangeLightness) / RED_ORANGE_LIGHTNESS_SOFTNESS
                    cost += x * x
                }
                if (cost < bestCost) {
                    bestCost = cost
                    best = IntArray(6).also { labels ->
                        labels[white] = CubeColor.WHITE.ordinal
                        for (j in 0 until 5) labels[others[j]] = WHEEL[perm[j]].ordinal
                    }
                }
            }
        }
        val labels = best ?: return CubeColor.entries.toList() // only for non-finite input
        return labels.map { CubeColor.entries[it] }
    }

    /**
     * The best color among [allowed] for [sample], given the CIELAB values of colors already named
     * ([known]); e.g. the center of a newly captured face, when the other captured centers have been
     * named already. [whiteRuledOut]: other evidence says the sample is not white (white is then
     * chosen only if nothing else is allowed). [allowed] must not be empty.
     */
    fun labelOne(
        sample: StickerSample,
        known: Map<CubeColor, Lab>,
        allowed: Collection<CubeColor>,
        whiteRuledOut: Boolean = false,
    ): CubeColor {
        require(allowed.isNotEmpty()) { "No color to choose from" }
        val costs = LiveClassifier.costs(sample)
        val hue = sample.lab.hue.toDouble()
        val byWheel = DoubleArray(5) { Double.NaN }
        for ((color, lab) in known) {
            val w = wheelIndex[color.ordinal]
            if (w >= 0) byWheel[w] = lab.hue.toDouble()
        }
        var best = allowed.first()
        var bestCost = Double.POSITIVE_INFINITY
        for (color in allowed) {
            var cost = costs[color.ordinal]
            if (color == CubeColor.WHITE) {
                // Calling it white implies the light's cast; beyond a mild warm cast that gets
                // unlikely, and a light chromatic sticker (peach under cool light) explains it better.
                val excess = (LiveClassifier.warmth(sample) - MILD_WARMTH).coerceAtLeast(0.0) / WARMTH_SOFTNESS
                cost += excess * excess
                if (whiteRuledOut) cost += RULED_OUT
            }
            val w = wheelIndex[color.ordinal]
            if (w >= 0) {
                val previous = byWheel[w]
                byWheel[w] = hue
                cost += orderCost(byWheel)
                byWheel[w] = previous
            }
            if (cost < bestCost) {
                bestCost = cost
                best = color
            }
        }
        return best
    }

    /**
     * How badly [hues] (indexed by [WHEEL] position, NaN where absent) break the cyclic order of the
     * color wheel. Each present color is compared with the next present one: the hue step between
     * them, taken as the representative (modulo 360) closest to the usual step, should be at least
     * [MIN_GAP]. Zero for fewer than two colors.
     */
    internal fun orderCost(hues: DoubleArray): Double {
        var first = -1
        var previous = -1
        var cost = 0.0
        for (i in hues.indices) {
            if (hues[i].isNaN()) continue
            if (previous >= 0) cost += gapCost(previous, i, hues) else first = i
            previous = i
        }
        if (first < 0 || first == previous) return 0.0
        return cost + gapCost(previous, first, hues)
    }

    private fun gapCost(from: Int, to: Int, hues: DoubleArray): Double {
        val expected = mod360(ANCHOR[to] - ANCHOR[from])
        val actual = expected + wrap180(hues[to] - hues[from] - expected)
        val deficit = MIN_GAP - actual
        if (deficit <= 0.0) return 0.0
        val x = deficit / ORDER_SOFTNESS
        return x * x
    }

    /** Per-channel gains that make [linear] neutral (equal R, G and B), clamped to a plausible cast. */
    internal fun neutralizingGains(linear: DoubleArray): DoubleArray {
        val g = linear[1]
        if (!(g > 0.0)) return doubleArrayOf(1.0, 1.0, 1.0)
        return doubleArrayOf(
            (g / linear[0]).let { if (it.isFinite()) it.coerceIn(MIN_GAIN, MAX_GAIN) else MAX_GAIN },
            1.0,
            (g / linear[2]).let { if (it.isFinite()) it.coerceIn(MIN_GAIN, MAX_GAIN) else MAX_GAIN },
        )
    }

    private const val UNUSABLE = 1e6

    /** Extra cost of a color that other evidence rules out (still chosen if it is the only one allowed). */
    private const val RULED_OUT = 1e3

    private fun mod360(x: Double): Double = ((x % 360.0) + 360.0) % 360.0

    private fun wrap180(x: Double): Double {
        val m = mod360(x)
        return if (m > 180.0) m - 360.0 else m
    }

    private fun permutations(n: Int): List<IntArray> {
        val result = mutableListOf<IntArray>()
        fun extend(prefix: IntArray, size: Int, used: Int) {
            if (size == n) {
                result += prefix.copyOf()
                return
            }
            for (i in 0 until n) {
                if (used and (1 shl i) != 0) continue
                prefix[size] = i
                extend(prefix, size + 1, used or (1 shl i))
            }
        }
        extend(IntArray(n), 0, 0)
        return result
    }
}
