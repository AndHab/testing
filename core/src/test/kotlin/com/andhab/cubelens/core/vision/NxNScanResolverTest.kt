package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.ColorScheme
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.core.nxn.NxNModel
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.core.nxn.NxNValidator
import com.andhab.cubelens.core.nxn.PieceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [ScanResolver.resolve] for N×N cubes: random scrambles ([NxNScrambler]) photographed face by face in
 * the app's guided order (F, R, B, L, U, D), each face held at a random quarter turn, every photo under
 * its own exposure and white balance ([NxNSessions]), sampled with [GridSampler].
 *
 * Outcomes are counted as in [NxNSessions.Outcome]: exact; a valid but different cube with every
 * differing sticker flagged as uncertain (the scan is genuinely ambiguous: another way of holding a
 * face gives a valid cube too, which colors cannot rule out); invalid (reported, the app asks for a
 * rescan); and wrong (a different cube with an unflagged sticker), which must never happen.
 */
class NxNScanResolverTest {

    private val sizes = listOf(2, 4, 5, 6, 7)

    /** Scans and resolves [cubes] random cubes of size [n] and [look] under [light]. */
    private fun sessions(n: Int, look: CubeLook, light: NxNSessions.Light, cubes: Int, seed: Int, scheme: Map<Face, CubeColor> = ColorScheme.STANDARD.centers): NxNSessions.Tally {
        val random = Random(seed)
        val renderer = SyntheticFaces(random, look)
        val tally = NxNSessions.Tally()
        repeat(cubes) { c ->
            val truth = NxNSessions.scrambled(n, random, scheme).toColors()
            val session = NxNSessions.scan(renderer, n, truth, light, random)
            val start = System.nanoTime()
            val analysis = ScanResolver.resolve(n, session.scans, NxNSessions.GUIDED)
            val elapsed = System.nanoTime() - start
            assertWellFormed(n, analysis)
            val outcome = NxNSessions.outcome(analysis, truth)
            tally.add(outcome, analysis, {
                "${n}x$n $look $light cube $c: $outcome, sampler errors ${NxNSessions.samplerErrors(session, look)}, problems ${analysis.problems.take(3).map { it.message }}"
            }, elapsed)
            if (analysis.isValid) {
                // Even sizes are placed by scan order; odd standard cubes by their center colors.
                if (n % 2 == 0) assertEquals(Placement.SCAN_ORDER, analysis.placement)
                if (n % 2 == 1 && scheme == ColorScheme.STANDARD.centers) assertEquals(Placement.CENTER_COLORS, analysis.placement)
                assertTrue(analysis.problems.isEmpty())
            } else {
                assertTrue(analysis.problems.isNotEmpty())
            }
        }
        return tally
    }

    private fun assertWellFormed(n: Int, analysis: NxNScanAnalysis) {
        assertEquals(n, analysis.n)
        assertEquals(6 * n * n, analysis.rawColors.size)
        assertEquals(6 * n * n, analysis.colors.size)
        assertEquals(Face.entries.toSet(), analysis.faceRotations.keys)
        assertTrue(analysis.faceRotations.values.all { it in 0..3 })
        assertTrue(analysis.uncertain.all { it in 0 until 6 * n * n })
        if (analysis.isValid) assertTrue(NxNValidator.validate(analysis.toCube()).isValid)
    }

    @Test
    fun resolvesRandomCubesOfEverySizeInNormalLight() {
        // At least 30 cubes per size, vivid and pastel: every cube is resolved exactly, or (genuinely
        // ambiguous) with every differing sticker flagged; none is wrong.
        val report = StringBuilder("N×N scans in normal light (guided order, faces held at random angles):\n")
        for (look in listOf(KnockOffCubes.VIVID, KnockOffCubes.PASTEL)) {
            for (n in sizes) {
                val tally = sessions(n, look, NxNSessions.Light.NORMAL, CUBES, 1000 * n + look.name.length)
                report.append("  ${n}x$n $look: $tally\n")
                assertEquals("${n}x$n $look:\n${tally.failures.joinToString("\n")}", 0, tally[NxNSessions.Outcome.WRONG])
                assertTrue("${n}x$n $look: $tally\n${tally.failures.joinToString("\n")}", tally[NxNSessions.Outcome.INVALID] <= 1)
                assertTrue("${n}x$n $look: $tally", tally[NxNSessions.Outcome.EXACT] >= CUBES - 3)
            }
        }
        print(report)
    }

