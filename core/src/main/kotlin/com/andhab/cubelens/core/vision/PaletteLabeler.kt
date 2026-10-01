package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor

/**
 * Names the colors of a cube by how they relate to each other, not by how close they are to fixed
 * reference colors.
 *
 * Absolute references fail on unusual cubes: a pastel blue is closer to a standard white than to a
 * standard blue, and a pink "red" is far from any standard red. What every cube keeps is the order of
 * its colors around the color wheel: red, orange, yellow, green, blue and back to red (pink and
 * magenta reds included). So a labelling of several colors at once is scored by
 *
 *  - how white-like the color called white is ([LiveClassifier.whiteDistance], cast-aware);
 *  - how far each other color's hue is from its usual hue band ([LiveClassifier.costs]), a soft
 *    preference only;
 *  - how badly the hues break the cyclic order of the wheel (strong): consecutive colors must follow
 *    each other in the right direction, with the gap measured as the hue difference closest to the
 *    usual one;
 *  - red being lighter than orange (weak: red is the darker of the two on every cube, standard or not).
 *
 * Every candidate for white is tried (and, for fewer than six colors, none); each hypothesis
 * white-balances the other colors with the candidate before reading their hues, which removes a
 * common color cast. The search is exhaustive (at most 6 x 120 labellings) and takes microseconds.
 *
 * Two kinds of input:
 *  - [labelAll]: the six cluster colors of a whole scanned cube in one common light ([ScanResolver]).
 *  - [labelCenters]: one to six center stickers from separate photos, as captured on the scanning
 *    screen ([AdaptiveLiveClassifier]). Each photo has its own exposure, and a single sticker can
 *    look like white under some cast, so here the default rules of [LiveClassifier] rank each
 *    sticker's colors, and calling a sticker white costs extra when that implies a strongly warm
 *    light (a light warm pastel is the likelier explanation).
 */
internal object PaletteLabeler {

    private val COLORS = CubeColor.entries

    /** The chromatic colors in hue order around the color wheel. */
    private val WHEEL = listOf(CubeColor.RED, CubeColor.ORANGE, CubeColor.YELLOW, CubeColor.GREEN, CubeColor.BLUE)

    /** Typical hue angle (degrees) of each [WHEEL] color, used for the expected gaps between them. */
    private val ANCHOR = doubleArrayOf(20.0, 60.0, 95.0, 150.0, 270.0)

    /** Smallest acceptable hue step (degrees) from one color to the next around the wheel. */
    private const val MIN_GAP = 3.0

    /** Degrees below [MIN_GAP] per unit of (squared) order cost. */
    private const val ORDER_SOFTNESS = 5.0

    /** L* by which red is lighter than orange per unit of (squared) cost. */
    private const val RED_ORANGE_LIGHTNESS_SOFTNESS = 10.0

    /**
     * [photoWhiteCost]: warmth ([LiveClassifier.warmth]) up to which a white reading costs nothing
     * extra (mildly warm light), and the softness of the cost beyond.
     */
    private const val MILD_WARMTH = 0.45
    private const val WARMTH_SOFTNESS = 0.35

    /** White-balance gains of a hypothesis are clamped to this range. */
    private const val MIN_GAIN = 0.4
    private const val MAX_GAIN = 2.5

    /** White cost of a color too dark to judge. */
    private const val UNUSABLE = 1e6

    /** Extra cost of a color that other evidence rules out (still chosen if nothing else fits). */
    private const val RULED_OUT = 1e3

    /** An 8-bit channel value at or above this may be clipped (as in [LiveClassifier]). */
    private const val CLIPPED_LEVEL = 250

    /**
     * Labels six distinct colors given in linear RGB (e.g. the lighting-compensated sticker colors of
     * a scanned cube) with the six [CubeColor]s: element k of the result names [linear] element k, and
     * the result is a permutation of [CubeColor.entries].
     */
    fun labelAll(linear: List<DoubleArray>): List<CubeColor> {
        require(linear.size == 6 && linear.all { it.size == 3 }) { "Need six linear RGB colors" }
        val items = linear.map { Item(it, NOT_CLIPPED) }
        return Search(items, null, null, photos = false).run() ?: COLORS.toList()
    }

