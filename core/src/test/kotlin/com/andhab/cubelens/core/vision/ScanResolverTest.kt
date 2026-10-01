package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScanResolverTest {

    private fun ideal(color: CubeColor): StickerSample = SyntheticFaces.BASE_SRGB.getValue(color).let { StickerSample.of(it[0], it[1], it[2]) }

    private fun mix(a: CubeColor, b: CubeColor, t: Double): StickerSample {
        val x = SyntheticFaces.BASE_SRGB.getValue(a)
        val y = SyntheticFaces.BASE_SRGB.getValue(b)
        val c = IntArray(3) { ColorMath.linearToSrgb((1 - t) * ColorMath.srgbToLinear(x[it]) + t * ColorMath.srgbToLinear(y[it])) }
        return StickerSample.of(c[0], c[1], c[2])
    }

    /** Scans of [cube] in facelet order (no rotation), as ideal samples. */
    private fun scansOf(cube: FaceletCube): MutableList<MutableList<StickerSample>> =
        cube.toColors().chunked(9).map { face -> face.map(::ideal).toMutableList() }.toMutableList()

    private fun assertWellFormed(analysis: ScanAnalysis) {
        assertEquals(Facelets.COUNT, analysis.rawColors.size)
        assertEquals(Facelets.COUNT, analysis.colors.size)
        assertEquals(Face.entries.toSet(), analysis.faceRotations.keys)
        assertTrue(analysis.uncertain.all { it in 0 until Facelets.COUNT })
    }

    @Test
    fun malformedInputNeverThrows() {
        val random = Random(1)
        val noise = { StickerSample.of(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val inputs: List<List<List<StickerSample>>> = listOf(
            emptyList(),
            List(5) { List(9) { noise() } },
            List(7) { List(9) { noise() } },
            List(6) { List(8) { noise() } },
            List(6) { if (it == 3) emptyList() else List(9) { noise() } },
            List(6) { List(9) { StickerSample.of(128, 128, 128) } },
            List(6) { List(9) { StickerSample(-40, 300, 1000, Lab(Float.NaN, Float.NaN, Float.NaN)) } },
            List(6) { List(9) { StickerSample.of(0, 0, 0) } },
            List(6) { List(9) { noise() } },
            // Six scans of the same face.
            List(6) { scansOf(FaceletCube.SOLVED)[2] },
        )
        for (input in inputs) {
            val analysis = ScanResolver.resolve(input)
            assertWellFormed(analysis)
            assertFalse(analysis.isValid)
        }
    }

    @Test
    fun resolvesAnUnusualCenterReading() {
        // The white center of the user's cube has a colorful logo; a careless read is bluish grey.
        val cube = FaceletCube.scrambled(Move.parseSequence("R U F' L2 D B R' U2 F"))
        val scans = scansOf(cube)
        scans[Face.U.ordinal][4] = StickerSample.of(113, 143, 176)
        val analysis = ScanResolver.resolve(scans.shuffled(Random(2)))
        assertTrue(analysis.isValid)
        assertEquals(cube, FaceletCube.fromColors(analysis.colors))
    }

    @Test
    fun repairsTwoSwappedLookingStickers() {
        val random = Random(3)
        var repaired = 0
        repeat(20) {
            val cube = FaceletCube.scrambled(List(25) { Move.entries[random.nextInt(18)] })
            val colors = cube.toColors()
            val red = (0 until 54).filter { it % 9 != 4 && colors[it] == CubeColor.RED }.random(random)
            val orange = (0 until 54).filter { it % 9 != 4 && colors[it] == CubeColor.ORANGE }.random(random)
            val scans = scansOf(cube)
            // Each looks slightly more like the other color, so joint classification swaps them.
            scans[red / 9][red % 9] = mix(CubeColor.RED, CubeColor.ORANGE, 0.6)
            scans[orange / 9][orange % 9] = mix(CubeColor.RED, CubeColor.ORANGE, 0.4)
            val classified = ScanResolver.classify(scans).colors
            assertEquals(CubeColor.ORANGE, classified[red])
            assertEquals(CubeColor.RED, classified[orange])

            val analysis = ScanResolver.resolve(scans)
            assertWellFormed(analysis)
            assertTrue(analysis.isValid)
            assertEquals(cube, FaceletCube.fromColors(analysis.colors))
            assertTrue(analysis.uncertain.containsAll(listOf(red, orange)))
            if (classified != analysis.rawColors) repaired++
        }
        // A swap of two stickers almost always makes an impossible cube that only the repair can fix.
        assertTrue("expected the swap repair to be exercised, got $repaired", repaired >= 18)
    }

    @Test
    fun uncertainStickersFollowTheFaceRotation() {
        val cube = FaceletCube.scrambled(Move.parseSequence("F R2 D' B L U' R F2"))
        val colors = cube.toColors()
        // An ambiguous red (between red and orange, nearer red) on some face.
        val target = (0 until Facelets.COUNT).first { it % 9 != 4 && colors[it] == CubeColor.RED }
        val face = Facelets.faceOf(target)
        val scans = scansOf(cube)
        scans[face.ordinal][target % 9] = mix(CubeColor.RED, CubeColor.ORANGE, 0.42)
        // The user held the cube a quarter turn clockwise while scanning that face.
        val asScanned = scans[face.ordinal]
        scans[face.ordinal] = listOf(6, 3, 0, 7, 4, 1, 8, 5, 2).map { asScanned[it] }.toMutableList()
        val analysis = ScanResolver.resolve(scans.shuffled(Random(4)))
        assertTrue(analysis.isValid)
        assertEquals(cube, FaceletCube.fromColors(analysis.colors))
        assertEquals(3, analysis.faceRotations.getValue(face))
        assertTrue("uncertain ${analysis.uncertain} should contain $target", target in analysis.uncertain)
        assertEquals(1, analysis.uncertain.size)
    }

    @Test
    fun isFast() {
        val random = Random(5)
        val inputs = List(40) {
            val colors = FaceletCube.scrambled(List(25) { Move.entries[random.nextInt(18)] }).toColors()
            // Rotated scans with per-face exposure, so the lighting model and the fixer both have work.
            Face.entries.map { face ->
                val exposure = random.nextDouble(0.6, 1.3)
                OrientationFixer.rotateFace(colors, face, random.nextInt(4))
                    .subList(face.ordinal * 9, face.ordinal * 9 + 9)
                    .map { c ->
                        val x = SyntheticFaces.BASE_SRGB.getValue(c)
                        val y = IntArray(3) { ColorMath.linearToSrgb(ColorMath.srgbToLinear(x[it]) * exposure) }
                        StickerSample.of(y[0], y[1], y[2])
                    }
            }.shuffled(random)
        }
        inputs.forEach { ScanResolver.resolve(it) } // warm-up
        val start = System.nanoTime()
        inputs.forEach { assertTrue(ScanResolver.resolve(it).isValid) }
        val perCallMs = (System.nanoTime() - start) / 1e6 / inputs.size
        println("ScanResolver.resolve: %.2f ms per cube".format(perCallMs))
        assertTrue("resolve took $perCallMs ms", perCallMs < 100.0)
    }

    @Test
    fun confidentScanHasNoUncertainStickers() {
        val cube = FaceletCube.scrambled(Move.parseSequence("L D2 B' R U F2 D L'"))
        val analysis = ScanResolver.resolve(scansOf(cube))
        assertTrue(analysis.isValid)
        assertEquals(analysis.rawColors, analysis.colors)
        assertTrue(analysis.uncertain.isEmpty())
    }
}
