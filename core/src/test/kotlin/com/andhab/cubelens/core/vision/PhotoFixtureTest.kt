package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Real photos of the user's scrambled cube (see src/test/resources/photos).
 *
 * [LiveClassifier.reference] was calibrated on these photos, so the tests that classify them with it
 * are calibration checks (they catch regressions in sampling and classification, but say little about
 * other cubes or light). [liveClassificationGeneralizesToHeldOutPhotos] is the generalization check:
 * references re-derived without the photo under test. The resolver tests are not affected: the
 * resolver only uses the references to tell the six centers apart and otherwise compares the scans
 * with each other.
 */
class PhotoFixtureTest {

    private val names = Photos.TRUTH.keys.toList()

    @Test
    fun liveClassifierReadsItsCalibrationPhotos() {
        for (name in names) {
            val got = Photos.scan(name).map(LiveClassifier::classify)
            assertEquals("photo $name", Photos.truth(name).letters(), got.letters())
        }
    }

    @Test
    fun liveClassifierReadsCalibrationPhotosThroughAMisalignedGuide() {
        val base = Photos.REGION
        var checked = 0
        for (name in names) {
            for (shiftX in listOf(-0.04, 0.0, 0.04)) {
                for (shiftY in listOf(-0.04, 0.0, 0.04)) {
                    for (scale in listOf(0.94, 1.0, 1.06)) {
                        val size = (base.size * scale).roundToInt()
                        val center = base.left + base.size / 2.0
                        val left = (center - size / 2.0 + shiftX * base.size).roundToInt()
                        val top = (center - size / 2.0 + shiftY * base.size).roundToInt()
                        val region = GridRegion(left, top, size)
                        val got = Photos.scan(name, region).map(LiveClassifier::classify)
                        assertEquals("photo $name region $region", Photos.truth(name).letters(), got.letters())
                        checked++
                    }
                }
            }
        }
        assertEquals(names.size * 27, checked)
    }

    @Test
    fun calibrationPhotosAreClearlyNearestToTheirTrueColor() {
        // Margin between the true color's reference and the nearest other one, in deltaE units.
        var minMargin = Float.MAX_VALUE
        for (name in names) {
            val truth = Photos.truth(name)
            for ((i, s) in Photos.scan(name).withIndex()) {
                minMargin = minOf(minMargin, margin(LiveClassifier.distances(s.lab), truth[i]))
            }
        }
        println("Photos (calibration data): smallest live-classification margin %.1f deltaE".format(minMargin))
        assertTrue("margin $minMargin", minMargin > 8f)
    }

    @Test
    fun liveClassificationGeneralizesToHeldOutPhotos() {
        // Leave one face out: references are the mean Lab of each color's stickers on the other photos,
        // the same procedure the shipped references come from, and the held-out face is classified
        // with them. Photos 1 and 5 show the same stickers, so they are held out together.
        val samples = names.associateWith { Photos.scan(it) }
        var minMargin = Float.MAX_VALUE
        var correct = 0
        var total = 0
        for (name in names) {
            val heldOut = if (name.endsWith("_red") || name.endsWith("_red_rotated")) setOf("1_red", "5_red_rotated") else setOf(name)
            val references = CubeColor.entries.map { color ->
                val labs = names.filter { it !in heldOut }.flatMap { other ->
                    val truth = Photos.truth(other)
                    samples.getValue(other).filterIndexed { i, _ -> truth[i] == color }.map { it.lab }
                }
                Lab(labs.map { it.l }.average().toFloat(), labs.map { it.a }.average().toFloat(), labs.map { it.b }.average().toFloat())
            }
            val truth = Photos.truth(name)
            for ((i, s) in samples.getValue(name).withIndex()) {
                total++
                if (LiveClassifier.classify(s, references) == truth[i]) correct++
                minMargin = minOf(minMargin, margin(LiveClassifier.distances(s.lab, references), truth[i]))
            }
        }
        println("Photos (held out): live classification $correct/$total, smallest margin %.1f deltaE".format(minMargin))
        assertEquals(total, correct)
        assertTrue("margin $minMargin", minMargin > 5f)
    }

    /** Distance to the nearest wrong color minus distance to [truth], from per-color [distances]. */
    private fun margin(distances: FloatArray, truth: CubeColor): Float =
        distances.filterIndexed { c, _ -> c != truth.ordinal }.min() - distances[truth.ordinal]

    @Test
    fun cameraRotationGivesTheSameSamples() {
        for ((k, name) in names.withIndex()) {
            val rotation = 90 * (k % 4)
            val photo = Photos.load(name)
            val buffer = photo.asBufferNeedingRotation(rotation)
            val samples = GridSampler.sample(buffer, photo.regionInBuffer(Photos.REGION, rotation), rotation)
            assertEquals("photo $name at $rotation", Photos.scan(name), samples)
        }
    }

    @Test
    fun resolvesTheUsersCubeFromAnyScanOrder() {
        val expected = "DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR"
        val faces = listOf("1_red", "2_green", "3_blue", "4_white", "6_yellow", "7_orange")
        val orders = listOf(
            listOf(0, 1, 2, 3, 4, 5),
            listOf(5, 4, 3, 2, 1, 0),
            listOf(3, 0, 5, 1, 4, 2),
            listOf(2, 5, 0, 4, 3, 1),
        )
        // Photo 5 is the red face again, photographed upside down.
        for (redFace in listOf("1_red", "5_red_rotated")) {
            val scans = faces.map { if (it == "1_red") Photos.scan(redFace) else Photos.scan(it) }
            for (order in orders) {
                val analysis = ScanResolver.resolve(order.map { scans[it] })
                assertTrue("not valid with $redFace, order $order: ${analysis.rawColors.letters()}", analysis.isValid)
                assertEquals(expected, FaceletCube.fromColors(analysis.colors)!!.toFaceletString())
                assertTrue("uncertain: ${analysis.uncertain}", analysis.uncertain.isEmpty())
            }
        }
    }
}
