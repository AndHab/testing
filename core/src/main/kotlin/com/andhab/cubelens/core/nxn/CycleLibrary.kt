package com.andhab.cubelens.core.nxn

import java.util.concurrent.ConcurrentHashMap

/**
 * A library of short move sequences that 3-cycle three pieces of one wing or center orbit and leave
 * every other sticker of the cube exactly where it was ("pure" 3-cycles), for one cube size.
 *
 * The library is computed, not hand-written, so it works for every size:
 *
 * 1. **Commutators.** If two move sequences X and Y move exactly one common piece, their
 *    commutator `X Y X' Y'` 3-cycles three pieces and changes nothing else. Candidates are
 *    `X = one layer turn` and `Y = f g f'` (a layer turn g "transported" by a turn f); their overlap
 *    is measured with sticker bit sets, and every candidate is verified by tracing its eight moves.
 *    Both `[X, Y]` and its inverse `[Y, X]` are kept.
 * 2. **Setups.** Conjugating a pure 3-cycle C by a setup S (`S C S'`) gives another pure 3-cycle,
 *    on the pieces S brings into C's slots. Breadth-first rounds of one-move setups extend the
 *    library until each of the 24·23·22/3 = 4048 cycles of every orbit has a sequence, keeping the
 *    shortest one found for each (measured after merging and cancelling, see [MoveSequence]); two
 *    rounds reach every cycle.
 *
 * Even sizes only use turns that keep the DBL corner in place (no outer D, L or B turns, see
 * [moveSet]), so their solutions never move that corner, not even temporarily.
 *
 * Libraries are built once per size ([of]) and are immutable afterwards, hence thread-safe. A
 * build checks for interruption as it goes (a cold 10×10 build takes one to a few seconds) and
 * then throws [InterruptedException]; nothing is cached, so the next request builds afresh.
 */
internal class CycleLibrary private constructor(val model: NxNModel) {

    /**
     * A pure 3-cycle: the piece in slot [a] moves to [b], the one in [b] to [c] and the one in [c]
     * to [a]. Its moves are stored compactly as merged same-axis groups (see [MoveSequence]): the
     * axis of each group and the quarter turns of each of its layers, which write as [cost] block
     * moves. [firstCost] and [lastCost] are the block moves of the first and last group.
     */
    class Cycle internal constructor(
        val a: Int,
        val b: Int,
        val c: Int,
        private val axes: ByteArray,
        private val layerTurns: ByteArray,
        val cost: Int,
    ) {
        private val n: Int get() = layerTurns.size / axes.size

        val groupCount: Int get() = axes.size
        val firstAxis: Int get() = axes[0].toInt()
        val lastAxis: Int get() = axes[axes.size - 1].toInt()
        val firstCost: Int = MoveSequence.blockCount(groupTurns(0))
        val lastCost: Int = MoveSequence.blockCount(groupTurns(axes.size - 1))

        fun groupAxis(group: Int): Int = axes[group].toInt()

        /** Quarter turns of layer [layer] in group [group]. */
        fun turnsOf(group: Int, layer: Int): Int = layerTurns[group * n + layer].toInt()

        /** A copy of the layer turns of group [group]. */
        fun groupTurns(group: Int): IntArray = IntArray(n) { turnsOf(group, it) }

        /** Appends this cycle's moves to [sequence]. */
        fun appendTo(sequence: MoveSequence) {
            for (g in axes.indices) for (l in 0 until n) {
                val t = turnsOf(g, l)
                if (t != 0) sequence.add(axes[g].toInt(), l, l, t)
            }
        }

        internal companion object {
            /** A cycle whose moves are the (merged) contents of [sequence]. */
            fun of(a: Int, b: Int, c: Int, sequence: MoveSequence, cost: Int, n: Int): Cycle {
                val k = sequence.groupCount
                val turns = ByteArray(k * n)
                for (g in 0 until k) {
                    val t = sequence.groupTurns(g)
                    for (l in 0 until n) turns[g * n + l] = t[l].toByte()
                }
                return Cycle(a, b, c, ByteArray(k) { sequence.groupAxis(it).toByte() }, turns, cost)
            }
        }
    }