    @Test
    fun otherLightAndBodies() {
        // Warm, cool and mixed light (vivid and pastel), stickerless and white-bodied cubes: rates are
        // reported; none may be resolved wrong, and nearly all must resolve.
        val report = StringBuilder("N×N scans under other light and on other bodies:\n")
        val variants = listOf(KnockOffCubes.VIVID, KnockOffCubes.PASTEL).flatMap { look ->
            listOf(NxNSessions.Light.WARM, NxNSessions.Light.COOL, NxNSessions.Light.MIXED).map { look to it }
        } + listOf(KnockOffCubes.STICKERLESS to NxNSessions.Light.NORMAL, KnockOffCubes.WHITE_BODY to NxNSessions.Light.NORMAL)
        val total = NxNSessions.Tally()
        for ((look, light) in variants) {
            report.append("  $look, ${light.name.lowercase()} light:")
            for (n in sizes) {
                val tally = sessions(n, look, light, OTHER_CUBES, 5000 * n + 31 * light.ordinal + look.name.length)
                report.append(" ${n}x$n ${tally.resolved}/${tally.cubes} resolved (${tally[NxNSessions.Outcome.FLAGGED]} ambiguous, flagged);")
                assertEquals("${n}x$n $look $light:\n${tally.failures.joinToString("\n")}", 0, tally[NxNSessions.Outcome.WRONG])
                total.counts.indices.forEach { total.counts[it] += tally.counts[it] }
                total.cubes += tally.cubes
                total.failures += tally.failures
            }
            report.append('\n')
        }
        report.append("  total: ${total.resolved}/${total.cubes} resolved, ${total[NxNSessions.Outcome.INVALID]} invalid\n")
        total.failures.forEach { report.append("    $it\n") }
        print(report)
        assertTrue("$total", total.resolved * 100 >= total.cubes * 97)
    }

    @Test
    fun knockOffColorArrangementsWithScanPositions() {
        // The Japanese scheme (white opposite blue) on even and odd sizes, scanned in guided order.
        // Odd sizes need the scan positions (the centers don't say where they belong); even sizes are
        // always placed by scan position, and their scheme comes from the corners.
        val japanese = mapOf(
            Face.U to CubeColor.WHITE, Face.D to CubeColor.BLUE, Face.F to CubeColor.GREEN,
            Face.B to CubeColor.YELLOW, Face.R to CubeColor.RED, Face.L to CubeColor.ORANGE,
        )
        val report = StringBuilder("Japanese scheme, guided order:")
        for (n in listOf(2, 4, 5, 7)) {
            for (look in listOf(KnockOffCubes.VIVID, KnockOffCubes.PASTEL)) {
                val random = Random(700 + n)
                val renderer = SyntheticFaces(random, look)
                var exact = 0
                repeat(ARRANGEMENT_CUBES) { c ->
                    val truth = NxNSessions.scrambled(n, random, japanese).toColors()
                    val session = NxNSessions.scan(renderer, n, truth, NxNSessions.Light.NORMAL, random)
                    val analysis = ScanResolver.resolve(n, session.scans, NxNSessions.GUIDED)
                    assertEquals("${n}x$n $look cube $c", Placement.SCAN_ORDER, analysis.placement)
                    val outcome = NxNSessions.outcome(analysis, truth)
                    assertTrue("${n}x$n $look cube $c: $outcome ${analysis.problems}", outcome == NxNSessions.Outcome.EXACT || outcome == NxNSessions.Outcome.FLAGGED)
                    if (outcome == NxNSessions.Outcome.EXACT) exact++
                    val blind = ScanResolver.resolve(n, session.scans)
                    if (n % 2 == 0) {
                        // Without positions, even sizes assume the guided order: the same result.
                        assertEquals(analysis, blind)
                    } else {
                        // Odd sizes can't place a non-standard arrangement without them: no crash, no wrong cube.
                        assertFalse(blind.isValid)
                        assertEquals(Placement.CENTER_COLORS, blind.placement)
                        assertTrue(blind.problems.isNotEmpty())
                    }
                }
                report.append(" ${n}x$n $look $exact/$ARRANGEMENT_CUBES exact;")
            }
        }
        println(report)
    }