    /**
     * Names [centers] (one to six center stickers of different faces, each from its own photo) with
     * distinct colors, jointly: element k of the result names [centers] element k.
     *
     * [allowed] optionally restricts the colors each center may get (e.g. a single color for centers
     * already named); [whiteRuledOut]`[k]` says other evidence rules out white for center k (it is
     * then white only if nothing else fits). Returns null if [allowed] admits no distinct labelling.
     */
    fun labelCenters(
        centers: List<StickerSample>,
        allowed: List<Set<CubeColor>>? = null,
        whiteRuledOut: BooleanArray? = null,
    ): List<CubeColor>? {
        require(centers.size in 1..6) { "Need one to six centers, got ${centers.size}" }
        require(allowed == null || allowed.size == centers.size) { "allowed must have one entry per center" }
        require(whiteRuledOut == null || whiteRuledOut.size == centers.size) { "whiteRuledOut must have one entry per center" }
        val items = centers.map { s ->
            Item(
                doubleArrayOf(ColorMath.srgbToLinear(s.r), ColorMath.srgbToLinear(s.g), ColorMath.srgbToLinear(s.b)),
                booleanArrayOf(s.r >= CLIPPED_LEVEL, s.g >= CLIPPED_LEVEL, s.b >= CLIPPED_LEVEL),
            )
        }
        return Search(items, allowed, whiteRuledOut, photos = true).run()
    }

    /** One color to name: linear RGB, and which channels may be clipped (their true value higher). */
    private class Item(val linear: DoubleArray, val clipped: BooleanArray)

    private val NOT_CLIPPED = BooleanArray(3)

    /**
     * Exhaustive search over the labellings of [items]. [photos]: items come from separate photos
     * (see [labelCenters]); otherwise they are colors in one common light (see [labelAll]).
     */
    private class Search(
        private val items: List<Item>,
        allowed: List<Set<CubeColor>>?,
        whiteRuledOut: BooleanArray?,
        private val photos: Boolean,
    ) {
        private val n = items.size

        /** allow[k][c]: item k may get the color with ordinal c. */
        private val allow = Array(n) { k -> BooleanArray(COLORS.size) { c -> allowed?.get(k)?.contains(COLORS[c]) ?: true } }

        /** Cost of calling item k white. */
        private val whiteCost = DoubleArray(n) { k ->
            val item = items[k]
            val cost = if (photos) photoWhiteCost(item.linear, item.clipped) else squared(whiteDistance(item.linear, item.clipped))
            if (whiteRuledOut?.get(k) == true) cost + RULED_OUT else cost
        }

        // State of the current white hypothesis: the other items and their balanced colors.
        private val others = IntArray(n)
        private var m = 0
        private val costs = arrayOfNulls<DoubleArray>(n)
        private val hues = DoubleArray(n)
        private val lightness = DoubleArray(n)

        // State of the labelling being built: wheel position of each other item.
        private val wheelOf = IntArray(n)
        private val used = BooleanArray(WHEEL.size)
        private val byWheel = DoubleArray(WHEEL.size)
        private var white = -1

        private var bestCost = Double.POSITIVE_INFINITY
        private var best: IntArray? = null // color ordinal per item

        fun run(): List<CubeColor>? {
            // Fewer than six colors need not include white (-1: no white among them).
            val candidates = (if (n < COLORS.size) listOf(-1) else emptyList()) +
                (0 until n).filter { allow[it][CubeColor.WHITE.ordinal] }.sortedBy { whiteCost[it] }
            for (candidate in candidates) {
                val cost = if (candidate < 0) 0.0 else whiteCost[candidate]
                if (cost >= bestCost) continue
                if (!prepare(candidate)) continue
                assign(0, cost)
            }
            return best?.map { COLORS[it] }
        }

        /** Sets up the hypothesis that item [candidate] is white (none if negative). */
        private fun prepare(candidate: Int): Boolean {
            white = candidate
            m = 0
            for (k in 0 until n) if (k != candidate) others[m++] = k
            if (m > WHEEL.size) return false
            val gains = if (candidate < 0) ONE else neutralizingGains(items[candidate].linear)
            for (j in 0 until m) {
                val item = items[others[j]]
                val r = item.linear[0] * gains[0]
                val g = item.linear[1] * gains[1]
                val b = item.linear[2] * gains[2]
                val lab = ColorMath.linearToLab(r, g, b)
                val distance = LiveClassifier.whiteDistance(r, g, b, item.clipped[0], item.clipped[1], item.clipped[2])
                costs[j] = LiveClassifier.costs(distance, lab, followRules = photos)
                hues[j] = lab.hue.toDouble()
                lightness[j] = lab.l.toDouble()
            }
            return true
        }

        /** Gives the other items from [j] on wheel colors, depth first, pruning at [bestCost]. */
        private fun assign(j: Int, cost: Double) {
            if (j == m) {
                finish(cost)
                return
            }
            val item = others[j]
            val itemCosts = costs[j]!!
            for (p in WHEEL.indices) {
                if (used[p]) continue
                val color = WHEEL[p].ordinal
                if (!allow[item][color]) continue
                val next = cost + itemCosts[color]
                if (next >= bestCost) continue
                used[p] = true
                wheelOf[j] = p
                assign(j + 1, next)
                used[p] = false
            }
        }

        /** Adds the order and lightness costs of a complete labelling and keeps it if it is the best. */
        private fun finish(partial: Double) {
            byWheel.fill(Double.NaN)
            var red = -1
            var orange = -1
            for (j in 0 until m) {
                byWheel[wheelOf[j]] = hues[j]
                if (wheelOf[j] == 0) red = j
                if (wheelOf[j] == 1) orange = j
            }
            var cost = partial + orderCost(byWheel)
            if (red >= 0 && orange >= 0 && lightness[red] > lightness[orange]) {
                val x = (lightness[red] - lightness[orange]) / RED_ORANGE_LIGHTNESS_SOFTNESS
                cost += x * x
            }
            if (cost < bestCost) {
                bestCost = cost
                best = IntArray(n).also { labels ->
                    if (white >= 0) labels[white] = CubeColor.WHITE.ordinal
                    for (j in 0 until m) labels[others[j]] = WHEEL[wheelOf[j]].ordinal
                }
            }
        }
    }

