package com.andhab.cubelens.ui.solve

import androidx.compose.ui.geometry.Offset
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The layer pictogram of [TurnGlyph]: which stickers light up for a move, and which way its arrow
 * runs on screen, checked against the notation for wide moves, inner slices and layer ranges from
 * every side.
 */
class TurnPictogramTest {

    /** (row, col) of the lit stickers of [face] for [notation] on an [n]×[n] cube. */
    private fun lit(notation: String, n: Int, face: Face): Set<Pair<Int, Int>> {
        val move = LayerMove.parse(notation)
        val pictogram = CubePictogram.of(n)
        val geometry = NxNGeometry.of(n)
        return pictogram.stickers
            .filter { it.face == face && pictogram.isMoved(it.index, move) }
            .map { geometry.rowOf(it.index) to geometry.colOf(it.index) }
            .toSet()
    }

    private fun columns(n: Int, vararg cols: Int) = (0 until n).flatMap { r -> cols.map { r to it } }.toSet()
    private fun rows(n: Int, vararg rows: Int) = rows.flatMap { r -> (0 until n).map { r to it } }.toSet()
    private fun all(n: Int) = rows(n, *IntArray(n) { it })

    @Test
    fun theVisibleFacesAreTopFrontAndRight() {
        for (n in listOf(2, 3, 4, 7)) {
            val stickers = CubePictogram.of(n).stickers
            assertEquals(3 * n * n, stickers.size)
            assertEquals(setOf(Face.U, Face.F, Face.R), stickers.map { it.face }.toSet())
        }
    }

    @Test
    fun layersCountedFromTheRightOrLeftLightColumns() {
        // Front face columns run left to right; the top face's too.
        assertEquals(columns(4, 3), lit("R", 4, Face.F))
        assertEquals(all(4), lit("R", 4, Face.R))
        assertEquals(columns(4, 2, 3), lit("Rw", 4, Face.F))
        assertEquals(columns(4, 1, 2, 3), lit("3Rw'", 4, Face.U))
        assertEquals(columns(3, 1), lit("2R", 3, Face.F))
        assertEquals(columns(5, 2, 3), lit("2-3Rw", 5, Face.F))
        assertEquals(emptySet<Pair<Int, Int>>(), lit("2-3Rw", 5, Face.R))
        assertEquals(columns(4, 0, 1), lit("Lw", 4, Face.F))
        assertEquals(columns(7, 3), lit("4L", 7, Face.U))
    }

    @Test
    fun layersCountedFromTheTopOrBottomLightRows() {
        assertEquals(rows(4, 0, 1), lit("Uw", 4, Face.F))
        assertEquals(all(4), lit("Uw", 4, Face.U))
        assertEquals(rows(5, 2), lit("3U'", 5, Face.R))
        assertEquals(rows(4, 2), lit("2D", 4, Face.F))
        assertEquals(rows(6, 2, 3, 4), lit("2-4Dw", 6, Face.R))
    }

    @Test
    fun layersCountedFromTheFrontOrBackLightTopRowsAndRightColumns() {
        // The top face's last row touches the front; the right face's first column does.
        assertEquals(rows(4, 1, 2, 3), lit("3Fw", 4, Face.U))
        assertEquals(columns(4, 0, 1, 2), lit("3Fw", 4, Face.R))
        assertEquals(all(4), lit("3Fw", 4, Face.F))
        assertEquals(rows(4, 1), lit("2B2", 4, Face.U))
        assertEquals(columns(6, 1, 2, 3, 4), lit("2-5Fw'", 6, Face.R))
        assertEquals(emptySet<Pair<Int, Int>>(), lit("2-5Fw'", 6, Face.F))
    }

    /** Screen direction (y down) from the arrow's tail to its tip. */
    private fun travel(notation: String, n: Int): Pair<Float, Float> {
        val pictogram = CubePictogram.of(n)
        val path = turnArrowPath(LayerMove.parse(notation), n).map(pictogram::project)
        return (path.last().x - path.first().x) to (path.last().y - path.first().y)
    }

    @Test
    fun arrowsRunWhereTheStickersGo() {
        for (n in listOf(3, 4, 7)) {
            // R clockwise lifts the front up over the top; L clockwise brings the top down the front.
            for (m in listOf("R", "Rw", "2R", "2-3Rw", "L'", "2L'", "Lw'")) assertTrue("$m up", travel(m, n).second < 0f)
            for (m in listOf("L", "Lw", "2L", "R'", "2R'", "3Rw'")) assertTrue("$m down", travel(m, n).second > 0f)
            // U clockwise carries the front to the left; D clockwise to the right.
            for (m in listOf("U", "Uw", "2U", "D'", "2D'")) assertTrue("$m left", travel(m, n).first < 0f)
            for (m in listOf("D", "Dw", "2D", "U'", "3Uw'")) assertTrue("$m right", travel(m, n).first > 0f)
            // F clockwise carries the top to the right; B clockwise to the left.
            for (m in listOf("F", "Fw", "2F", "B'", "2B'")) assertTrue("$m right", travel(m, n).first > 0f)
            for (m in listOf("B", "Bw", "2B", "F'", "2F'")) assertTrue("$m left", travel(m, n).first < 0f)
        }
        // A half turn goes the clockwise way.
        assertEquals(travel("R", 4), travel("R2", 4))
        assertEquals(travel("2B", 4), travel("2B2", 4))
    }