    @Test
    fun oddSizesPlaceStandardCubesByCenterColorInAnyOrder() {
        val random = Random(6)
        for (n in listOf(5, 7)) {
            val renderer = SyntheticFaces(random, KnockOffCubes.PASTEL)
            repeat(4) {
                val truth = NxNSessions.scrambled(n, random).toColors()
                val order = Face.entries.shuffled(random)
                val session = NxNSessions.scan(renderer, n, truth, NxNSessions.Light.NORMAL, random, order)
                for (positions in listOf(null, NxNSessions.GUIDED)) {
                    // The guided positions are wrong for this order, and the centers place the scans anyway.
                    val analysis = ScanResolver.resolve(n, session.scans, positions)
                    assertEquals(Placement.CENTER_COLORS, analysis.placement)
                    val outcome = NxNSessions.outcome(analysis, truth)
                    assertTrue("${n}x$n: $outcome", outcome == NxNSessions.Outcome.EXACT || outcome == NxNSessions.Outcome.FLAGGED)
                }
            }
        }
    }

    @Test
    fun threeByThreeAgreesWithTheThreeByThreeResolver() {
        fun assertAgree(scans: List<List<StickerSample>>, positions: List<Face>?) {
            val expected = ScanResolver.resolve(scans, positions)
            val actual = ScanResolver.resolve(3, scans, positions)
            assertEquals(3, actual.n)
            assertEquals(expected.rawColors, actual.rawColors)
            assertEquals(expected.colors, actual.colors)
            assertEquals(expected.faceRotations, actual.faceRotations)
            assertEquals(expected.isValid, actual.isValid)
            assertEquals(expected.uncertain, actual.uncertain)
            assertEquals(expected.palette, actual.palette)
            assertEquals(expected.placement, actual.placement)
            assertEquals(expected.isValid, actual.problems.isEmpty())
        }
        // The user's real photos, in several orders, with and without scan positions.
        val faces = listOf("1_red", "2_green", "3_blue", "4_white", "6_yellow", "7_orange")
        for (redFace in listOf("1_red", "5_red_rotated")) {
            val scans = faces.map { if (it == "1_red") Photos.scan(redFace) else Photos.scan(it) }
            for (order in listOf(listOf(0, 1, 2, 3, 4, 5), listOf(5, 4, 3, 2, 1, 0), listOf(3, 0, 5, 1, 4, 2))) {
                for (positions in listOf(null, NxNSessions.GUIDED)) assertAgree(order.map { scans[it] }, positions)
            }
        }
        // Synthetic cubes of every look, standard and Japanese arrangement, and malformed input.
        val random = Random(7)
        val japanese = ColorScheme(mapOf(Face.U to CubeColor.WHITE, Face.D to CubeColor.BLUE, Face.F to CubeColor.GREEN, Face.B to CubeColor.YELLOW, Face.R to CubeColor.RED, Face.L to CubeColor.ORANGE))
        for (look in KnockOffCubes.ALL) {
            val renderer = SyntheticFaces(random, look)
            repeat(4) { c ->
                val cube = FaceletCube.scrambled(List(25) { Move.entries[random.nextInt(18)] })
                val colors = cube.toColors(if (c % 2 == 0) ColorScheme.STANDARD else japanese)
                val scans = NxNSessions.GUIDED.map { face ->
                    val (image, guide) = renderer.render(OrientationFixer.rotateFace(colors, face, random.nextInt(4)).subList(face.ordinal * 9, face.ordinal * 9 + 9), renderer.randomConditions(), 128)
                    GridSampler.sample(image, guide, 0, 3)
                }
                for (positions in listOf(null, NxNSessions.GUIDED)) assertAgree(scans, positions)
            }
        }
        assertAgree(emptyList(), null)
        assertAgree(List(6) { List(9) { StickerSample.of(128, 128, 128) } }, NxNSessions.GUIDED)
        assertAgree(List(6) { List(8) { StickerSample.of(200, 10, 10) } }, null)
    }

