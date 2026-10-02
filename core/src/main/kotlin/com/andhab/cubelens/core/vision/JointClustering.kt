package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/*
 * The joint classification machinery shared by the 3x3 resolver, the N×N resolver and the adaptive
 * live classifier: stickers of several photos ("scans") are clustered into the six colors under a
 * per-photo lighting model. See [ScanResolver] for the method.
 */

/**
 * Linear RGB of sticker i of scan k with color (cluster) c is modeled as `gain[k] * mean[c]` per
 * channel: the exposure and white balance of each photo (von Kries), times the color's own value.
 *
 * Stickers are numbered scan by scan, [perScan] per scan: sticker i belongs to scan `i / perScan`.
 */
internal class LightingModel(val linear: Array<DoubleArray>, val scans: Int, val perScan: Int) {
    val gain = Array(scans) { DoubleArray(3) { 1.0 } }
    val mean = Array(JointClustering.COLORS) { DoubleArray(3) }

    /** Each cluster's mean starts as the sticker at [position] of its scan (cluster k: scan k), with neutral gains. */
    fun seedFromPosition(position: Int) {
        for (k in 0 until JointClustering.COLORS) linear[k * perScan + position].copyInto(mean[k])
    }

    /** Cluster c's mean starts as [means]`[c]` (linear RGB), with neutral gains. */
    fun seed(means: Array<DoubleArray>) {
        for (c in 0 until JointClustering.COLORS) means[c].copyInto(mean[c])
        for (g in gain) g.fill(1.0)
    }

    /** Alternating least squares for gains and means given the cluster of every sticker. */
    fun fit(assigned: IntArray) {
        repeat(JointClustering.MODEL_ROUNDS) {
            for (c in 0 until JointClustering.COLORS) {
                for (ch in 0 until 3) {
                    var num = 0.0
                    var den = 0.0
                    for (i in linear.indices) {
                        if (assigned[i] != c) continue
                        val g = gain[i / perScan][ch]
                        num += g * linear[i][ch]
                        den += g * g
                    }
                    if (den > 0.0) mean[c][ch] = num / den
                }
            }
            for (k in 0 until scans) {
                for (ch in 0 until 3) {
                    var num = JointClustering.GAIN_PRIOR
                    var den = JointClustering.GAIN_PRIOR
                    for (p in 0 until perScan) {
                        val i = k * perScan + p
                        val m = mean[assigned[i]][ch]
                        num += m * linear[i][ch]
                        den += m * m
                    }
                    gain[k][ch] = (num / den).coerceIn(JointClustering.MIN_GAIN, JointClustering.MAX_GAIN)
                }
            }
            // Fix the gauge: per channel, the gains' geometric mean is 1.
            for (ch in 0 until 3) {
                var logSum = 0.0
                for (k in 0 until scans) logSum += ln(gain[k][ch])
                val norm = exp(logSum / scans)
                for (k in 0 until scans) gain[k][ch] /= norm
            }
        }
    }

    /** cost[i * 6 + c]: distance of sticker i (with its scan's gains undone) to cluster c's mean. */
    fun costs(): DoubleArray {
        val colors = JointClustering.COLORS
        val means = Array(colors) { c -> ColorMath.linearToLab(mean[c][0], mean[c][1], mean[c][2]) }
        val cost = DoubleArray(linear.size * colors)
        for (i in linear.indices) {
            val g = gain[i / perScan]
            val lab = ColorMath.linearToLab(linear[i][0] / g[0], linear[i][1] / g[1], linear[i][2] / g[2])
            for (c in 0 until colors) cost[i * colors + c] = ColorMath.deltaE(lab, means[c]).toDouble()
        }
        return cost
    }
}

/**
 * A joint classification in scan order: sticker index `k * perScan + p` is sticker p of scan k.
 */