    /** The cycles of one orbit, one per 3-cycle of its slots. */
    class OrbitCycles(val orbit: Orbit, val cycles: List<Cycle>) {
        val averageCost: Double = cycles.sumOf { it.cost }.toDouble() / cycles.size
    }

    /**
     * Codes used as transporters `f` and as setups: every layer turn for odd sizes; for even sizes
     * those that keep the DBL corner in place, including the `0..n-2` block turns that stand in for
     * outer D, L and B turns (see [NxNModel]).
     */
    val moveSet: IntArray = (0 until model.codeCount)
        .filter { model.n % 2 == 1 || model.keepsDblCorner(it) }
        .toIntArray()

    /**
     * Single-layer codes of [moveSet], used as the turns `X` and `g` (only their small supports
     * make one-piece overlaps possible).
     */
    private val singleMoveSet: IntArray = moveSet.filter { model.isSingle(it) }.toIntArray()

    val wingCycles: List<OrbitCycles>
    val centerCycles: List<OrbitCycles>

    /** Diagnostics: how long the build took and how much the commutator search found. */
    val buildStats: String

    init {
        val start = System.nanoTime()
        val base = findCommutators()
        val searched = System.nanoTime()
        wingCycles = model.wingOrbits.mapIndexed { o, orbit ->
            OrbitCycles(orbit, extendBySetups(orbit, base.wings[o]))
        }
        centerCycles = model.centerOrbits.mapIndexed { o, orbit ->
            OrbitCycles(orbit, extendBySetups(orbit, base.centers[o]))
        }
        val done = System.nanoTime()
        buildStats = "commutators %d ms (wing %s, center %s), setups %d ms".format(
            (searched - start) / 1_000_000,
            base.wings.map { it.size },
            base.centers.map { it.size },
            (done - searched) / 1_000_000,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Step 1: commutators

    /** Shortest cycle found so far per orbit, keyed by [normalizedKey]. */
    private class BaseCycles(wingOrbits: Int, centerOrbits: Int) {
        val wings = List(wingOrbits) { HashMap<Int, Cycle>() }
        val centers = List(centerOrbits) { HashMap<Int, Cycle>() }

        fun of(kind: PieceKind, orbit: Int): HashMap<Int, Cycle> =
            if (kind == PieceKind.WING) wings[orbit] else centers[orbit]
    }

    /** A pure 3-cycle found by [classify]: the piece in `slots[0]` goes to `slots[1]`, and so on. */
    private class Pure3Cycle(val kind: PieceKind, val orbit: Int, val slots: IntArray)

    private fun findCommutators(): BaseCycles {
        val found = BaseCycles(model.wingOrbits.size, model.centerOrbits.size)
        val words = (model.stickerCount + 63) / 64
        val supports = Array(model.codeCount) { c ->
            val p = model.perm(c)
            p.indices.filter { p[it] != it }.toIntArray()
        }
        val transported = LongArray(words)
        for (f in moveSet) {
            throwIfInterrupted()
            val pf = model.perm(f)
            for (g in singleMoveSet) {
                if (model.axisOf(f) == model.axisOf(g)) continue
                // Stickers moved by f g f': those that f brings into g's layer.
                transported.fill(0)
                for (q in supports[g]) {
                    val p = pf[q]
                    transported[p ushr 6] = transported[p ushr 6] or (1L shl (p and 63))
                }
                for (x in singleMoveSet) {
                    val bits = model.supportBits[x]
                    var overlap = 0
                    for (w in 0 until words) overlap += java.lang.Long.bitCount(bits[w] and transported[w])
                    if (overlap == 0 || overlap > 2) continue
                    val fi = model.inverse(f)
                    val sequence = intArrayOf(x, f, g, fi, model.inverse(x), f, model.inverse(g), fi)
                    val cycle = classify(sequence, bits, transported) ?: continue
                    val map = found.of(cycle.kind, cycle.orbit)
                    val (a, b, c) = cycle.slots
                    record(map, normalizedKey(a, b, c), sequence)
                    // [Y, X] = [X, Y]' runs the same three pieces the other way round.
                    record(map, normalizedKey(a, c, b), intArrayOf(f, g, fi, x, f, model.inverse(g), fi, model.inverse(x)))
                }
            }
        }
        return found
    }

    private fun record(map: HashMap<Int, Cycle>, key: Int, codes: IntArray) {
        val sequence = MoveSequence(model.n)
        for (code in codes) sequence.addCode(model, code)
        val cost = sequence.moveCount()
        val existing = map[key]
        if (existing == null || cost < existing.cost) map[key] = cycleOf(key, sequence, cost)
    }

    /**
     * The pure 3-cycle of one wing or center orbit that the move [sequence] performs, or null if it
     * does anything else. Only stickers in [supportA] or [supportB] (bit sets) can move, so only
     * those are traced.
     */
    private fun classify(sequence: IntArray, supportA: LongArray, supportB: LongArray): Pure3Cycle? {
        val perms = Array(sequence.size) { model.perm(sequence[it]) }
        val moved = ArrayList<Int>(6)
        val from = HashMap<Int, Int>(12)
        for (w in supportA.indices) {
            var word = supportA[w] or supportB[w]
            while (word != 0L) {
                val i = (w shl 6) + java.lang.Long.numberOfTrailingZeros(word)
                word = word and (word - 1)
                // Composite permutation: the sticker now at i came from perms[0][perms[1][...[i]]].
                var j = i
                for (k in perms.indices.reversed()) j = perms[k][j]
                if (j != i) {
                    if (moved.size == 6) return null
                    moved += i
                    from[i] = j
                }
            }
        }
        val kind = when (moved.size) {
            3 -> PieceKind.CENTER
            6 -> PieceKind.WING
            else -> return null
        }
        if (moved.any { model.geometry.kindOf(it) != kind }) return null
        val orbitIndex = model.orbitIndexOf(moved[0])
        if (moved.any { model.orbitIndexOf(it) != orbitIndex }) return null
        val orbit = if (kind == PieceKind.CENTER) model.centerOrbits[orbitIndex] else model.wingOrbits[orbitIndex]
        // The piece that was in slot(from[i]) is now in slot(i).
        val next = HashMap<Int, Int>()
        for (i in moved) next[orbit.slotOf(from.getValue(i))] = orbit.slotOf(i)
        if (next.size != 3) return null
        val a = next.keys.min()
        val b = next.getValue(a)
        val c = next.getValue(b)
        if (next[c] != a) return null
        return Pure3Cycle(kind, orbitIndex, intArrayOf(a, b, c))
    }

    // ---------------------------------------------------------------------------------------------
    // Step 2: setups

    private fun extendBySetups(orbit: Orbit, base: Map<Int, Cycle>): List<Cycle> {
        val size = orbit.size
        val best = arrayOfNulls<Cycle>(size * size * size)
        for ((key, cycle) in base) best[key] = cycle
        var frontier = base.keys.toIntArray()
        val scratch = IntArray(model.n)
        var round = 0
        val target = size * (size - 1) * (size - 2) / 3
        while (frontier.isNotEmpty() && round < MAX_SETUP_ROUNDS) {
            round++
            val improved = LinkedHashSet<Int>()
            for (key in frontier) {
                throwIfInterrupted()
                val cycle = best[key] ?: continue
                for (s in moveSet) {
                    val back = orbit.slotDest[model.inverse(s)]
                    val newKey = normalizedKey(back[cycle.a], back[cycle.b], back[cycle.c])
                    val cost = conjugateCost(cycle, s, scratch)
                    val existing = best[newKey]
                    if (existing != null && existing.cost <= cost) continue
                    val sequence = MoveSequence(model.n)
                    sequence.addCode(model, s)
                    cycle.appendTo(sequence)
                    sequence.addCode(model, model.inverse(s))
                    val exact = sequence.moveCount()
                    if (existing != null && existing.cost <= exact) continue
                    best[newKey] = cycleOf(newKey, sequence, exact)
                    improved += newKey
                }
            }
            frontier = improved.toIntArray()
        }
        val cycles = best.filterNotNull()
        check(cycles.size == target) {
            "Only ${cycles.size} of $target 3-cycles found for a ${orbit.kind} orbit of the ${model.n}x${model.n} cube"
        }
        return cycles
    }

    /**
     * Block-move length of `s C s'` from C's stored groups: s and s' either start new groups (+1
     * each) or merge into C's first or last group. Falls back to a full merge when a group would
     * vanish (then neighbouring groups could merge as well).
     */
    private fun conjugateCost(cycle: Cycle, s: Int, scratch: IntArray): Int {
        val axis = model.axisOf(s)
        val from = model.fromLayer(s)
        val to = model.toLayer(s)
        val turns = model.turnsOf(s)
        val last = cycle.groupCount - 1
        if (last == 0) return 0 // Cannot happen for a 3-cycle; let the exact count decide.
        var cost = cycle.cost
        for (end in 0..1) {
            val g = if (end == 0) 0 else last
            if (cycle.groupAxis(g) != axis) {
                cost++
                continue
            }
            for (layer in scratch.indices) scratch[layer] = cycle.turnsOf(g, layer)
            val q = if (end == 0) turns else 4 - turns
            for (layer in from..to) scratch[layer] = (scratch[layer] + q) and 3
            if (scratch.all { it == 0 }) return 0 // Unknown: let the exact count decide.
            cost += MoveSequence.blockCount(scratch) - if (end == 0) cycle.firstCost else cycle.lastCost
        }
        return cost
    }

    private fun cycleOf(key: Int, sequence: MoveSequence, cost: Int): Cycle =
        Cycle.of(key / (SLOTS * SLOTS), key / SLOTS % SLOTS, key % SLOTS, sequence, cost, model.n)

    companion object {
        /** Setup rounds are a safety limit; two always suffice in practice. */
        private const val MAX_SETUP_ROUNDS = 6

        private val cache = ConcurrentHashMap<Int, BuildOnce<CycleLibrary>>()

        /**
         * The library for [n]×[n] cubes (n >= 4), built on first use. Thread-safe: concurrent
         * callers wait for a single build.
         *
         * @throws InterruptedException if the thread is interrupted while waiting for or running the build.
         */
        fun of(n: Int): CycleLibrary {
            require(n >= 4) { "Only cubes of size 4 and up have wing or center orbits" }
            val model = NxNModel.of(n)
            return cache.computeIfAbsent(n) { BuildOnce { CycleLibrary(model) } }.get()
        }

        /** Builds a new library for [n]×[n] cubes, bypassing the cache (to measure build time). */
        internal fun buildUncached(n: Int): CycleLibrary {
            require(n >= 4) { "Only cubes of size 4 and up have wing or center orbits" }
            return CycleLibrary(NxNModel.of(n))
        }

        /** Slots per orbit. */
        const val SLOTS = 24

        /** Key of the directed 3-cycle a→b→c, rotated so that its smallest slot comes first. */
        fun normalizedKey(a: Int, b: Int, c: Int): Int = when {
            a < b && a < c -> (a * SLOTS + b) * SLOTS + c
            b < c -> (b * SLOTS + c) * SLOTS + a
            else -> (c * SLOTS + a) * SLOTS + b
        }
    }
}

/**
 * Solves one orbit with library 3-cycles.
 *
 * A beam search over cycle choices: every partial solution is scored by its exact length so far
 * (including moves that merge or cancel where one cycle ends and the next begins) plus an estimate
 * for the pieces still wrong, and the best `beamWidth` partial solutions are extended with every
 * cycle that puts at least one more piece right (net). With a width of 1 this is a greedy search.
 *
 * Wings are distinct pieces (target: piece `s` in slot `s`; the permutation must be even, as
 * 3-cycles only make even permutations). Centers of one color are interchangeable (target: the
 * slot's face color), so they never have a parity problem.
 */
internal object OrbitSolver {

    /** A partial solution: the orbit after its cycles, their length and the last move group. */
    private class Node(
        val slots: IntArray,
        val wrong: Int,
        val cost: Int,
        val tailAxis: Int,
        val tailTurns: IntArray?,
        val parent: Node?,
        val cycle: CycleLibrary.Cycle?,
    ) {
        val tailCost: Int = tailTurns?.let { MoveSequence.blockCount(it) } ?: 0
    }

    /**
     * Appends to [out] cycles that turn [state] into [target] (`state[slot]` is the piece or color in
     * each slot) and returns how many were used. Moves are merged with what [out] ends with.
     *
     * @throws InterruptedException if the thread is interrupted (checked once per search round,
     *   well under a millisecond apart).
     */
    fun solve(
        cycles: CycleLibrary.OrbitCycles,
        state: IntArray,
        target: IntArray,
        out: MoveSequence,
        beamWidth: Int = DEFAULT_BEAM_WIDTH,
    ): Int {
        val wrong = state.indices.count { state[it] != target[it] }
        if (wrong == 0) return 0
        val distinct = target.toSet().size == target.size
        val perPiece = cycles.averageCost / if (distinct) WING_PIECES_PER_CYCLE else CENTER_PIECES_PER_CYCLE
        val last = out.groupCount - 1
        val root = Node(
            state.copyOf(), wrong, 0,
            if (last >= 0) out.groupAxis(last) else -1,
            if (last >= 0) out.groupTurns(last).copyOf() else null,
            null, null,
        )
        val all = cycles.cycles
        val scratch = IntArray(out.n)
        var beam = listOf(root)
        var best: Node? = null
        while (beam.isNotEmpty()) {
            throwIfInterrupted()
            // Candidate extensions, best estimate first: (estimate, cost, beam index, cycle index).
            val candidates = ArrayList<DoubleArray>()
            for ((b, node) in beam.withIndex()) {
                for ((ci, c) in all.withIndex()) {
                    val gain = gain(c, node.slots, target)
                    if (gain <= 0) continue
                    val cost = node.cost + c.cost + mergeDelta(node, c, scratch)
                    if (best != null && cost >= best.cost) continue
                    val estimate = cost + perPiece * (node.wrong - gain)
                    candidates += doubleArrayOf(estimate, cost.toDouble(), b.toDouble(), ci.toDouble())
                }
            }
            candidates.sortWith(compareBy<DoubleArray> { it[0] }.thenBy { it[1] })
            val next = ArrayList<Node>()
            val seen = HashSet<List<Int>>()
            for (candidate in candidates) {
                if (next.size >= beamWidth) break
                val node = beam[candidate[2].toInt()]
                val c = all[candidate[3].toInt()]
                val slots = node.slots.copyOf()
                apply(c, slots)
                if (!seen.add(slots.asList())) continue
                val left = slots.indices.count { slots[it] != target[it] }
                val child = Node(slots, left, candidate[1].toInt(), c.lastAxis, c.groupTurns(c.groupCount - 1), node, c)
                if (left > 0) {
                    next += child
                } else if (best == null || child.cost < best.cost) {
                    best = child
                }
            }
            beam = next
        }
        val chosen = ArrayList<CycleLibrary.Cycle>()
        var node: Node = checkNotNull(best) { "No sequence of 3-cycles solves the orbit (odd permutation?)" }
        while (true) {
            chosen += node.cycle ?: break
            node = node.parent ?: break
        }
        chosen.reverse()
        chosen.forEach { it.appendTo(out) }
        return chosen.size
    }

    /** Change in block moves when [c] directly follows the last move group of [node]. */
    private fun mergeDelta(node: Node, c: CycleLibrary.Cycle, scratch: IntArray): Int {
        val tail = node.tailTurns
        if (tail == null || c.firstAxis != node.tailAxis) return 0
        for (l in scratch.indices) scratch[l] = (c.turnsOf(0, l) + tail[l]) and 3
        return MoveSequence.blockCount(scratch) - c.firstCost - node.tailCost
    }

    const val DEFAULT_BEAM_WIDTH = 16

    /** Typical pieces put right per cycle, for estimating what is left. */
    private const val WING_PIECES_PER_CYCLE = 2.0
    private const val CENTER_PIECES_PER_CYCLE = 2.5

    /** Net number of slots made right by [c]. */
    fun gain(c: CycleLibrary.Cycle, s: IntArray, target: IntArray): Int {
        val pa = s[c.a]
        val pb = s[c.b]
        val pc = s[c.c]
        var g = 0
        if (pa == target[c.b]) g++
        if (pb == target[c.c]) g++
        if (pc == target[c.a]) g++
        if (pa == target[c.a]) g--
        if (pb == target[c.b]) g--
        if (pc == target[c.c]) g--
        return g
    }

    fun apply(c: CycleLibrary.Cycle, s: IntArray) {
        val pa = s[c.a]
        s[c.a] = s[c.c]
        s[c.c] = s[c.b]
        s[c.b] = pa
    }
}