    @Test
    fun garbageNeverThrows() {
        val random = Random(8)
        val noise = { StickerSample.of(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        for (n in 2..7) {
            val per = n * n
            val cube = NxNSessions.scrambled(n, random).toColors()
            val ideal = NxNSessions.GUIDED.map { face -> NxNSessions.faceAsHeld(n, cube, face, 0).map(KnockOffCubes.VIVID::sample) }
            val inputs: List<List<List<StickerSample>>> = listOf(
                emptyList(),
                List(5) { List(per) { noise() } },
                List(7) { List(per) { noise() } },
                List(6) { List(per - 1) { noise() } },
                List(6) { List(9) { noise() } },
                List(6) { if (it == 3) emptyList() else List(per) { noise() } },
                List(6) { List(per) { StickerSample.of(128, 128, 128) } },
                List(6) { k -> List(per) { StickerSample.of(40 + 35 * k, 40 + 35 * k, 40 + 35 * k) } },
                List(6) { List(per) { StickerSample(-40, 300, 1000, Lab(Float.NaN, Float.NaN, Float.NaN)) } },
                List(6) { List(per) { StickerSample.of(0, 0, 0) } },
                List(6) { List(per) { noise() } },
                // The same face scanned six times, and one face scanned twice.
                List(6) { ideal[0] },
                ideal.take(5) + listOf(ideal[0]),
            )
            for (input in inputs) {
                for (positions in listOf(null, NxNSessions.GUIDED, listOf(Face.U, Face.U, Face.F, Face.F, Face.R, Face.R), emptyList())) {
                    val analysis = ScanResolver.resolve(n, input, positions)
                    assertWellFormed(n, analysis)
                    assertFalse("${n}x$n ${input.size} scans", analysis.isValid)
                    assertTrue(analysis.problems.isNotEmpty())
                }
            }
            // The well-formed scan itself resolves.
            assertTrue(ScanResolver.resolve(n, ideal, NxNSessions.GUIDED).isValid)
        }
    }

    @Test
    fun idealSamplesOfEverySupportedSize() {
        // Up to the engine's largest size: solved cubes and scrambles from ideal samples, faces held at
        // random angles; exact, or ambiguous with every differing sticker flagged.
        val random = Random(9)
        for (n in 2..10) {
            val solved = NxNCube.solved(n).toColors()
            val solvedScans = NxNSessions.GUIDED.map { face -> NxNSessions.faceAsHeld(n, solved, face, random.nextInt(4)).map(KnockOffCubes.PASTEL::sample) }
            val analysis = ScanResolver.resolve(n, solvedScans, NxNSessions.GUIDED)
            assertEquals(solved, analysis.colors)
            assertTrue(analysis.uncertain.isEmpty())
            repeat(3) {
                val truth = NxNSessions.scrambled(n, random).toColors()
                val turns = List(6) { random.nextInt(4) }
                val scans = NxNSessions.GUIDED.mapIndexed { k, face -> NxNSessions.faceAsHeld(n, truth, face, turns[k]).map(KnockOffCubes.VIVID::sample) }
                val outcome = NxNSessions.outcome(ScanResolver.resolve(n, scans, NxNSessions.GUIDED), truth)
                assertTrue("${n}x$n: $outcome", outcome == NxNSessions.Outcome.EXACT || outcome == NxNSessions.Outcome.FLAGGED)
            }
        }
    }

    @Test
    fun facesHeldInTheReferenceOrientationAreNeverReRotated() {
        // Half-turn scrambles admit several valid readings; held as the guide asks, the preferred one is
        // the cube itself. Held at random angles, wrong stickers are all flagged.
        val random = Random(10)
        for (n in sizes) {
            repeat(6) {
                val truth = NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, random).map { m -> m.copy(turns = 2) }).toColors()
                val asGuided = NxNSessions.GUIDED.map { face -> NxNSessions.faceAsHeld(n, truth, face, 0).map(KnockOffCubes.VIVID::sample) }
                assertEquals(truth, ScanResolver.resolve(n, asGuided, NxNSessions.GUIDED).colors)
                val turns = List(6) { random.nextInt(4) }
                val held = NxNSessions.GUIDED.mapIndexed { k, face -> NxNSessions.faceAsHeld(n, truth, face, turns[k]).map(KnockOffCubes.VIVID::sample) }
                val outcome = NxNSessions.outcome(ScanResolver.resolve(n, held, NxNSessions.GUIDED), truth)
                assertTrue("${n}x$n: $outcome", outcome == NxNSessions.Outcome.EXACT || outcome == NxNSessions.Outcome.FLAGGED)
            }
        }
    }

