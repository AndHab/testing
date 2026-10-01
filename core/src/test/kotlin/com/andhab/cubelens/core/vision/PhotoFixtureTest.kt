package com.andhab.cubelens.core.vision

import com.andhab.cubelens.core.cube.FaceletCube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** Real photos of the user's scrambled cube (see src/test/resources/photos). */
class PhotoFixtureTest {

    private val names = Photos.TRUTH.keys.toList()

    @Test
    fun liveClassifierReadsEveryPhoto() {
        for (name in names) {
            val got = Photos.scan(name).map(LiveClassifier::classify)
            assertEquals("photo $name", Photos.truth(name).letters(), got.letters())
        }
    }

    @Test
    fun liveClassifierToleratesMisalignedGuide() {
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
    fun everyStickerIsClearlyNearestToItsTrueColor() {
        // Margin between the true color's reference and the nearest other one, in deltaE units.
        var minMargin = Float.MAX_VALUE
        for (name in names) {
            val truth = Photos.truth(name)
            for ((i, s) in Photos.scan(name).withIndex()) {
                val d = LiveClassifier.distances(s.lab)
                val own = d[truth[i].ordinal]
                val other = d.filterIndexed { c, _ -> c != truth[i].ordinal }.min()
                minMargin = minOf(minMargin, other - own)
            }
        }
        println("Photos: smallest live-classification margin %.1f deltaE".format(minMargin))
        assertTrue("margin $minMargin", minMargin > 8f)
    }

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