internal class Clusters(
    /** Linear RGB of every sticker. */
    val linear: Array<DoubleArray>,
    /** Cluster of every sticker. */
    val cluster: IntArray,
    /** cost[i * 6 + k]: distance of sticker i to cluster k after lighting compensation. */
    val cost: DoubleArray,
    /** Per-scan channel gains of the lighting model. */
    val gain: Array<DoubleArray>,
    /** Stickers per scan. */
    val perScan: Int,
) {
    private val colors = JointClustering.COLORS

    /** Sum of every sticker's distance to its cluster: how well the clusters explain the stickers. */
    fun totalCost(): Double = linear.indices.sumOf { cost[it * colors + cluster[it]] }

    /**
     * Each cluster's color in the common light of the lighting model: the per-channel median of
     * its stickers with their scan's gains undone (robust to a highlight or a misread sticker).
     */
    fun robustColors(): List<DoubleArray> = List(colors) { k ->
        val members = linear.indices.filter { cluster[it] == k }
        DoubleArray(3) { ch ->
            val values = members.map { linear[it][ch] / gain[it / perScan][ch] }.sorted()
            if (values.isEmpty()) 0.0 else values[values.size / 2]
        }
    }

    /**
     * Sticker-order colors and costs with cluster k named [labels]`[k]` and scan k placed on
     * [faces]`[k]` (sticker p of a scan placed on face f becomes sticker `f * perScan + p`); for the
     * six scans of a whole cube. Stickers at [pinnedPosition] of every face (the fixed centers that
     * seeded the clusters; -1 for none) are certain.
     */
    fun classification(labels: List<CubeColor>, faces: List<Face>, pinnedPosition: Int): Classification {
        check(linear.size == JointClustering.SCANS * perScan) { "A classification needs the six scans of a whole cube" }
        val clusterOfColor = IntArray(colors)
        for (k in 0 until colors) clusterOfColor[labels[k].ordinal] = k
        val count = linear.size
        val placed = arrayOfNulls<CubeColor>(count)
        val placedCost = DoubleArray(count * colors)
        for (k in 0 until JointClustering.SCANS) {
            val f = faces[k].ordinal
            for (p in 0 until perScan) {
                val i = k * perScan + p
                val j = f * perScan + p
                placed[j] = labels[cluster[i]]
                for (c in 0 until colors) placedCost[j * colors + c] = cost[i * colors + clusterOfColor[c]]
            }
        }
        return Classification(placed.map { it!! }, placedCost, perScan, pinnedPosition)
    }
}

/** Outcome of the joint classification for one placement, before orientation fixing and repair. */
internal class Classification(
    /** Color of every sticker, scans placed on their faces as captured. */
    val colors: List<CubeColor>,
    /** cost[i * 6 + c]: distance of sticker i to color c (by ordinal) after lighting compensation. */
    val cost: DoubleArray,
    /** Stickers per face. */
    private val perFace: Int,
    /** Position (within each face) of the stickers that are certain, e.g. the fixed centers; -1 for none. */
    private val pinnedPosition: Int,
) {
    private val colorCount = JointClustering.COLORS

    /** Whether sticker [i] is certain by construction (a pinned center). */
    fun isPinned(i: Int): Boolean = pinnedPosition >= 0 && i % perFace == pinnedPosition

    /** Second-best cost minus own cost per sticker (infinite for pinned ones); negative if forced by the quotas. */
    val margin: DoubleArray = DoubleArray(colors.size) { i ->
        if (isPinned(i)) {
            Double.POSITIVE_INFINITY
        } else {
            val own = colors[i].ordinal
            var second = Double.POSITIVE_INFINITY
            for (c in 0 until colorCount) if (c != own) second = minOf(second, cost[i * colorCount + c])
            second - cost[i * colorCount + own]
        }
    }

    /** Stickers whose second-best color is within [ScanResolver.UNCERTAIN_MARGIN] of their own. */
    fun uncertain(): Set<Int> = colors.indices.filter { margin[it] < ScanResolver.UNCERTAIN_MARGIN }.toSet()
}

/** Constants and steps of the joint clustering shared by the resolvers and the adaptive classifier. */
internal object JointClustering {

    /** Sticker colors of a cube, i.e. clusters. */
    const val COLORS = 6

    /** Scans of a whole cube. */
    const val SCANS = 6

    /** Alternating least-squares rounds per lighting fit. */
    const val MODEL_ROUNDS = 4