    /** [look]'s colors [a] and [b] mixed in linear light ([t] of [b]). */
    private fun mix(look: CubeLook, a: CubeColor, b: CubeColor, t: Double): StickerSample {
        val x = look.linear.getValue(a)
        val y = look.linear.getValue(b)
        val c = IntArray(3) { ColorMath.linearToSrgb((1 - t) * x[it] + t * y[it]) }
        return StickerSample.of(c[0], c[1], c[2])
    }

    @Test
    fun closeCallsAreFlaggedAndSwappedLookingStickersRepaired() {
        // A red and an orange sticker that each look more like the other color: the joint
        // classification may swap them. On corners and edges that makes an impossible cube, which the
        // swap search repairs. Two movable centers of one orbit swapped still make a valid cube (big
        // cubes' centers of one color are interchangeable), which no validation can catch: as close
        // calls, both are flagged.
        val random = Random(11)
        val look = KnockOffCubes.VIVID
        for (n in listOf(4, 5)) {
            val geometry = NxNGeometry.of(n)
            for (centers in listOf(false, true)) {
                var repaired = 0
                repeat(6) {
                    val truth = NxNSessions.scrambled(n, random).toColors()
                    val scans = NxNSessions.GUIDED.map { face -> NxNSessions.faceAsHeld(n, truth, face, 0).map(look::sample).toMutableList() }
                    val indexOf = { (k, p): Pair<Int, Int> -> NxNSessions.GUIDED[k].ordinal * n * n + p }
                    val candidates = scans.indices.flatMap { k -> (0 until n * n).map { k to it } }.filter {
                        val kind = geometry.kindOf(indexOf(it))
                        if (centers) kind == PieceKind.CENTER else kind != PieceKind.CENTER && kind != PieceKind.FIXED_CENTER
                    }
                    val red = candidates.filter { truth[indexOf(it)] == CubeColor.RED }.random(random)
                    val orange = candidates.filter {
                        truth[indexOf(it)] == CubeColor.ORANGE && (!centers || NxNModel.of(n).orbitIndexOf(indexOf(it)) == NxNModel.of(n).orbitIndexOf(indexOf(red)))
                    }.randomOrNull(random) ?: return@repeat
                    // Centers: nearly halfway, a close call either way (a confident misread of two centers
                    // of one orbit would be a valid cube that nothing can tell from the real one).
                    val t = if (centers) 0.55 else 0.7
                    scans[red.first][red.second] = mix(look, CubeColor.RED, CubeColor.ORANGE, t)
                    scans[orange.first][orange.second] = mix(look, CubeColor.RED, CubeColor.ORANGE, 1 - t)
                    val classified = NxNScanResolver.classify(n, scans, NxNSessions.GUIDED).colors
                    val analysis = ScanResolver.resolve(n, scans, NxNSessions.GUIDED)
                    assertTrue(analysis.isValid)
                    assertTrue(analysis.uncertain.containsAll(listOf(indexOf(red), indexOf(orange))))
                    val wrong = truth.indices.filter { analysis.colors[it] != truth[it] }.toSet()
                    if (centers) {
                        assertTrue(wrong.isEmpty() || wrong == setOf(indexOf(red), indexOf(orange)))
                    } else {
                        assertEquals(truth, analysis.colors)
                        if (classified != analysis.rawColors) repaired++
                    }
                }
                if (!centers) {
                    println("${n}x$n: $repaired of 6 swapped-looking edge or corner pairs repaired by the swap search (the rest were read right)")
                    assertTrue("${n}x$n: expected the swap repair to be exercised, got $repaired", repaired >= 3)
                }
            }
            // Clearly read cubes have no uncertain stickers.
            val clean = NxNSessions.scrambled(n, random).toColors()
            val cleanScans = NxNSessions.GUIDED.map { face -> NxNSessions.faceAsHeld(n, clean, face, 0).map(look::sample) }
            val analysis = ScanResolver.resolve(n, cleanScans, NxNSessions.GUIDED)
            assertEquals(clean, analysis.colors)
            assertTrue(analysis.uncertain.isEmpty())
            assertEquals(analysis.rawColors, analysis.colors)
        }
    }

