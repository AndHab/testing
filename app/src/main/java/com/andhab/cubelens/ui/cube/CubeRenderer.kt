package com.andhab.cubelens.ui.cube

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Facelets
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette
import com.andhab.cubelens.ui.cube.CubeGeometry.CAMERA_DISTANCE
import com.andhab.cubelens.ui.cube.CubeGeometry.cubiePosition
import com.andhab.cubelens.ui.cube.CubeGeometry.faceNormal
import com.andhab.cubelens.ui.cube.CubeGeometry.faceU
import com.andhab.cubelens.ui.cube.CubeGeometry.faceV
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Everything one frame of [Cube3D] depends on. A single instance is reused for every frame; the
 * composable fills it in from snapshot state inside the draw phase.
 */
internal class CubeFrame {
    var colors: List<CubeColor?> = emptyList()
    var yaw = 0f
    var pitch = 0f
    var move: Move? = null

    /** Linear time fraction of [move]; eased by the renderer. */
    var progress = 0f
    var highlights: Set<Int> = emptySet()

    /** 0..1 phase of the highlight pulse. */
    var pulse = 0f

    /** Per-face dimming (0 = full color, 1 = fully dimmed), indexed by [Face.ordinal]. */
    val faceDim = FloatArray(6)
    var focusFace: Face? = null

    /** 0..1 visibility of the glowing outline around [focusFace]. */
    var focusGlow = 0f

    /** Idle float, -1..1: lifts the cube and softens its shadow. */
    var lift = 0f
}

/**
 * Draws the cube with a painter's algorithm on an [android.graphics.Canvas].
 *
 * Each visible cubie face is drawn in its own local square (0..[S] along the face's u and v axes)
 * that [Matrix.setPolyToPoly] maps exactly onto the face's perspective-projected quad. Rounded
 * stickers, gloss gradients and outlines are therefore drawn with cached shaders in local space and
 * stay glued to the face, foreshortening included. All buffers, paints and shaders are allocated
 * once; drawing a frame allocates nothing.
 */
internal class CubeRenderer {

    private val view = FloatArray(9)
    private val layer = FloatArray(9)
    private val eye = FloatArray(3)
    private val light = FloatArray(3)
    private val half = FloatArray(3)
    private val down = FloatArray(3)
    private val order = IntArray(CubeGeometry.CUBIE_COUNT)
    private val scratch = CubeGeometry.OrderScratch()
    private val point = FloatArray(3)
    private val normal = FloatArray(3)
    private val center = FloatArray(3)
    private val sideDir = FloatArray(3)
    private val src = floatArrayOf(0f, 0f, S, 0f, S, S, 0f, S)
    private val faceSrc = floatArrayOf(0f, 0f, FACE_S, 0f, FACE_S, FACE_S, 0f, FACE_S)
    private val dst = FloatArray(8)
    private val faceMatrix = Matrix()
    private val glossWeight = FloatArray(4)
    private val bodyPath = Path()
    private val faceRect = RectF(0f, 0f, S, S)
    private val cornerRadii = FloatArray(8)

    private var cx = 0f
    private var cy = 0f
    private var focal = 0f

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stickerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        pathEffect = DashPathEffect(floatArrayOf(9f, 7f), 0f)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val stickerArgb = IntArray(CubeColor.entries.size) { CubePalette.color(CubeColor.entries[it]).toArgb() }
    private val unknownArgb = CubePalette.Unknown.toArgb()
    private val dangerArgb = Brand.Danger.toArgb()

    /** Gloss: white from local corner k fading towards the middle of the sticker. */
    private val glossShaders = Array(4) { k ->
        diagonalGradient(k, intArrayOf(0x8CFFFFFF.toInt(), 0x24FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.32f, 0.62f))
    }

    /** Soft darkening that creeps in from the corner opposite the gloss. */
    private val shadeShaders = Array(4) { k -> diagonalGradient(k, intArrayOf(0x38000000, 0x00000000), floatArrayOf(0f, 0.55f)) }