    /** Weight pulling scan gains towards 1, in squared linear units. */
    const val GAIN_PRIOR = 0.02

    /** Range of the per-scan channel gains. */
    const val MIN_GAIN = 0.2
    const val MAX_GAIN = 5.0

    /** Rounds of assignment and lighting fit for [converge]. */
    private const val MAX_ROUNDS = 25

    /** k-means++ starts of [clusterFromScratch], besides the one from the live classifier's rules. */
    private const val RANDOM_STARTS = 4

    /** Fixed seed of the k-means++ starts: results are reproducible. */
    private const val RANDOM_SEED = 0x5CA9L

    /**
     * Smallest CIEDE2000 distance between two cluster colors of a real cube. The six colors of real
     * cubes, pastel ones included, are about 15 deltaE or more apart; this only rejects clusters that
     * are clearly the same color (e.g. one face scanned twice), leaving the rest to validation.
     */
    const val MIN_COLOR_SEPARATION = 4f

    /**
     * A cluster counts as colored from this CIELAB chroma on (white-balanced on the white cluster and
     * exposure-normalized, see [PaletteEstimator.normalize]); the light blue of a very pale pastel
     * cube still has about 8.
     */
    private const val MIN_CHROMA = 5f

    /** At least this many of the five non-white clusters must be colored ([MIN_CHROMA]). */
    private const val MIN_COLORED_CLUSTERS = 4

    /** Linear RGB of every sample, scan by scan. */
    fun linearOf(scans: List<List<StickerSample>>): Array<DoubleArray> {
        val samples = scans.flatten()
        return Array(samples.size) { i ->
            val s = samples[i]
            doubleArrayOf(ColorMath.srgbToLinear(s.r), ColorMath.srgbToLinear(s.g), ColorMath.srgbToLinear(s.b))
        }
    }

    /** Whether the six cluster colors (linear RGB) are at least [separation] apart ([ColorMath.deltaE]). */
    fun areDistinct(colors: List<DoubleArray>, separation: Float = MIN_COLOR_SEPARATION): Boolean {
        val labs = colors.map { ColorMath.linearToLab(it[0], it[1], it[2]) }
        for (i in labs.indices) {
            for (j in i + 1 until labs.size) {
                if (!(ColorMath.deltaE(labs[i], labs[j]) >= separation)) return false
            }
        }
        return true
    }

    /**
     * Whether the clusters (linear RGB per color) look like the stickers of a cube: besides white,
     * nearly all of them are clearly colored. Six grey levels, for example, are not.
     */
    fun isColored(linear: Map<CubeColor, DoubleArray>): Boolean {
        val normalized = PaletteEstimator.normalize(linear).first
        return normalized.count { (color, lab) -> color != CubeColor.WHITE && lab.chroma >= MIN_CHROMA } >= MIN_COLORED_CLUSTERS
    }

    /**
     * Alternates the bounded assignment of the [movable] stickers ([GroupAssignment]; cluster c gets
     * `lower[c]..upper[c]` of them) and lighting fits, starting from [model]'s current means and
     * gains, until nothing changes. [assigned] holds the clusters of stickers that are not movable
     * (e.g. pinned centers) and receives the result.
     */
    fun converge(model: LightingModel, assigned: IntArray, movable: IntArray, lower: IntArray, upper: IntArray): Clusters {
        var current = model.costs()
        for (round in 0 until MAX_ROUNDS) {
            if (!GroupAssignment.solve(current, COLORS, movable, lower, upper, assigned)) break
            model.fit(assigned)
            current = model.costs()
        }
        return Clusters(model.linear, assigned, current, model.gain, model.perScan)
    }