    private val ONE = doubleArrayOf(1.0, 1.0, 1.0)

    /**
     * How unlikely it is that [sample], a sticker in a photo under unknown light, is a white sticker:
     * its squared [LiveClassifier.whiteDistance], plus a cost for the cast that this implies when it
     * is more than mildly warm (a light warm pastel such as peach or beige is then the likelier
     * explanation). The cost [labelCenters] gives a white center.
     */
    fun photoWhiteCost(sample: StickerSample): Double = photoWhiteCost(
        doubleArrayOf(ColorMath.srgbToLinear(sample.r), ColorMath.srgbToLinear(sample.g), ColorMath.srgbToLinear(sample.b)),
        booleanArrayOf(sample.r >= CLIPPED_LEVEL, sample.g >= CLIPPED_LEVEL, sample.b >= CLIPPED_LEVEL),
    )

    private fun photoWhiteCost(linear: DoubleArray, clipped: BooleanArray): Double {
        val excess = (LiveClassifier.warmth(linear[0], linear[1], linear[2]) - MILD_WARMTH).coerceAtLeast(0.0) / WARMTH_SOFTNESS
        return squared(whiteDistance(linear, clipped)) + excess * excess
    }

    private fun whiteDistance(linear: DoubleArray, clipped: BooleanArray): Double =
        LiveClassifier.whiteDistance(linear[0], linear[1], linear[2], clipped[0], clipped[1], clipped[2])

    /** [d] squared, or [UNUSABLE] for a color too dark to judge. */
    private fun squared(d: Double): Double = if (d.isFinite()) d * d else UNUSABLE

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

    private fun mod360(x: Double): Double = ((x % 360.0) + 360.0) % 360.0

    private fun wrap180(x: Double): Double {
        val m = mod360(x)
        return if (m > 180.0) m - 360.0 else m
    }
}