    @Test
    fun arrowsRunAlongTheMiddleOfTheTurningBand() {
        val n = 5
        for (notation in listOf("2R", "2-3Rw", "Lw", "3U", "2-4Dw", "2F", "Bw")) {
            val move = LayerMove.parse(notation)
            val path = turnArrowPath(move, n)
            val axisValues = path.map {
                when (move.face) {
                    Face.R, Face.L -> it.x
                    Face.U, Face.D -> it.y
                    Face.F, Face.B -> it.z
                }
            }
            // The arrow keeps to one plane across the band's middle layer.
            assertEquals(notation, 1, axisValues.distinct().size)
            val sign = if (move.face == Face.R || move.face == Face.U || move.face == Face.F) 1f else -1f
            val middleDepth = (move.fromDepth + move.toDepth) / 2f
            assertEquals(notation, sign * (1f - (2f * middleDepth - 1f) / n), axisValues.first(), 1e-5f)
        }
    }

    /** Every layer move of one layer on an [n]×[n] cube, outer and inner, from every side. */
    private fun singleLayers(n: Int) = Face.entries.flatMap { face -> (1..n).map { LayerMove(face, it, it, 1) } }

    private fun axisValue(face: Face, point: CubePoint) = when (face) {
        Face.R, Face.L -> point.x
        Face.U, Face.D -> point.y
        Face.F, Face.B -> point.z
    }

    @Test
    fun thinBandsGetTheirArrowAlongsideSoTheWholeBandShows() {
        for (n in 4..7) {
            for (move in singleLayers(n)) {
                assertTrue("$move on $n", isNarrowBand(move, n))
                val center = bandCenter(move, n)
                val half = bandHalfWidth(move, n)
                val lane = arrowLane(move, n, clearance = 0.2f)
                // Clear of the band, on the cube, and toward its middle.
                assertTrue("${move.notation} on $n clear of the band", abs(lane - center) >= half + 0.2f - 1e-4f || abs(lane) == 0.85f)
                assertTrue("${move.notation} on $n on the cube", abs(lane) <= 0.85f)
                if (abs(center) > 1e-3f) assertTrue("${move.notation} on $n toward the middle", abs(lane) < abs(center) || lane * center < 0f)
                // The arrow still runs the way the stickers go, just beside the band.
                val path = turnArrowPath(move, n, lane)
                assertEquals(1, path.map { axisValue(move.face, it) }.distinct().size)
                val pictogram = CubePictogram.of(n)
                val onBand = turnArrowPath(move, n).map(pictogram::project)
                val beside = path.map(pictogram::project)
                val bandTravel = onBand.last() - onBand.first()
                val besideTravel = beside.last() - beside.first()
                assertTrue("${move.notation} on $n same way", bandTravel.x * besideTravel.x + bandTravel.y * besideTravel.y > 0f)
            }
        }
    }

    @Test
    fun bandsWideEnoughKeepTheArrowDownTheirMiddle() {
        for ((notation, n) in listOf("2R" to 3, "M" to 3, "Rw" to 4, "2-3Rw" to 4, "Uw'" to 5, "2-3Dw" to 6, "3Fw" to 7, "2-3Bw" to 7)) {
            val move = if (notation == "M") LayerMove(Face.L, 2, 2, 1) else LayerMove.parse(notation)
            assertFalse("$notation on $n", isNarrowBand(move, n))
            assertEquals("$notation on $n", bandCenter(move, n), arrowLane(move, n, clearance = 0.2f), 0f)
        }
    }

    @Test
    fun tabsMarkTheBandJustOutsideTheOutline() {
        for (n in listOf(4, 5, 7)) {
            val pictogram = CubePictogram.of(n)
            val outline = pictogram.outline
            for (move in singleLayers(n)) {
                val tabs = bandTabs(move, n)
                assertEquals(2, tabs.size)
                val center = bandCenter(move, n)
                val half = bandHalfWidth(move, n)
                for (tab in tabs) {
                    for (corner in tab) {
                        // Across the band's own layers...
                        val along = axisValue(move.face, corner)
                        assertTrue("${move.notation} on $n within the band", along in (center - half)..(center + half))
                        // ...and off the cube on screen.
                        assertFalse("${move.notation} on $n outside the outline", inside(pictogram.project(corner), outline))
                    }
                }
            }
        }
    }

    @Test
    fun tabsStillFitTheSquare() {
        assertTrue(CubePictogram.tabFit in 0.75f..0.99f)
        for (n in listOf(4, 7)) {
            val pictogram = CubePictogram.of(n)
            for (move in singleLayers(n)) {
                for (corner in bandTabs(move, n).flatten()) {
                    val p = pictogram.project(corner) * CubePictogram.tabFit
                    assertTrue("${move.notation} on $n", abs(p.x) <= 0.5f + 1e-4f && abs(p.y) <= 0.5f + 1e-4f)
                }
            }
        }
    }

    /** True if [point] lies inside the convex polygon [polygon]. */
    private fun inside(point: Offset, polygon: List<Offset>): Boolean {
        val signs = polygon.indices.map { i ->
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            (b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x)
        }
        return signs.all { it > 0f } || signs.all { it < 0f }
    }

    @Test
    fun onlyOuterTurnsOfSmallCubesUseTheFaceView() {
        assertFalse(usesLayerView(LayerMove.parse("R"), 3))
        assertFalse(usesLayerView(LayerMove.parse("U2"), 2))
        assertTrue(usesLayerView(LayerMove.parse("2R"), 3))
        assertTrue(usesLayerView(LayerMove.parse("R"), 4))
        assertTrue(usesLayerView(LayerMove.parse("Rw"), 7))
    }
}