    /**
     * Repairs an unlucky start of a clustering seeded from fixed centers (cluster k: the center at
     * [center] of scan k): when two centers look alike in their photos (a white center under a cool
     * cast and a light blue one under a warm cast), the other stickers of the two colors can end up
     * with the wrong center each. Then each center fits the other's cluster better than its own;
     * exchanging the two clusters' [movable] members and refitting ([converge], from a model fitted to
     * the exchanged clusters) must explain the stickers better. Up to [MAX_CLUSTER_SWAPS] such pairs
     * are tried; returns the best clustering.
     */
    fun undoSwappedCenters(start: Clusters, center: Int, movable: IntArray, converge: (LightingModel, IntArray) -> Clusters): Clusters {
        var best = start
        val tried = mutableSetOf<Int>()
        repeat(MAX_CLUSTER_SWAPS) {
            val pair = swappedPair(best, center, tried) ?: return best
            tried += pair
            val a = pair / COLORS
            val b = pair % COLORS
            val swapped = best.cluster.copyOf()
            for (i in movable) {
                if (swapped[i] == a) swapped[i] = b else if (swapped[i] == b) swapped[i] = a
            }
            val trial = LightingModel(best.linear, SCANS, best.perScan)
            trial.fit(swapped)
            val candidate = converge(trial, swapped)
            if (candidate.totalCost() < best.totalCost()) best = candidate
        }
        return best
    }

    /** Clusters tried by [undoSwappedCenters]. */
    private const val MAX_CLUSTER_SWAPS = 3

    /**
     * Two clusters (encoded `a * 6 + b`, `a < b`, not in [tried]) whose centers (at [center] of their
     * scans) each fit the other cluster better than their own, or null.
     */
    private fun swappedPair(clusters: Clusters, center: Int, tried: Set<Int>): Int? {
        val cost = clusters.cost
        for (a in 0 until COLORS) {
            val centerA = a * clusters.perScan + center
            for (b in a + 1 until COLORS) {
                if (a * COLORS + b in tried) continue
                val centerB = b * clusters.perScan + center
                if (cost[centerA * COLORS + b] < cost[centerA * COLORS + a] && cost[centerB * COLORS + a] < cost[centerB * COLORS + b]) return a * COLORS + b
            }
        }
        return null
    }

    /**
     * Tries exchanging the colors of two low-margin stickers of [classification] (which keeps the
     * count of every color), cheapest exchange first, until [orient] accepts the colors. Returns the
     * new colors, what [orient] made of them and the two exchanged stickers, or null.
     */
    fun <T : Any> repairBySwap(classification: Classification, orient: (List<CubeColor>) -> T?): Triple<List<CubeColor>, T, Set<Int>>? {
        val raw = classification.colors
        val cost = classification.cost
        val candidates = raw.indices.filter { !classification.isPinned(it) }.sortedBy { classification.margin[it] }.take(SWAP_CANDIDATES)
        val swaps = mutableListOf<Triple<Int, Int, Double>>()
        for (x in candidates.indices) {
            for (y in x + 1 until candidates.size) {
                val i = candidates[x]
                val j = candidates[y]
                val ci = raw[i].ordinal
                val cj = raw[j].ordinal
                if (ci == cj) continue
                val delta = cost[i * COLORS + cj] + cost[j * COLORS + ci] - cost[i * COLORS + ci] - cost[j * COLORS + cj]
                if (delta <= MAX_SWAP_COST) swaps += Triple(i, j, delta)
            }
        }
        swaps.sortBy { it.third }
        for ((i, j, _) in swaps) {
            val candidate = raw.toMutableList()
            candidate[i] = raw[j]
            candidate[j] = raw[i]
            val oriented = orient(candidate) ?: continue
            return Triple(candidate, oriented, setOf(i, j))
        }
        return null
    }

    /** [repairBySwap]: the lowest-margin stickers considered, and the largest cost increase of an exchange. */
    private const val SWAP_CANDIDATES = 12
    private const val MAX_SWAP_COST = 40.0

