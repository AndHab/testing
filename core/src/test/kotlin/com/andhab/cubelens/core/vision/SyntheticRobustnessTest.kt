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
 */
class SyntheticRobustnessTest {

    @Test
    fun recoversRandomCubesUnderRealisticConditions() {
        val random = Random(20261001)
        val faces = SyntheticFaces(random)
        val cubes = 160
        var exact = 0
        var liveCorrect = 0
        var liveTotal = 0
        var uncertainTotal = 0
        val failures = mutableListOf<String>()
        repeat(cubes) { n ->
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
                    liveTotal++
                    if (LiveClassifier.classify(s) == shown[i]) liveCorrect++
                }
                samples
            }.shuffled(random)

            val analysis = ScanResolver.resolve(scans)
            uncertainTotal += analysis.uncertain.size
            val result = FaceletCube.fromColors(analysis.colors)
            if (analysis.isValid && result == cube) exact++ else failures += "cube $n: $cube -> $result (valid=${analysis.isValid})"
        }
        println(
            "Synthetic: resolver exact $exact/$cubes, live classifier ${"%.2f".format(100.0 * liveCorrect / liveTotal)}% " +
                "($liveCorrect/$liveTotal stickers), uncertain stickers per cube ${"%.2f".format(uncertainTotal.toDouble() / cubes)}",
        )
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertEquals(cubes, exact)
    }

    @Test
    fun recoversRandomCubesFromIdealSamplesInAnyOrientation() {
        val random = Random(5)
        repeat(200) {
            val cube = FaceletCube.scrambled(List(25) { Move.entries[random.nextInt(18)] })
            val colors = cube.toColors()
            val scans = Face.entries.map { face ->
                OrientationFixer.rotateFace(colors, face, random.nextInt(4))
                    .subList(face.ordinal * 9, face.ordinal * 9 + 9)
                    .map { c -> SyntheticFaces.BASE_SRGB.getValue(c).let { StickerSample.of(it[0], it[1], it[2]) } }
            }.shuffled(random)
            val analysis = ScanResolver.resolve(scans)
            assertTrue(analysis.isValid)
            assertEquals(cube, FaceletCube.fromColors(analysis.colors))
            assertEquals(Facelets.COUNT, analysis.rawColors.size)
        }
    }
}
