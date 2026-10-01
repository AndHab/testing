package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * End-to-end robustness on rendered photos: random scrambles, every face photographed under its own
 * exposure and white balance, held at a random angle, with a random camera buffer rotation, and the
 * scans handed over in random order.
 *
 * Cubes alternate between two sticker palettes: nominal brand colors, which nothing in the vision code
 * was calibrated on, and colors like the user's cube in the photo fixtures.
 */
class SyntheticRobustnessTest {

    @Test
    fun recoversRandomCubesUnderRealisticConditions() {
        val random = Random(20261001)
        val palettes = SyntheticFaces.Palette.entries
        val renderers = palettes.associateWith { SyntheticFaces(random, it) }
        val cubes = 160
        var exact = 0
        val liveCorrect = palettes.associateWith { 0 }.toMutableMap()
        val liveTotal = palettes.associateWith { 0 }.toMutableMap()
        var uncertainTotal = 0
        val failures = mutableListOf<String>()
        repeat(cubes) { n ->
            val palette = palettes[n % palettes.size]
            val faces = renderers.getValue(palette)
            val cube = FaceletCube.scrambled(List(random.nextInt(20, 31)) { Move.entries[random.nextInt(18)] })
            val colors = cube.toColors()
            val scans = Face.entries.map { face ->
                val heldAt = random.nextInt(4)
                val shown = OrientationFixer.rotateFace(colors, face, heldAt).subList(face.ordinal * 9, face.ordinal * 9 + 9)
                val (upright, guide) = faces.render(shown, faces.randomConditions())
                val cameraRotation = 90 * random.nextInt(4)
                val buffer = upright.asBufferNeedingRotation(cameraRotation)
                val samples = GridSampler.sample(buffer, upright.regionInBuffer(guide, cameraRotation), cameraRotation)
                samples.forEachIndexed { i, s ->
                    liveTotal[palette] = liveTotal.getValue(palette) + 1
                    if (LiveClassifier.classify(s) == shown[i]) liveCorrect[palette] = liveCorrect.getValue(palette) + 1
                }
                samples
            }.shuffled(random)

            val analysis = ScanResolver.resolve(scans)
            uncertainTotal += analysis.uncertain.size
            val result = FaceletCube.fromColors(analysis.colors)
            if (analysis.isValid && result == cube) exact++ else failures += "cube $n ($palette): $cube -> $result (valid=${analysis.isValid})"
        }
        val live = palettes.joinToString { "$it ${"%.2f".format(100.0 * liveCorrect.getValue(it) / liveTotal.getValue(it))}% (${liveCorrect[it]}/${liveTotal[it]})" }
        println(
            "Synthetic: resolver exact $exact/$cubes, live classifier $live, " +
                "uncertain stickers per cube ${"%.2f".format(uncertainTotal.toDouble() / cubes)}",
        )
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(cubes, exact)
        // The live preview is not used for the final colors, but should be right almost always even
        // on the palette it was not calibrated on.
        for (palette in palettes) {
            val accuracy = liveCorrect.getValue(palette).toDouble() / liveTotal.getValue(palette)
            assertTrue("live classifier on $palette: $accuracy", accuracy >= 0.99)
        }
    }

    @Test
    fun recoversRandomCubesFromIdealSamplesInAnyOrientation() {
        val random = Random(5)
        repeat(200) { n ->
            val palette = SyntheticFaces.Palette.entries[n % SyntheticFaces.Palette.entries.size]
            val cube = FaceletCube.scrambled(List(25) { Move.entries[random.nextInt(18)] })
            val colors = cube.toColors()
            val scans = Face.entries.map { face ->
                OrientationFixer.rotateFace(colors, face, random.nextInt(4))
                    .subList(face.ordinal * 9, face.ordinal * 9 + 9)
                    .map(palette::sample)
            }.shuffled(random)
            val analysis = ScanResolver.resolve(scans)
            assertTrue(analysis.isValid)
            assertEquals(cube, FaceletCube.fromColors(analysis.colors))
            assertEquals(Facelets.COUNT, analysis.rawColors.size)
        }
    }
}