    /**
     * Clusters [samples] (all stickers of [scans] scans of [perScan] each, scan by scan; [linear] is
     * their linear RGB) into the six colors when no sticker's color is known in advance (even-sized
     * cubes have no fixed centers), cluster c getting `lower[c]..upper[c]` stickers.
     *
     * The lighting model makes this a non-convex problem, so it starts from several seeds and keeps
     * every distinct outcome: the live classifier's reading of each sticker (right for most stickers
     * of most cubes), and k-means++ seeds (distance-weighted random stickers, reproducible), which
     * do not depend on any idea of what the colors look like. Returns the distinct clusterings,
     * the one that explains the stickers best ([Clusters.totalCost]) first.
     */
    fun clusterFromScratch(
        samples: List<StickerSample>,
        linear: Array<DoubleArray>,
        scans: Int,
        perScan: Int,
        lower: IntArray,
        upper: IntArray,
    ): List<Clusters> {
        val labs = Array(linear.size) { ColorMath.linearToLab(linear[it][0], linear[it][1], linear[it][2]) }
        val starts = ArrayList<Array<DoubleArray>>()
        starts += liveSeeds(samples, labs, linear)
        val random = Random(RANDOM_SEED)
        repeat(RANDOM_STARTS) { starts += kMeansPlusPlusSeeds(labs, linear, random) }
        val all = IntArray(linear.size) { it }
        val results = starts.map { seed ->
            val model = LightingModel(linear, scans, perScan)
            model.seed(seed)
            converge(model, IntArray(linear.size) { -1 }, all, lower, upper)
        }
        return results.distinctBy { canonical(it.cluster) }.sortedBy { it.totalCost() }
    }

    /**
     * Seeds from the default rules: the mean of the stickers [LiveClassifier] gives each color; a color
     * it gives no sticker starts at the sticker farthest from the seeds so far.
     */
    private fun liveSeeds(samples: List<StickerSample>, labs: Array<Lab>, linear: Array<DoubleArray>): Array<DoubleArray> {
        val sums = Array(COLORS) { DoubleArray(3) }
        val counts = IntArray(COLORS)
        for (i in samples.indices) {
            val s = samples[i]
            val color = LiveClassifier.classify(StickerSample.of(s.r.coerceIn(0, 255), s.g.coerceIn(0, 255), s.b.coerceIn(0, 255))).ordinal
            counts[color]++
            for (ch in 0 until 3) sums[color][ch] += linear[i][ch]
        }
        val seeds = arrayOfNulls<DoubleArray>(COLORS)
        val chosen = ArrayList<Lab>()
        for (c in 0 until COLORS) {
            if (counts[c] == 0) continue
            val mean = DoubleArray(3) { sums[c][it] / counts[c] }
            seeds[c] = mean
            chosen += ColorMath.linearToLab(mean[0], mean[1], mean[2])
        }
        for (c in 0 until COLORS) {
            if (seeds[c] != null) continue
            val far = farthest(labs, chosen)
            seeds[c] = linear[far].copyOf()
            chosen += labs[far]
        }
        return Array(COLORS) { seeds[it]!! }
    }

    /** k-means++ seeds: a random sticker, then stickers drawn with probability proportional to their squared distance from the seeds so far. */
    private fun kMeansPlusPlusSeeds(labs: Array<Lab>, linear: Array<DoubleArray>, random: Random): Array<DoubleArray> {
        val seeds = ArrayList<DoubleArray>()
        val nearest = DoubleArray(labs.size) { Double.POSITIVE_INFINITY } // squared distance to the nearest seed
        var next = random.nextInt(labs.size)
        while (seeds.size < COLORS) {
            seeds += linear[next].copyOf()
            var total = 0.0
            for (i in labs.indices) {
                val d = ColorMath.deltaE(labs[i], labs[next]).toDouble()
                if (d * d < nearest[i]) nearest[i] = d * d
                total += nearest[i]
            }
            if (!(total > 0.0)) {
                // Fewer distinct colors than clusters: any sticker will do.
                next = random.nextInt(labs.size)
                continue
            }
            var r = random.nextDouble() * total
            next = labs.size - 1
            for (i in labs.indices) {
                r -= nearest[i]
                if (r <= 0.0) {
                    next = i
                    break
                }
            }
        }
        return seeds.toTypedArray()
    }