    /** Warm bounce light: a band fading inwards from side k (see [drawEdges]). */
    private val bounceShaders = Array(4) { side ->
        val colors = intArrayOf(
            Brand.Coral.copy(alpha = 0.9f).toArgb(),
            Brand.Magenta.copy(alpha = 0.35f).toArgb(),
            0x00FF2E63,
        )
        val stops = floatArrayOf(0f, 0.45f, 1f)
        when (side) {
            0 -> LinearGradient(0f, 0f, 0f, BOUNCE_DEPTH, colors, stops, Shader.TileMode.CLAMP)
            1 -> LinearGradient(S, 0f, S - BOUNCE_DEPTH, 0f, colors, stops, Shader.TileMode.CLAMP)
            2 -> LinearGradient(0f, S, 0f, S - BOUNCE_DEPTH, colors, stops, Shader.TileMode.CLAMP)
            else -> LinearGradient(0f, 0f, BOUNCE_DEPTH, 0f, colors, stops, Shader.TileMode.CLAMP)
        }
    }

    /** Body plastic: a faint sheen from the lit corner. */
    private val bodySheenShaders = Array(4) { k -> diagonalGradient(k, intArrayOf(0x1FFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.7f)) }

    private val groundGlow = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            Brand.Coral.copy(alpha = 0.70f).toArgb(),
            Brand.Magenta.copy(alpha = 0.42f).toArgb(),
            Brand.Magenta.copy(alpha = 0.12f).toArgb(),
            0x00FF2E63,
        ),
        floatArrayOf(0f, 0.3f, 0.66f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val contactShadow = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0xE6000000.toInt(), 0x8C000000.toInt(), 0x00000000),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val backHalo = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(
            Brand.Magenta.copy(alpha = 0.20f).toArgb(),
            Brand.Magenta.copy(alpha = 0.07f).toArgb(),
            0x00FF2E63,
        ),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val focusShader = LinearGradient(
        0f, 0f, FACE_S, FACE_S,
        intArrayOf(Brand.Magenta.toArgb(), Brand.Tangerine.toArgb(), Brand.Gold.toArgb()),
        null,
        Shader.TileMode.CLAMP,
    )

    /** Draws [frame] centered in a [width] x [height] area of [canvas]. */
    fun draw(canvas: Canvas, width: Float, height: Float, frame: CubeFrame) {
        if (width <= 0f || height <= 0f || frame.colors.size != Facelets.COUNT) return

        // Layout: the cube's bounding sphere projects to radius r; leave room below for the glow.
        val r = min(width * 0.5f, height / 2.25f) * 0.96f
        val restY = height * 0.5f - r * 0.1f
        cx = width * 0.5f
        cy = restY - frame.lift * r * 0.045f
        val bound = CubeGeometry.BOUNDING_RADIUS
        focal = r * sqrt(CAMERA_DISTANCE * CAMERA_DISTANCE - bound * bound) / bound

        CubeGeometry.viewRotation(frame.yaw, frame.pitch, view)
        CubeGeometry.eyePosition(view, eye)
        CubeGeometry.transformTransposed(view, LIGHT_X, LIGHT_Y, LIGHT_Z, light)
        CubeGeometry.transformTransposed(view, HALF_X, HALF_Y, HALF_Z, half)
        CubeGeometry.transformTransposed(view, 0f, -1f, 0f, down)

        val move = frame.move
        val axis: Int
        val turningSlab: Int
        if (move != null) {
            axis = CubeGeometry.axisOf(move.face)
            turningSlab = CubeGeometry.slabOf(move.face)
            val n = move.face.normal
            CubeGeometry.rotation(n.x, n.y, n.z, turnAngleDegrees(move, frame.progress), layer)
        } else {
            axis = 1
            turningSlab = 0
            CubeGeometry.identity(layer)
        }

        drawStage(canvas, r, restY, frame.lift)

        CubeGeometry.cubieDrawOrder(eye, axis, turningSlab, layer, order, scratch)
        for (c in order) {
            val turning = turningSlab != 0 && cubiePosition[c * 3 + axis] == turningSlab
            var visible = 0
            for (d in 0 until 6) if (isFaceVisible(c, d, turning, axis, turningSlab)) visible = visible or (1 shl d)
            if (visible == 0) continue
            fillCornerJunctions(canvas, c, visible, turning)
            for (d in 0 until 6) {
                if ((visible and (1 shl d)) != 0) drawCubieFace(canvas, frame, c, d, turning, axis, turningSlab)
            }
        }

        if (frame.focusGlow > 0.01f && move == null) frame.focusFace?.let { drawFocusOutline(canvas, it, frame) }
    }

    /** Back halo plus a warm glow pool and contact shadow on the "table" under the cube. */
    private fun drawStage(canvas: Canvas, r: Float, restY: Float, lift: Float) {
        glowPaint.shader = backHalo
        glowPaint.alpha = 255
        drawEllipse(canvas, cx, cy, r * 1.3f, r * 1.3f)

        val groundY = restY + r * 0.98f
        val spread = 1f + lift * 0.05f
        glowPaint.shader = groundGlow
        glowPaint.alpha = (255 * (1f - lift * 0.12f)).toInt().coerceIn(0, 255)
        drawEllipse(canvas, cx, groundY, r * 1.1f * spread, r * 0.21f * spread)
        glowPaint.shader = contactShadow
        glowPaint.alpha = (255 * (0.95f - lift * 0.2f)).toInt().coerceIn(0, 255)
        drawEllipse(canvas, cx, groundY - r * 0.02f, r * 0.52f * spread, r * 0.07f * spread)
        glowPaint.alpha = 255
    }

    private fun drawEllipse(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, glowPaint)
        canvas.restore()
    }

    /**
     * Whether face [d] of cubie [c] is drawn: not glued to a neighbor that moves with it, and turned
     * towards the camera (nearly edge-on faces are skipped: they cover no pixels and would need a
     * degenerate mapping). Leaves the face's oriented normal in [normal].
     */
    private fun isFaceVisible(c: Int, d: Int, turning: Boolean, axis: Int, turningSlab: Int): Boolean {
        val px = cubiePosition[c * 3]
        val py = cubiePosition[c * 3 + 1]
        val pz = cubiePosition[c * 3 + 2]
        val nx = faceNormal[d * 3]
        val ny = faceNormal[d * 3 + 1]
        val nz = faceNormal[d * 3 + 2]
        if (sameGroupNeighbor(px + nx, py + ny, pz + nz, turning, axis, turningSlab)) return false
        orient(nx.toFloat(), ny.toFloat(), nz.toFloat(), turning, normal)
        orient(px + 0.5f * nx, py + 0.5f * ny, pz + 0.5f * nz, turning, center)
        val facing = (eye[0] - center[0]) * normal[0] + (eye[1] - center[1]) * normal[1] + (eye[2] - center[2]) * normal[2]
        return facing > MIN_FACING
    }

    /**
     * Where three visible faces of a cubie meet, each face's rounded corner leaves a tiny gap at the
     * shared vertex. A body-colored cap drawn underneath the faces fills it, so the vertex reads as a
     * rounded plastic corner instead of a pinhole onto the background.
     */
    private fun fillCornerJunctions(canvas: Canvas, c: Int, visible: Int, turning: Boolean) {
        val px = cubiePosition[c * 3]
        val py = cubiePosition[c * 3 + 1]
        val pz = cubiePosition[c * 3 + 2]
        for (corner in 0 until 8) {
            val sx = if ((corner and 1) != 0) 1 else -1
            val sy = if ((corner and 2) != 0) 1 else -1
            val sz = if ((corner and 4) != 0) 1 else -1
            val fx = if (sx > 0) Face.R else Face.L
            val fy = if (sy > 0) Face.U else Face.D
            val fz = if (sz > 0) Face.F else Face.B
            val mask = (1 shl fx.ordinal) or (1 shl fy.ordinal) or (1 shl fz.ordinal)
            if ((visible and mask) != mask) continue
            orient(px + 0.5f * sx, py + 0.5f * sy, pz + 0.5f * sz, turning, point)
            project(point[0], point[1], point[2], 0)
            val vz = view[6] * point[0] + view[7] * point[1] + view[8] * point[2]
            val radius = focal / (CAMERA_DISTANCE - vz) * (BODY_RADIUS / S) * 1.15f
            bodyPaint.color = CORNER_CAP
            canvas.drawCircle(dst[0], dst[1], radius, bodyPaint)
        }
    }

    private fun drawCubieFace(canvas: Canvas, frame: CubeFrame, c: Int, d: Int, turning: Boolean, axis: Int, turningSlab: Int) {
        val px = cubiePosition[c * 3]
        val py = cubiePosition[c * 3 + 1]
        val pz = cubiePosition[c * 3 + 2]
        val nx = faceNormal[d * 3]
        val ny = faceNormal[d * 3 + 1]
        val nz = faceNormal[d * 3 + 2]
        orient(nx.toFloat(), ny.toFloat(), nz.toFloat(), turning, normal)

        val ux = faceU[d * 3]
        val uy = faceU[d * 3 + 1]
        val uz = faceU[d * 3 + 2]
        val vx = faceV[d * 3]
        val vy = faceV[d * 3 + 1]
        val vz = faceV[d * 3 + 2]
        for (k in 0 until 4) {
            val su = if (k == 1 || k == 2) 0.5f else -0.5f
            val sv = if (k >= 2) 0.5f else -0.5f
            orient(
                px + 0.5f * nx + su * ux + sv * vx,
                py + 0.5f * ny + su * uy + sv * vy,
                pz + 0.5f * nz + su * uz + sv * vz,
                turning,
                point,
            )
            project(point[0], point[1], point[2], k)
        }
        if (!faceMatrix.setPolyToPoly(src, 0, dst, 0, 4)) return
        computeGlossWeights()

        val lambert = normal[0] * light[0] + normal[1] * light[1] + normal[2] * light[2]
        val diffuse = ((lambert + WRAP) / (1f + WRAP)).coerceIn(0f, 1f)
        val specular = max(0f, normal[0] * half[0] + normal[1] * half[1] + normal[2] * half[2]).pow(10f)

        val facelet = CubeGeometry.sticker[c * 6 + d]
        val exposed = exposedSides(px, py, pz, d, turning, axis, turningSlab)
        buildBodyPath(exposed)
        canvas.save()
        canvas.concat(faceMatrix)

        // Body plastic.
        bodyPaint.color = if (facelet >= 0) {
            lerpArgb(BODY_SHADOW, BODY_LIT, diffuse)
        } else {
            lerpArgb(BODY_INNER_SHADOW, BODY_INNER_LIT, diffuse)
        }
        canvas.drawPath(bodyPath, bodyPaint)
        for (k in 0 until 4) {
            val w = glossWeight[k] * (0.5f + 0.5f * diffuse)
            if (w < 0.02f) continue
            overlayPaint.shader = bodySheenShaders[k]
            overlayPaint.alpha = (255 * w).toInt()
            canvas.drawPath(bodyPath, overlayPaint)
        }
        overlayPaint.shader = null
        drawEdges(canvas, d, exposed, turning)

        if (facelet >= 0) drawSticker(canvas, frame, facelet, diffuse, specular)
        canvas.restore()
    }

    /**
     * Bitmask of the face's sides (bit `side`, see [drawEdges]) that are not glued to a neighbor moving
     * with this cubie: the outside of the cube, and both sides of a turning slab.
     */
    private fun exposedSides(px: Int, py: Int, pz: Int, d: Int, turning: Boolean, axis: Int, turningSlab: Int): Int {
        var mask = 0
        for (side in 0 until 4) {
            val sign = if (side == 0 || side == 3) -1 else 1
            val axes = if (side == 0 || side == 2) faceV else faceU
            val nx = px + axes[d * 3] * sign
            val ny = py + axes[d * 3 + 1] * sign
            val nz = pz + axes[d * 3 + 2] * sign
            if (!sameGroupNeighbor(nx, ny, nz, turning, axis, turningSlab)) mask = mask or (1 shl side)
        }
        return mask
    }

    /**
     * The face's body outline in local space. A corner is rounded only where both adjoining sides are
     * exposed, so pieces that move together butt up seamlessly (no notches showing the background),
     * while the cube, and a turning slab, keep soft corners.
     */
    private fun buildBodyPath(exposed: Int) {
        for (k in 0 until 4) {
            val r = if (cornerRounded(exposed, k)) BODY_RADIUS else 0f
            cornerRadii[k * 2] = r
            cornerRadii[k * 2 + 1] = r
        }
        bodyPath.rewind()
        bodyPath.addRoundRect(faceRect, cornerRadii, Path.Direction.CW)
    }

    /** Local corner k (0 = (0,0), 1 = (S,0), 2 = (S,S), 3 = (0,S)) joins sides k-1 and k (mod 4). */
    private fun cornerRounded(exposed: Int, k: Int): Boolean {
        val a = (k + 3) % 4
        return (exposed and (1 shl a)) != 0 && (exposed and (1 shl k)) != 0
    }

    /**
     * Edge treatment of one cubie face, by side (0: -v at local y = 0, 1: +u at x = S, 2: +v at y = S,
     * 3: -u at x = 0). Seams between pieces that move together get a hairline groove. Exposed edges
     * catch the key light like a rounded bevel, and edges facing down pick up a soft warm bounce from
     * the glow pool under the cube.
     */
    private fun drawEdges(canvas: Canvas, d: Int, exposed: Int, turning: Boolean) {
        for (side in 0 until 4) {
            if ((exposed and (1 shl side)) == 0) {
                edgePaint.color = SEAM_COLOR
                edgePaint.strokeWidth = SEAM_WIDTH
                drawEdge(canvas, side, SEAM_WIDTH * 0.5f, exposed)
                continue
            }
            val sign = if (side == 0 || side == 3) -1 else 1
            val axes = if (side == 0 || side == 2) faceV else faceU
            orient(
                (axes[d * 3] * sign + faceNormal[d * 3]).toFloat(),
                (axes[d * 3 + 1] * sign + faceNormal[d * 3 + 1]).toFloat(),
                (axes[d * 3 + 2] * sign + faceNormal[d * 3 + 2]).toFloat(),
                turning,
                sideDir,
            )
            val key = max(0f, (sideDir[0] * light[0] + sideDir[1] * light[1] + sideDir[2] * light[2]) * INV_SQRT2)
            edgePaint.strokeWidth = BEVEL_WIDTH
            edgePaint.color = BEVEL_COLOR
            edgePaint.alpha = (255 * (0.05f + 0.55f * key * key)).toInt()
            drawEdge(canvas, side, BEVEL_WIDTH * 0.5f, exposed)

            val downness = (sideDir[0] * down[0] + sideDir[1] * down[1] + sideDir[2] * down[2]) * INV_SQRT2
            val bounce = ((downness - BOUNCE_THRESHOLD) / (1f - BOUNCE_THRESHOLD)).coerceIn(0f, 1f)
            if (bounce > 0.02f) {
                overlayPaint.shader = bounceShaders[side]
                overlayPaint.alpha = (255 * BOUNCE_STRENGTH * bounce).toInt()
                canvas.drawPath(bodyPath, overlayPaint)
                overlayPaint.shader = null
            }
        }
    }

    /** A line along [side], inset by [inset]; it stops short of rounded corners. */
    private fun drawEdge(canvas: Canvas, side: Int, inset: Float, exposed: Int) {
        // The side runs from corner `side` to corner `side + 1` (mod 4) in local corner numbering.
        val startR = if (cornerRounded(exposed, side)) BODY_RADIUS else 0f
        val endR = if (cornerRounded(exposed, (side + 1) % 4)) BODY_RADIUS else 0f
        when (side) {
            0 -> canvas.drawLine(startR, inset, S - endR, inset, edgePaint)
            1 -> canvas.drawLine(S - inset, startR, S - inset, S - endR, edgePaint)
            2 -> canvas.drawLine(S - startR, S - inset, endR, S - inset, edgePaint)
            else -> canvas.drawLine(inset, S - startR, inset, endR, edgePaint)
        }
    }

    private fun drawSticker(canvas: Canvas, frame: CubeFrame, facelet: Int, diffuse: Float, specular: Float) {
        val color = frame.colors[facelet]
        val dim = frame.faceDim[facelet / 9]
        val l = STICKER_INSET
        val h = S - STICKER_INSET
        val light = AMBIENT + (1f - AMBIENT) * diffuse
        if (color != null) {
            val shaded = shadeArgb(stickerArgb[color.ordinal], light)
            stickerPaint.color = lerpArgb(shaded, DIM_TARGET, dim * DIM_STRENGTH)
            canvas.drawRoundRect(l, l, h, h, STICKER_RADIUS, STICKER_RADIUS, stickerPaint)
            drawWeighted(canvas, shadeShaders, l, l, h, h, STICKER_RADIUS, 1f, 2)
            val gloss = (0.62f + 0.38f * specular) * (1f - 0.85f * dim)
            drawWeighted(canvas, glossShaders, l, l, h, h, STICKER_RADIUS, gloss, 0)
        } else {
            // Unknown sticker: matte and hollow-looking, with a dashed rim.
            val matte = shadeArgb(unknownArgb, 0.55f + 0.3f * diffuse)
            stickerPaint.color = lerpArgb(matte, DIM_TARGET, dim * DIM_STRENGTH)
            canvas.drawRoundRect(l, l, h, h, STICKER_RADIUS, STICKER_RADIUS, stickerPaint)
            emptyPaint.color = lerpArgb(EMPTY_RIM, DIM_TARGET, dim * DIM_STRENGTH)
            val e = l + 7f
            canvas.drawRoundRect(e, e, S - e, S - e, STICKER_RADIUS - 5f, STICKER_RADIUS - 5f, emptyPaint)
        }

        if (facelet in frame.highlights) {
            val p = frame.pulse
            outlinePaint.color = dangerArgb
            outlinePaint.alpha = (255 * (0.22f + 0.28f * p)).toInt()
            outlinePaint.strokeWidth = 9f + 5f * p
            canvas.drawRoundRect(l, l, h, h, STICKER_RADIUS, STICKER_RADIUS, outlinePaint)
            outlinePaint.alpha = 255
            outlinePaint.strokeWidth = 5.5f
            canvas.drawRoundRect(l + 1f, l + 1f, h - 1f, h - 1f, STICKER_RADIUS - 1f, STICKER_RADIUS - 1f, outlinePaint)
        }
    }

    /**
     * A glowing sunset frame around the whole [face]. Drawn last and only at rest, when a front-facing
     * face of the convex cube cannot be covered by anything; it fades in as the face turns towards the
     * camera so orbiting never makes it pop.
     */
    private fun drawFocusOutline(canvas: Canvas, face: Face, frame: CubeFrame) {
        val d = face.ordinal
        val nx = faceNormal[d * 3]
        val ny = faceNormal[d * 3 + 1]
        val nz = faceNormal[d * 3 + 2]
        val facing = (eye[0] - 1.5f * nx) * nx + (eye[1] - 1.5f * ny) * ny + (eye[2] - 1.5f * nz) * nz
        val presence = ((facing - FOCUS_FADE_START) / (FOCUS_FADE_END - FOCUS_FADE_START)).coerceIn(0f, 1f)
        if (presence <= 0f) return
        for (k in 0 until 4) {
            val su = if (k == 1 || k == 2) 1.5f else -1.5f
            val sv = if (k >= 2) 1.5f else -1.5f
            project(
                1.5f * nx + su * faceU[d * 3] + sv * faceV[d * 3],
                1.5f * ny + su * faceU[d * 3 + 1] + sv * faceV[d * 3 + 1],
                1.5f * nz + su * faceU[d * 3 + 2] + sv * faceV[d * 3 + 2],
                k,
            )
        }
        if (!faceMatrix.setPolyToPoly(faceSrc, 0, dst, 0, 4)) return
        canvas.save()
        canvas.concat(faceMatrix)
        val a = frame.focusGlow * presence
        val o = -4f
        outlinePaint.shader = focusShader
        for (i in FOCUS_GLOW_WIDTHS.indices) {
            outlinePaint.strokeWidth = FOCUS_GLOW_WIDTHS[i]
            outlinePaint.alpha = (255 * FOCUS_GLOW_ALPHAS[i] * a).toInt()
            canvas.drawRoundRect(o, o, FACE_S - o, FACE_S - o, FOCUS_RADIUS, FOCUS_RADIUS, outlinePaint)
        }
        outlinePaint.shader = null
        outlinePaint.alpha = 255
        canvas.restore()
    }

    /** Draws a rounded rect once per local corner with that corner's cached shader, weighted by [glossWeight]. */
    private fun drawWeighted(
        canvas: Canvas,
        shaders: Array<Shader>,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
        radius: Float,
        strength: Float,
        cornerShift: Int,
    ) {
        for (k in 0 until 4) {
            val w = glossWeight[k] * strength
            if (w < 0.02f) continue
            overlayPaint.shader = shaders[(k + cornerShift) % 4]
            overlayPaint.alpha = (255 * w.coerceAtMost(1f)).toInt()
            canvas.drawRoundRect(l, t, r, b, radius, radius, overlayPaint)
        }
        overlayPaint.shader = null
    }

    /**
     * Weights the four local corners by how far towards the screen's top-left they project, so the
     * gloss always sits top-left on screen and glides smoothly between corners as the cube turns.
     */
    private fun computeGlossWeights() {
        val mx = (dst[0] + dst[2] + dst[4] + dst[6]) * 0.25f
        val my = (dst[1] + dst[3] + dst[5] + dst[7]) * 0.25f
        var total = 0f
        for (k in 0 until 4) {
            val dx = dst[k * 2] - mx
            val dy = dst[k * 2 + 1] - my
            val len = sqrt(dx * dx + dy * dy)
            val s = if (len > 1e-3f) -(dx + dy) / (len * SQRT2) else 0f
            val w = if (s > 0f) s * s * s else 0f
            glossWeight[k] = w
            total += w
        }
        if (total > 1e-4f) for (k in 0 until 4) glossWeight[k] /= total
    }

    private fun sameGroupNeighbor(x: Int, y: Int, z: Int, turning: Boolean, axis: Int, turningSlab: Int): Boolean {
        if (!CubeGeometry.inGrid(x, y, z)) return false
        if (turningSlab == 0) return true
        val coord = when (axis) {
            0 -> x
            1 -> y
            else -> z
        }
        return (coord == turningSlab) == turning
    }

    /** Applies the layer rotation to (x, y, z) when [turning], writing into [out]. */
    private fun orient(x: Float, y: Float, z: Float, turning: Boolean, out: FloatArray) {
        if (turning) {
            CubeGeometry.transform(layer, x, y, z, out)
        } else {
            out[0] = x; out[1] = y; out[2] = z
        }
    }

    /** Perspective-projects a cube-space point into [dst] slot [k]. */
    private fun project(x: Float, y: Float, z: Float, k: Int) {
        val vx = view[0] * x + view[1] * y + view[2] * z
        val vy = view[3] * x + view[4] * y + view[5] * z
        val vz = view[6] * x + view[7] * y + view[8] * z
        val s = focal / (CAMERA_DISTANCE - vz)
        dst[k * 2] = cx + vx * s
        dst[k * 2 + 1] = cy - vy * s
    }

    private fun diagonalGradient(corner: Int, colors: IntArray, stops: FloatArray): Shader {
        val x0 = if (corner == 1 || corner == 2) S else 0f
        val y0 = if (corner >= 2) S else 0f
        return LinearGradient(x0, y0, S - x0, S - y0, colors, stops, Shader.TileMode.CLAMP)
    }

    private companion object {
        /** Local units per cubie face edge. */
        const val S = 100f
        const val FACE_S = 300f
        const val BODY_RADIUS = 10f
        const val STICKER_INSET = 7.5f
        const val STICKER_RADIUS = 15.5f
        const val FOCUS_RADIUS = 26f
        const val FOCUS_FADE_START = 0.5f
        const val FOCUS_FADE_END = 3f
        val FOCUS_GLOW_WIDTHS = floatArrayOf(30f, 26f, 22f, 18f, 14f, 10f, 6f, 3.5f)
        val FOCUS_GLOW_ALPHAS = floatArrayOf(0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.06f, 0.08f, 1f)
        const val BEVEL_WIDTH = 2.6f
        const val SEAM_WIDTH = 1.6f
        const val BOUNCE_DEPTH = 15f
        const val BOUNCE_THRESHOLD = 0.55f
        const val BOUNCE_STRENGTH = 0.55f

        const val MIN_FACING = 0.02f
        const val AMBIENT = 0.6f
        const val WRAP = 0.45f
        const val DIM_STRENGTH = 0.74f

        const val SQRT2 = 1.4142135f
        const val INV_SQRT2 = 0.70710677f

        // Key light from the upper left, slightly in front (camera space), and the half vector to the eye.
        val LIGHT_X: Float
        val LIGHT_Y: Float
        val LIGHT_Z: Float
        val HALF_X: Float
        val HALF_Y: Float
        val HALF_Z: Float

        init {
            val lx = -0.42f
            val ly = 0.72f
            val lz = 0.55f
            val ll = sqrt(lx * lx + ly * ly + lz * lz)
            LIGHT_X = lx / ll; LIGHT_Y = ly / ll; LIGHT_Z = lz / ll
            val hx = LIGHT_X
            val hy = LIGHT_Y
            val hz = LIGHT_Z + 1f
            val hl = sqrt(hx * hx + hy * hy + hz * hz)
            HALF_X = hx / hl; HALF_Y = hy / hl; HALF_Z = hz / hl
        }

        const val BODY_SHADOW = 0xFF0B0B10.toInt()
        const val BODY_LIT = 0xFF262630.toInt()
        const val BODY_INNER_SHADOW = 0xFF050507.toInt()
        const val CORNER_CAP = 0xFF16161D.toInt()
        const val BODY_INNER_LIT = 0xFF15151C.toInt()
        const val DIM_TARGET = 0xFF16161E.toInt()
        const val EMPTY_RIM = 0xB39A95B5.toInt()
        const val BEVEL_COLOR = 0xFFFFFFFF.toInt()
        const val SEAM_COLOR = 0x8C000000.toInt()

        fun shadeArgb(argb: Int, k: Float): Int {
            val r = (((argb shr 16) and 0xFF) * k).toInt().coerceIn(0, 255)
            val g = (((argb shr 8) and 0xFF) * k).toInt().coerceIn(0, 255)
            val b = ((argb and 0xFF) * k).toInt().coerceIn(0, 255)
            return (argb and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }

        fun lerpArgb(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return (lerpChannel(a, b, 24, u) shl 24) or (lerpChannel(a, b, 16, u) shl 16) or
                (lerpChannel(a, b, 8, u) shl 8) or lerpChannel(a, b, 0, u)
        }

        private fun lerpChannel(a: Int, b: Int, shift: Int, t: Float): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t).toInt().coerceIn(0, 255)
        }
    }
}
