package com.andhab.cubelens.ui.solve

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.ui.theme.CubePalette
import com.andhab.cubelens.ui.theme.StickerFinish
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A point in cube space: the cube spans [-1, 1] on each axis; x right (R), y up (U), z toward the viewer (F). */
internal data class CubePoint(val x: Float, val y: Float, val z: Float)

/**
 * A small N×N cube seen from the front-top-right, the way the 3D cube first appears on the solve
 * screen: an orthographic three-quarter view showing the top, front and right faces. Used for the
 * turn pictogram of multi-layer moves and the orientation hint.
 *
 * Coordinates of [stickers] and [outline] are in "pictogram units": the whole drawing fits a square
 * of side 1 centered on the origin, y pointing down.
 */
internal class CubePictogram private constructor(val n: Int) {

    /** One visible sticker: its index in [NxNGeometry] order, its face and its outline (4 corners, clockwise on screen). */
    class Sticker(val index: Int, val face: Face, val corners: List<Offset>)

    private val scale: Float
    private val geometry = NxNGeometry.of(n)

    /** The cube's silhouette (convex hull of its projected corners). */
    val outline: List<Offset>

    /** The stickers of the top, front and right faces. */
    val stickers: List<Sticker>

    init {
        val corners = buildList {
            for (x in CORNER_SIGNS) for (y in CORNER_SIGNS) for (z in CORNER_SIGNS) add(rawProject(CubePoint(x, y, z)))
        }
        val extent = corners.maxOf { max(kotlin.math.abs(it.x), kotlin.math.abs(it.y)) }
        scale = 0.5f / extent
        outline = convexHull(corners.map { it * scale })
        val half = (1f - STICKER_GAP) / n
        stickers = VISIBLE_FACES.flatMap { face ->
            val (u, v) = tangents(face)
            (0 until n * n).map { i ->
                val index = face.ordinal * n * n + i
                val p = geometry.position[index]
                val normal = face.normal
                // Sticker center on the surface (doubled coordinates put the surface at ±n).
                val c = CubePoint((p.x + normal.x).toFloat() / n, (p.y + normal.y).toFloat() / n, (p.z + normal.z).toFloat() / n)
                val quad = listOf(
                    c + u * -half + v * half,
                    c + u * half + v * half,
                    c + u * half + v * -half,
                    c + u * -half + v * -half,
                ).map(::project)
                Sticker(index, face, quad)
            }
        }
    }

    /** Where [point] lands in pictogram units. */
    fun project(point: CubePoint): Offset = rawProject(point) * scale

    /** The outline of a whole visible [face] (U, F or R), inset by [inset] (a fraction of the half-width). */
    fun facePanel(face: Face, inset: Float): List<Offset> {
        val (u, v) = tangents(face)
        val c = CubePoint(face.normal.x.toFloat(), face.normal.y.toFloat(), face.normal.z.toFloat())
        val half = 1f - inset
        return listOf(c + u * -half + v * half, c + u * half + v * half, c + u * half + v * -half, c + u * -half + v * -half).map(::project)
    }

    /** True if [move] turns the sticker at [index]. */
    fun isMoved(index: Int, move: LayerMove): Boolean = geometry.isMovedBy(index, move)

    companion object {
        /** Turn of the view about the vertical axis, toward the right face. */
        private const val YAW_DEGREES = 36f

        /** Tilt of the view toward the top face. */
        private const val PITCH_DEGREES = 27f

        /** Fraction of a cubie's width left between neighbouring stickers. */
        private const val STICKER_GAP = 0.16f

        private val CORNER_SIGNS = floatArrayOf(-1f, 1f)
        private val VISIBLE_FACES = listOf(Face.U, Face.F, Face.R)
        private val COS_YAW = cos(Math.toRadians(YAW_DEGREES.toDouble())).toFloat()
        private val SIN_YAW = sin(Math.toRadians(YAW_DEGREES.toDouble())).toFloat()
        private val COS_PITCH = cos(Math.toRadians(PITCH_DEGREES.toDouble())).toFloat()
        private val SIN_PITCH = sin(Math.toRadians(PITCH_DEGREES.toDouble())).toFloat()

        private val cache = ConcurrentHashMap<Int, CubePictogram>()

        /** The pictogram of an [n]×[n] cube (cached). */
        fun of(n: Int): CubePictogram = cache.getOrPut(n) { CubePictogram(n) }

        /** Orthographic projection: yaw about y, then pitch about x; screen y points down. */
        private fun rawProject(p: CubePoint): Offset {
            val x1 = p.x * COS_YAW - p.z * SIN_YAW
            val z1 = p.x * SIN_YAW + p.z * COS_YAW
            val y2 = p.y * COS_PITCH - z1 * SIN_PITCH
            return Offset(x1, -y2)
        }

        /** Two in-plane axes of [face], oriented so the quad corners run clockwise on screen. */
        private fun tangents(face: Face): Pair<CubePoint, CubePoint> = when (face) {
            Face.U -> CubePoint(1f, 0f, 0f) to CubePoint(0f, 0f, -1f)
            Face.F -> CubePoint(1f, 0f, 0f) to CubePoint(0f, 1f, 0f)
            Face.R -> CubePoint(0f, 0f, -1f) to CubePoint(0f, 1f, 0f)
            else -> error("Face $face is not visible in the pictogram")
        }

        private fun convexHull(points: List<Offset>): List<Offset> {
            val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
            fun cross(o: Offset, a: Offset, b: Offset) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
            val lower = ArrayList<Offset>()
            for (p in sorted) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0f) lower.removeAt(lower.lastIndex)
                lower += p
            }
            val upper = ArrayList<Offset>()
            for (p in sorted.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0f) upper.removeAt(upper.lastIndex)
                upper += p
            }
            return lower.dropLast(1) + upper.dropLast(1)
        }
    }
}