    /** The sticker farthest ([ColorMath.deltaE]) from all of [chosen] (any sticker if none is chosen). */
    private fun farthest(labs: Array<Lab>, chosen: List<Lab>): Int {
        var best = 0
        var bestDistance = Double.NEGATIVE_INFINITY
        for (i in labs.indices) {
            var d = Double.POSITIVE_INFINITY
            for (c in chosen) d = minOf(d, ColorMath.deltaE(labs[i], c).toDouble())
            if (d > bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best
    }

    /**
     * Learns what this cube's six colors look like from some of its faces ([faces]: one to six
     * captured faces of the same size, all their stickers), without knowing any sticker's color in
     * advance (even-sized cubes have no fixed centers): the stickers are clustered jointly with
     * [clusterFromScratch] under the per-photo lighting model, and the clusters are named by how they
     * relate to each other ([PaletteLabeler.labelAll]). Returns a representative sample per color (the
     * per-channel median of its stickers as captured), or null while the evidence is too thin:
     * fewer than [MIN_LEARNING_STICKERS] stickers, a color seen fewer than [MIN_LEARNING_MEMBERS]
     * times, or clusters that are not six clearly different colors (e.g. a color not captured yet).
     *
     * A cube's faces hold each color equally often overall, but a few faces need not: with all six
     * faces each color gets exactly its share, with fewer each cluster may get up to
     * [PARTIAL_SPREAD] times its share (so two colors are never merged into one cluster).
     */
    fun learnColors(faces: List<List<StickerSample>>): Map<CubeColor, StickerSample>? {
        if (faces.isEmpty()) return null
        val perScan = faces[0].size
        if (perScan == 0 || faces.any { it.size != perScan }) return null
        val samples = faces.flatten()
        val count = samples.size
        if (count < MIN_LEARNING_STICKERS) return null
        val (lower, upper) = if (faces.size == SCANS && count % COLORS == 0) {
            IntArray(COLORS) { count / COLORS } to IntArray(COLORS) { count / COLORS }
        } else {
            IntArray(COLORS) { 1 } to IntArray(COLORS) { kotlin.math.ceil(PARTIAL_SPREAD * count / COLORS).toInt() + 1 }
        }
        val linear = linearOf(faces)
        val clusters = clusterFromScratch(samples, linear, faces.size, perScan, lower, upper).first()
        val members = List(COLORS) { k -> samples.indices.filter { clusters.cluster[it] == k } }
        if (members.any { it.size < MIN_LEARNING_MEMBERS }) return null
        val measured = clusters.robustColors()
        val separation = if (faces.size == SCANS) MIN_COLOR_SEPARATION else MIN_PARTIAL_SEPARATION
        if (!areDistinct(measured, separation)) return null
        val labels = PaletteLabeler.labelAll(measured)
        if (!isColored(labels.indices.associate { labels[it] to measured[it] })) return null
        return labels.indices.associate { k ->
            val own = members[k].map { samples[it] }
            labels[k] to StickerSample.of(medianOf(own.map { it.r }), medianOf(own.map { it.g }), medianOf(own.map { it.b }))
        }
    }

    /** [learnColors] needs at least this many stickers (two 3x3 faces' worth, three 2x2 faces). */
    const val MIN_LEARNING_STICKERS = 12

    /** [learnColors]: every color must be seen at least this often. */
    private const val MIN_LEARNING_MEMBERS = 2

    /** [learnColors] with fewer than six faces: a cluster may hold up to this many times its share of the stickers. */
    private const val PARTIAL_SPREAD = 1.75

    /**
     * [learnColors] with fewer than six faces: the clusters must be this far apart (CIEDE2000), more
     * than [MIN_COLOR_SEPARATION], because a color not captured yet makes another color split in two.
     */
    private const val MIN_PARTIAL_SEPARATION = 8f

    /** Upper median of 0..255 channel values (sRGB values outside are clamped). */
    private fun medianOf(values: List<Int>): Int = values.map { it.coerceIn(0, 255) }.sorted()[values.size / 2]

    /** [cluster] with clusters renumbered in order of first appearance: equal for the same partition. */
    private fun canonical(cluster: IntArray): List<Int> {
        val rename = IntArray(COLORS) { -1 }
        var next = 0
        return cluster.map { c ->
            if (c !in 0 until COLORS) return@map -1
            if (rename[c] < 0) rename[c] = next++
            rename[c]
        }
    }
}