    @Test
    fun aConfidentMisreadIsCorrectedOrFlaggedNeverWrong() {
        // One sticker read confidently as another color (as when the sampler reads a sticker partly off
        // its tile): every color must still appear n * n times, so another sticker showing that color is
        // forced into the missing one. From 4x4 on the resolver corrects the misread; where another
        // sticker could equally be the misread one (two wings with the same second color, centers of
        // one orbit), every sticker that depends on it is flagged. A 2x2 has too few pieces to tell,
        // and is reported invalid or flagged, never silently wrong.
        val random = Random(14)
        val report = StringBuilder("One confident misread (ideal samples, faces held at random angles):")
        for (n in listOf(2, 4, 5, 6, 7)) {
            val geometry = NxNGeometry.of(n)
            val counts = IntArray(NxNSessions.Outcome.entries.size)
            repeat(MISREAD_CUBES) { c ->
                val truth = NxNSessions.scrambled(n, random).toColors()
                val turns = List(6) { random.nextInt(4) }
                val scans = NxNSessions.GUIDED.mapIndexed { k, face -> NxNSessions.faceAsHeld(n, truth, face, turns[k]).map(KnockOffCubes.VIVID::sample).toMutableList() }
                val k = random.nextInt(6)
                val p = (0 until n * n).filter { geometry.kindOf(it) != PieceKind.FIXED_CENTER }.random(random)
                val shown = NxNSessions.faceAsHeld(n, truth, NxNSessions.GUIDED[k], turns[k])[p]
                scans[k][p] = KnockOffCubes.VIVID.sample(CubeColor.entries.filter { it != shown }.random(random))
                val analysis = ScanResolver.resolve(n, scans, NxNSessions.GUIDED)
                val outcome = NxNSessions.outcome(analysis, truth)
                counts[outcome.ordinal]++
                assertTrue("${n}x$n cube $c: wrong without a flag", outcome != NxNSessions.Outcome.WRONG)
            }
            val valid = counts[NxNSessions.Outcome.EXACT.ordinal] + counts[NxNSessions.Outcome.FLAGGED.ordinal]
            report.append(" ${n}x$n $valid/$MISREAD_CUBES valid (${counts[NxNSessions.Outcome.FLAGGED.ordinal]} with the possible misreads flagged);")
            if (n >= 4) assertTrue("${n}x$n: only $valid of $MISREAD_CUBES corrected", valid * 4 >= MISREAD_CUBES * 3)
        }
        println(report)
    }

    @Test
    fun anotherValidReadingOfCloseCallsIsFlagged() {
        // A very pale pastel 2x2 in mixed light: a white and an orange sticker were close calls read the
        // wrong way round, which together with the front face taken as held the other way up made a
        // valid cube (only the corners can be checked). Read the right way round they make the real
        // cube, so every sticker the two readings disagree on is flagged, front face included.
        val random = Random(210807)
        val renderer = SyntheticFaces(random, KnockOffCubes.PALE)
        repeat(3) { c ->
            val truth = NxNSessions.scrambled(2, random).toColors()
            val session = NxNSessions.scan(renderer, 2, truth, NxNSessions.Light.MIXED, random)
            val analysis = ScanResolver.resolve(2, session.scans, NxNSessions.GUIDED)
            val outcome = NxNSessions.outcome(analysis, truth)
            if (c == 2) {
                assertEquals(NxNSessions.Outcome.FLAGGED, outcome)
            } else {
                assertEquals(NxNSessions.Outcome.EXACT, outcome)
            }
        }
    }