private operator fun CubePoint.plus(o: CubePoint) = CubePoint(x + o.x, y + o.y, z + o.z)
private operator fun CubePoint.times(f: Float) = CubePoint(x * f, y * f, z * f)

/**
 * The path a turn of [move]'s layers carries stickers along, as seen on the pictogram: across the
 * two visible faces that show those layers as a band, ending where the stickers go. Three points:
 * start, the cube edge where the path bends, end.
 *
 *  - Layers counted from the right or left run as columns: up the front face and back over the top
 *    for R clockwise (and L counter-clockwise), the other way round for L clockwise.
 *  - Layers counted from the top or bottom run as rows: from the right face across the front toward
 *    the left for U clockwise (D counter-clockwise), and back for D clockwise.
 *  - Layers counted from the front or back run across the top and down the right face for F
 *    clockwise (B counter-clockwise), and back up for B clockwise.
 *
 * A half turn follows the clockwise path.
 */
internal fun turnArrowPath(move: LayerMove, n: Int): List<CubePoint> {
    val positiveSide = move.face == Face.R || move.face == Face.U || move.face == Face.F
    val sign = if (positiveSide) 1f else -1f
    // Center of the band of turning layers along the move's axis.
    val c = sign * (1f - (move.fromDepth + move.toDepth - 1).toFloat() / n)
    val reach = ARROW_REACH
    val forward = when (move.face) {
        Face.R, Face.L -> listOf(CubePoint(c, -reach, 1f), CubePoint(c, 1f, 1f), CubePoint(c, 1f, -reach))
        Face.U, Face.D -> listOf(CubePoint(1f, c, -reach), CubePoint(1f, c, 1f), CubePoint(-reach, c, 1f))
        Face.F, Face.B -> listOf(CubePoint(-reach, 1f, c), CubePoint(1f, 1f, c), CubePoint(1f, -reach, c))
    }
    // The forward paths are the clockwise turns of R, U and F; the opposite sides turn the other way.
    val clockwiseForward = positiveSide
    val counterClockwise = move.turns == 3
    return if (clockwiseForward != counterClockwise) forward else forward.asReversed()
}

/** How far along each face the arrow of [turnArrowPath] reaches, from the shared edge (2 = the whole face). */
private const val ARROW_REACH = 0.55f

/**
 * Draws [pictogram] centered on [center] with side [side]: a dark body and every visible sticker
 * filled by [stickerColor] (glossy, with the top face lit brightest, then the front, then the
 * right), or as faint glass where it returns null.
 */
internal fun DrawScope.drawCubePictogram(
    pictogram: CubePictogram,
    center: Offset,
    side: Float,
    stickerColor: (CubePictogram.Sticker) -> Color?,
) {
    val outline = pictogram.outline.map { center + it * side }
    drawPath(roundedPolygon(outline, side * 0.035f), CubePalette.Body)
    for (sticker in pictogram.stickers) {
        val corners = sticker.corners.map { center + it * side }
        val edge = min(distance(corners[0], corners[1]), distance(corners[1], corners[2]))
        val path = roundedPolygon(corners, edge * 0.2f)
        val color = stickerColor(sticker)
        if (color == null) {
            val light = when (sticker.face) {
                Face.U -> 1f
                Face.F -> 0.8f
                else -> 0.6f
            }
            drawPath(path, Color.White.copy(alpha = 0.1f + 0.08f * light))
        } else {
            // Colored stickers darken less than the glass, so lit layers stand out on every face.
            val light = when (sticker.face) {
                Face.U -> 1f
                Face.F -> 0.9f
                else -> 0.8f
            }
            val shaded = StickerFinish.of(color).shade(light)
            drawPath(
                path,
                Brush.linearGradient(
                    listOf(lerp(shaded, Color.White, 0.2f), shaded),
                    start = corners[0],
                    end = corners[2],
                ),
            )
            // A bright rim outlines each colored sticker, so even a thin band reads around an arrow.
            drawPath(path, lerp(shaded, Color.White, 0.55f).copy(alpha = 0.7f), style = Stroke(width = edge * 0.09f))
        }
    }
}

/** A closed polygon through [points] with its corners rounded by about [radius]. */
internal fun roundedPolygon(points: List<Offset>, radius: Float): Path = Path().apply {
    val count = points.size
    for (i in 0 until count) {
        val prev = points[(i - 1 + count) % count]
        val p = points[i]
        val next = points[(i + 1) % count]
        val r = min(radius, min(distance(prev, p), distance(p, next)) / 2f)
        val from = p + (prev - p) * (r / distance(prev, p))
        val to = p + (next - p) * (r / distance(p, next))
        if (i == 0) moveTo(from.x, from.y) else lineTo(from.x, from.y)
        quadraticTo(p.x, p.y, to.x, to.y)
    }
    close()
}

private fun distance(a: Offset, b: Offset): Float = hypot(a.x - b.x, a.y - b.y)