    @Test
    fun rivalClusteringsOfATwoByTwoAreFlagged() {
        // A very pale pastel 2x2 in cool light: with four stickers per photo the per-photo lighting
        // model explains the stickers about as well with two different clusterings, and both make a
        // valid cube (only the corners can be checked). The cheaper one is wrong here; every sticker
        // the two disagree on is flagged.
        val random = Random(215170)
        val renderer = SyntheticFaces(random, KnockOffCubes.PALE)
        repeat(23) { c ->
            val truth = NxNSessions.scrambled(2, random).toColors()
            val session = NxNSessions.scan(renderer, 2, truth, NxNSessions.Light.COOL, random)
            if (c == 22) {
                val analysis = ScanResolver.resolve(2, session.scans, NxNSessions.GUIDED)
                assertEquals(NxNSessions.Outcome.FLAGGED, NxNSessions.outcome(analysis, truth))
            }
        }
    }

    @Test
    fun paletteIsEstimatedForEverySize() {
        val random = Random(12)
        for (n in listOf(2, 4, 7)) {
            for ((look, standardLike) in listOf(KnockOffCubes.VIVID to true, KnockOffCubes.PASTEL to false, KnockOffCubes.CANDY to false)) {
                val renderer = SyntheticFaces(random, look)
                val truth = NxNSessions.scrambled(n, random).toColors()
                val session = NxNSessions.scan(renderer, n, truth, NxNSessions.Light.NORMAL, random)
                val analysis = ScanResolver.resolve(n, session.scans, NxNSessions.GUIDED)
                assertEquals("${n}x$n $look", standardLike, analysis.palette.isStandardLike)
                if (!standardLike) {
                    // Each color is drawn in its own hue (pink stays pink).
                    val design = KnockOffCubes.DESIGN_HEX.getValue(look)
                    for (color in CubeColor.entries.filter { it != CubeColor.WHITE }) {
                        val expected = StickerSample.ofArgb(design.getValue(color)).lab.hue
                        val estimated = StickerSample.ofArgb(analysis.palette.colors.getValue(color)).lab.hue
                        val d = Math.abs(expected - estimated) % 360f
                        assertTrue("${n}x$n $look $color: hue $estimated vs $expected", minOf(d, 360f - d) <= 15f)
                    }
                }
            }
        }
    }

    @Test
    fun isFastForEverySize() {
        val report = StringBuilder("ScanResolver.resolve per cube (rendered scans, pastel, normal light):")
        for (n in listOf(2, 3, 4, 5, 6, 7)) {
            val random = Random(13 + n)
            val renderer = SyntheticFaces(random, KnockOffCubes.PASTEL)
            val inputs = List(8) {
                val truth = NxNSessions.scrambled(n, random).toColors()
                NxNSessions.scan(renderer, n, truth, NxNSessions.Light.NORMAL, random).scans
            }
            inputs.forEach { ScanResolver.resolve(n, it, NxNSessions.GUIDED) } // warm-up
            var worst = 0L
            var total = 0L
            for (input in inputs) {
                val start = System.nanoTime()
                ScanResolver.resolve(n, input, NxNSessions.GUIDED)
                val elapsed = System.nanoTime() - start
                worst = maxOf(worst, elapsed)
                total += elapsed
            }
            report.append(" ${n}x$n average %.1f ms, worst %.1f ms;".format(total / 1e6 / inputs.size, worst / 1e6))
            assertTrue("${n}x$n worst ${worst / 1e6} ms", worst / 1e6 < 500.0)
        }
        println(report)
    }

    @Test
    fun rejectsUnsupportedSizes() {
        for (n in listOf(-1, 0, 1, 11)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ScanResolver.resolve(n, emptyList()) }
        }
    }

    private companion object {
        const val CUBES = 30
        const val OTHER_CUBES = 8
        const val ARRANGEMENT_CUBES = 6
        const val MISREAD_CUBES = 20
    }
}
