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
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.core.nxn.NxNGeometry
import com.andhab.cubelens.ui.cube.CubeGeometry.CAMERA_DISTANCE
import com.andhab.cubelens.ui.cube.CubeGeometry.HALF_EXTENT
import com.andhab.cubelens.ui.cube.CubeGeometry.faceNormal
import com.andhab.cubelens.ui.cube.CubeGeometry.faceU
import com.andhab.cubelens.ui.cube.CubeGeometry.faceV
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.StickerFinish
import com.andhab.cubelens.ui.theme.StickerPalette
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Everything one frame of [Cube3D] depends on. A single instance is reused for every frame; the
 * composable fills it in from snapshot state inside the draw phase.
 */
internal class CubeFrame {
    /** Sticker colors, 6·N² in [NxNGeometry] order. */
    var colors: List<CubeColor?> = emptyList()

    /** Cube size N; [colors] must hold 6·N² stickers or nothing is drawn. */
    var size = 3
    var yaw = 0f
    var pitch = 0f
    var move: LayerMove? = null

    /** Linear time fraction of [move]; eased by the renderer. */
    var progress = 0f

    /** Highlighted stickers, indexed by sticker; `null` when none is highlighted. */
    var highlights: BooleanArray? = null

    /** 0..1 phase of the highlight pulse. */
    var pulse = 0f

    /** Per-face dimming (0 = full color, 1 = fully dimmed), indexed by [Face.ordinal]. */
    val faceDim = FloatArray(6)
    var focusFace: Face? = null

    /** 0..1 visibility of the glowing outline around [focusFace]. */
    var focusGlow = 0f

    /** Idle float, -1..1: lifts the cube and softens its shadow. */
    var lift = 0f

    /** Display colors of the sticker labels. */
    var palette: StickerPalette = StickerPalette.Standard
}

/**
 * Proportions of the cube's details for one size N, in local face units ([CubeRenderer] maps each
 * cubie face onto a 100 × 100 square). A 3×3 uses the reference proportions. Bigger cubes have
 * smaller cubies on screen, so their strokes (bevels, seams, rings) and gaps grow a little in local
 * units to stay crisp, and their gloss is a touch softer so a field of 49 stickers reads as glossy
 * plastic rather than glitter. Smaller cubes get slightly finer details for the same reason.
 */
internal class CubeStyle private constructor(n: Int) {
    private val q = n / 3f
    private val k = sqrt(q)

    val stickerInset = 7.5f * q.pow(0.22f)
    val stickerRadius = 15.5f * q.pow(0.1f)
    val bodyRadius = 10f * k
    val bevelWidth = 2.6f * k
    val seamWidth = 1.6f * k
    val bounceDepth = 15f * k
    val emptyRimInset = 7f * k
    val emptyRimWidth = 3.2f * k
    val emptyRimRadiusDelta = 5f * k
    val emptyDash = floatArrayOf(9f * k, 7f * k)
    val highlightGlowWidth = 9f * k
    val highlightPulseWidth = 5f * k
    val highlightRingWidth = 5.5f * k

    /** Multiplier for sticker gloss. */
    val gloss = if (n <= 3) 1f else (1f - 0.045f * (n - 3)).coerceAtLeast(0.7f)

    companion object {
        private val cache = arrayOfNulls<CubeStyle>(NxNGeometry.MAX_SIZE + 1)

        /** The (cached, immutable) style of an [n]×[n] cube. A racing first call just builds an equal copy. */
        fun of(n: Int): CubeStyle = cache[n] ?: CubeStyle(n).also { cache[n] = it }
    }
}

/**
 * Draws the cube with a painter's algorithm on an [android.graphics.Canvas].
 *
 * [CubeScene] splits the cube into slab groups and orders them back to front; each visible face of
 * a group's box is drawn as one grid of cubie faces in a local rectangle (100 units per cubie) that
 * [Matrix.setPolyToPoly] maps exactly onto the face's perspective-projected quad. Rounded stickers,
 * gloss gradients, seams and bevels are therefore drawn with cached shaders in local space and
 * stay glued to the face, foreshortening included. All buffers, paints and shaders are allocated
 * once (per size or palette); drawing a frame allocates nothing.
 */
internal class CubeRenderer {

    private val scene = CubeScene()
    private var style = CubeStyle.of(3)

    private val light = FloatArray(3)
    private val half = FloatArray(3)
    private val down = FloatArray(3)
    private val point = FloatArray(3)
    private val normal = FloatArray(3)
    private val sideDir = FloatArray(3)
    private val coords = IntArray(3)
    private val src = FloatArray(8)
    private val faceSrc = floatArrayOf(0f, 0f, FACE_S, 0f, FACE_S, FACE_S, 0f, FACE_S)
    private val dst = FloatArray(8)
    private val cellProbe = FloatArray(10)
    private val faceMatrix = Matrix()
    private val glossWeight = FloatArray(4)

    /** Shaded, dimmed fill per label (index 6: unknown) for the face being drawn. */
    private val faceFill = IntArray(COLOR_SLOTS)

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
    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = SEAM_COLOR
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val dangerArgb = Brand.Danger.toArgb()

    /** Gloss: white from local corner k fading towards the middle of the sticker. */
    private val glossShaders = Array(4) { k ->
        diagonalGradient(k, intArrayOf(0x8CFFFFFF.toInt(), 0x24FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.32f, 0.62f))
    }

    /** Body plastic: a faint sheen from the lit corner of each cubie. */
    private val bodySheenShaders = Array(4) { k -> diagonalGradient(k, intArrayOf(0x1FFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.7f)) }

    // Palette-dependent: finishes and corner-shadow shaders per label (index 6: unknown).
    private var palette: StickerPalette? = null
    private val finishes = arrayOfNulls<StickerFinish>(COLOR_SLOTS)
    private var shadeShaders: Array<Array<Shader>> = emptyArray()

    // Size-dependent: the warm bounce light along each side of a face, and a cubie's outline with
    // each combination of rounded corners (index: bit k set = local corner k rounded).
    private var preparedStyle: CubeStyle? = null
    private var bounceShaders: Array<Shader> = emptyArray()
    private var cellPaths: Array<Path> = emptyArray()

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
        if (width <= 0f || height <= 0f) return
        val n = frame.size
        if (n !in NxNGeometry.MIN_SIZE..NxNGeometry.MAX_SIZE || frame.colors.size != 6 * n * n) return
        prepare(frame.palette, CubeStyle.of(n))

        // Layout: the cube's bounding sphere projects to radius r; leave room below for the glow.
        val r = min(width * 0.5f, height / 2.25f) * 0.96f
        val restY = height * 0.5f - r * 0.1f
        cx = width * 0.5f
        cy = restY - frame.lift * r * 0.045f
        val bound = CubeGeometry.BOUNDING_RADIUS
        focal = r * sqrt(CAMERA_DISTANCE * CAMERA_DISTANCE - bound * bound) / bound

        scene.update(CubeLattice.of(n), frame.yaw, frame.pitch, frame.move, frame.progress)
        val view = scene.view
        CubeGeometry.transformTransposed(view, LIGHT_X, LIGHT_Y, LIGHT_Z, light)
        CubeGeometry.transformTransposed(view, HALF_X, HALF_Y, HALF_Z, half)
        CubeGeometry.transformTransposed(view, 0f, -1f, 0f, down)

        drawStage(canvas, r, restY, frame.lift)

        for (i in 0 until scene.groupCount) {
            val g = scene.groupInDrawOrder(i)
            var visible = 0
            for (d in 0 until 6) if (scene.isFaceVisible(g, d)) visible = visible or (1 shl d)
            if (visible == 0) continue
            fillCornerJunctions(canvas, g, visible)
            for (d in 0 until 6) if ((visible and (1 shl d)) != 0) drawBoxFace(canvas, frame, g, d)
        }

        if (frame.focusGlow > 0.01f && scene.groupCount == 1) frame.focusFace?.let { drawFocusOutline(canvas, it, frame) }
    }

    /** Rebuilds the shaders that depend on the palette or the size, only when those change. */
    private fun prepare(palette: StickerPalette, style: CubeStyle) {
        this.style = style
        if (palette != this.palette) {
            this.palette = palette
            for (c in 0 until COLOR_SLOTS) finishes[c] = palette.finish(CubeColor.entries.getOrNull(c))
            shadeShaders = Array(COLOR_SLOTS) { c ->
                val shadow = finishes[c]!!.shadow.toArgb() and 0x00FFFFFF
                Array(4) { k -> diagonalGradient(k, intArrayOf(SHADE_ALPHA or shadow, shadow), floatArrayOf(0f, 0.55f)) }
            }
        }
        if (style !== preparedStyle) {
            preparedStyle = style
            val depth = style.bounceDepth
            val colors = intArrayOf(
                Brand.Coral.copy(alpha = 0.9f).toArgb(),
                Brand.Magenta.copy(alpha = 0.35f).toArgb(),
                0x00FF2E63,
            )
            val stops = floatArrayOf(0f, 0.45f, 1f)
            bounceShaders = Array(4) { side ->
                when (side) {
                    0 -> LinearGradient(0f, 0f, 0f, depth, colors, stops, Shader.TileMode.CLAMP)
                    1 -> LinearGradient(S, 0f, S - depth, 0f, colors, stops, Shader.TileMode.CLAMP)
                    2 -> LinearGradient(0f, S, 0f, S - depth, colors, stops, Shader.TileMode.CLAMP)
                    else -> LinearGradient(0f, 0f, depth, 0f, colors, stops, Shader.TileMode.CLAMP)
                }
            }
            val cell = RectF(0f, 0f, S, S)
            cellPaths = Array(16) { mask ->
                val radii = FloatArray(8) { if ((mask and (1 shl (it / 2))) != 0) style.bodyRadius else 0f }
                Path().apply { addRoundRect(cell, radii, Path.Direction.CW) }
            }
            emptyPaint.strokeWidth = style.emptyRimWidth
            emptyPaint.pathEffect = DashPathEffect(style.emptyDash, 0f)
            seamPaint.strokeWidth = 2f * style.seamWidth
        }
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
     * Where three visible faces of a group's box meet, each face's rounded corner leaves a tiny gap
     * at the shared vertex. A body-colored cap drawn underneath the faces fills it, so the vertex
     * reads as a rounded plastic corner instead of a pinhole onto the background.
     */
    private fun fillCornerJunctions(canvas: Canvas, g: Int, visible: Int) {
        val cell = scene.lattice.cell
        val view = scene.view
        for (corner in 0 until 8) {
            val sx = if ((corner and 1) != 0) 1 else -1
            val sy = if ((corner and 2) != 0) 1 else -1
            val sz = if ((corner and 4) != 0) 1 else -1
            val fx = if (sx > 0) Face.R else Face.L
            val fy = if (sy > 0) Face.U else Face.D
            val fz = if (sz > 0) Face.F else Face.B
            val mask = (1 shl fx.ordinal) or (1 shl fy.ordinal) or (1 shl fz.ordinal)
            if ((visible and mask) != mask) continue
            scene.orient(g, scene.boxPlane(g, 0, sx), scene.boxPlane(g, 1, sy), scene.boxPlane(g, 2, sz), point)
            project(point[0], point[1], point[2], 0)
            val vz = view[6] * point[0] + view[7] * point[1] + view[8] * point[2]
            val radius = focal / (CAMERA_DISTANCE - vz) * cell * (style.bodyRadius / S) * 1.15f
            bodyPaint.color = CORNER_CAP
            canvas.drawCircle(dst[0], dst[1], radius, bodyPaint)
        }
    }

    /**
     * Draws box face [d] of group [g]: a grid of cubie faces with the body plastic, seams between
     * cubies, lit bevels along the box edges and, on the outside of the cube, the stickers.
     */
    private fun drawBoxFace(canvas: Canvas, frame: CubeFrame, g: Int, d: Int) {
        val lattice = scene.lattice
        val ua = CubeGeometry.axisOfVector(faceU, d)
        val va = CubeGeometry.axisOfVector(faceV, d)
        val na = CubeGeometry.axisOfVector(faceNormal, d)
        val cellsU = scene.span(g, ua)
        val cellsV = scene.span(g, va)
        val w = cellsU * S
        val h = cellsV * S

        for (k in 0 until 4) {
            scene.faceCorner(g, d, k, point)
            project(point[0], point[1], point[2], k)
        }
        src[0] = 0f; src[1] = 0f; src[2] = w; src[3] = 0f
        src[4] = w; src[5] = h; src[6] = 0f; src[7] = h
        if (!faceMatrix.setPolyToPoly(src, 0, dst, 0, 4)) return
        computeGlossWeights(w, h)

        scene.orient(g, faceNormal[d * 3].toFloat(), faceNormal[d * 3 + 1].toFloat(), faceNormal[d * 3 + 2].toFloat(), normal)
        val lambert = normal[0] * light[0] + normal[1] * light[1] + normal[2] * light[2]
        val diffuse = ((lambert + WRAP) / (1f + WRAP)).coerceIn(0f, 1f)
        val specular = max(0f, normal[0] * half[0] + normal[1] * half[1] + normal[2] * half[2]).pow(10f)

        // The face is on the outside of the cube when its layer is the outermost one along its normal.
        val sn = CubeGeometry.signOfVector(faceNormal, d)
        val layerN = if (sn > 0) scene.upper(g, na) else scene.lower(g, na)
        val outer = if (sn > 0) layerN == lattice.n - 1 else layerN == 0

        canvas.save()
        canvas.concat(faceMatrix)

        // Body plastic, then the grooves between cubies and the lit edges of the box.
        val radius = style.bodyRadius
        bodyPaint.color = if (outer) lerpArgb(BODY_SHADOW, BODY_LIT, diffuse) else lerpArgb(BODY_INNER_SHADOW, BODY_INNER_LIT, diffuse)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, bodyPaint)
        for (i in 1 until cellsU) canvas.drawLine(i * S, 0f, i * S, h, seamPaint)
        for (j in 1 until cellsV) canvas.drawLine(0f, j * S, w, j * S, seamPaint)
        drawEdges(canvas, g, d, w, h)

        // Cubie by cubie: the body sheen, then the sticker.
        val su = CubeGeometry.signOfVector(faceU, d)
        val sv = CubeGeometry.signOfVector(faceV, d)
        coords[na] = layerN
        if (outer) prepareFills(frame.faceDim[d], diffuse)
        val sheen = 0.5f + 0.5f * diffuse
        var tx = 0f
        var ty = 0f
        for (j in 0 until cellsV) {
            coords[va] = if (sv > 0) scene.lower(g, va) + j else scene.upper(g, va) - j
            for (i in 0 until cellsU) {
                coords[ua] = if (su > 0) scene.lower(g, ua) + i else scene.upper(g, ua) - i
                val x = i * S
                val y = j * S
                canvas.translate(x - tx, y - ty)
                tx = x
                ty = y
                drawSheen(canvas, cornerMask(i, j, cellsU, cellsV), sheen)
                if (outer) {
                    val sticker = lattice.sticker(coords[0], coords[1], coords[2], d)
                    if (sticker >= 0) drawSticker(canvas, frame, sticker, specular, frame.faceDim[d])
                }
            }
        }
        canvas.restore()
    }

    /**
     * The faint sheen on one cubie's body, from its lit corner. Cubies at the corners of the box are
     * rounded there (bit k of [corners] for local corner k), like the body under them.
     */
    private fun drawSheen(canvas: Canvas, corners: Int, strength: Float) {
        val paths = cellPaths
        for (k in 0 until 4) {
            val w = glossWeight[k] * strength
            if (w < 0.02f) continue
            overlayPaint.shader = bodySheenShaders[k]
            overlayPaint.alpha = (255 * w.coerceAtMost(1f)).toInt()
            if (corners == 0) canvas.drawRect(0f, 0f, S, S, overlayPaint) else canvas.drawPath(paths[corners], overlayPaint)
        }
        overlayPaint.shader = null
    }

    /** Which local corners of cubie ([i], [j]) of a [cellsU] x [cellsV] face are corners of the box. */
    private fun cornerMask(i: Int, j: Int, cellsU: Int, cellsV: Int): Int {
        val left = i == 0
        val right = i == cellsU - 1
        val top = j == 0
        val bottom = j == cellsV - 1
        var mask = 0
        if (left && top) mask = mask or 1
        if (right && top) mask = mask or 2
        if (right && bottom) mask = mask or 4
        if (left && bottom) mask = mask or 8
        return mask
    }

    /** The fill of every label on the current face: lit by [diffuse] and dimmed by [dim]. */
    private fun prepareFills(dim: Float, diffuse: Float) {
        val lit = AMBIENT + (1f - AMBIENT) * diffuse
        for (c in 0 until COLOR_SLOTS - 1) {
            faceFill[c] = lerpArgb(finishes[c]!!.shadeArgb(lit), DIM_TARGET, dim * DIM_STRENGTH)
        }
        // Unknown stickers are matte and hollow-looking.
        faceFill[COLOR_SLOTS - 1] = lerpArgb(finishes[COLOR_SLOTS - 1]!!.shadeArgb(0.55f + 0.3f * diffuse), DIM_TARGET, dim * DIM_STRENGTH)
    }

    /**
     * Edge treatment of one box face, by side (0: -v at local y = 0, 1: +u at x = w, 2: +v at y = h,
     * 3: -u at x = 0). Every side of a box face is an edge of its group, so it catches the key light
     * like a rounded bevel, and edges facing down pick up a soft warm bounce from the glow pool under
     * the cube.
     */
    private fun drawEdges(canvas: Canvas, g: Int, d: Int, w: Float, h: Float) {
        for (side in 0 until 4) {
            val sign = if (side == 0 || side == 3) -1 else 1
            val axes = if (side == 0 || side == 2) faceV else faceU
            scene.orient(
                g,
                (axes[d * 3] * sign + faceNormal[d * 3]).toFloat(),
                (axes[d * 3 + 1] * sign + faceNormal[d * 3 + 1]).toFloat(),
                (axes[d * 3 + 2] * sign + faceNormal[d * 3 + 2]).toFloat(),
                sideDir,
            )
            val key = max(0f, (sideDir[0] * light[0] + sideDir[1] * light[1] + sideDir[2] * light[2]) * INV_SQRT2)
            edgePaint.strokeWidth = style.bevelWidth
            edgePaint.color = BEVEL_COLOR
            edgePaint.alpha = (255 * (0.05f + 0.55f * key * key)).toInt()
            drawEdge(canvas, side, style.bevelWidth * 0.5f, w, h)

            val downness = (sideDir[0] * down[0] + sideDir[1] * down[1] + sideDir[2] * down[2]) * INV_SQRT2
            val bounce = ((downness - BOUNCE_THRESHOLD) / (1f - BOUNCE_THRESHOLD)).coerceIn(0f, 1f)
            if (bounce > 0.02f) drawBounce(canvas, side, (255 * BOUNCE_STRENGTH * bounce).toInt(), w, h)
        }
    }

    /** A line along [side], inset by [inset]; it stops short of the rounded corners. */
    private fun drawEdge(canvas: Canvas, side: Int, inset: Float, w: Float, h: Float) {
        val r = style.bodyRadius
        when (side) {
            0 -> canvas.drawLine(r, inset, w - r, inset, edgePaint)
            1 -> canvas.drawLine(w - inset, r, w - inset, h - r, edgePaint)
            2 -> canvas.drawLine(w - r, h - inset, r, h - inset, edgePaint)
            else -> canvas.drawLine(inset, h - r, inset, r, edgePaint)
        }
    }

    /**
     * The warm bounce band along [side], clipped to the rounded body. The cached shaders are laid out
     * for a one-cubie square, so the far sides shift the canvas to put the band on the box's edge.
     */
    private fun drawBounce(canvas: Canvas, side: Int, alpha: Int, w: Float, h: Float) {
        val r = style.bodyRadius
        val dx = if (side == 1) w - S else 0f
        val dy = if (side == 2) h - S else 0f
        overlayPaint.shader = bounceShaders[side]
        overlayPaint.alpha = alpha
        canvas.translate(dx, dy)
        canvas.drawRoundRect(-dx, -dy, w - dx, h - dy, r, r, overlayPaint)
        canvas.translate(-dx, -dy)
        overlayPaint.shader = null
    }

    /** One sticker in the current cubie's local square (0..S). */
    private fun drawSticker(canvas: Canvas, frame: CubeFrame, sticker: Int, specular: Float, dim: Float) {
        val color = frame.colors[sticker]
        val slot = color?.ordinal ?: (COLOR_SLOTS - 1)
        val finish = finishes[slot]!!
        val l = style.stickerInset
        val h = S - l
        val radius = style.stickerRadius
        stickerPaint.color = faceFill[slot]
        canvas.drawRoundRect(l, l, h, h, radius, radius, stickerPaint)
        if (color != null) {
            drawWeighted(canvas, shadeShaders[slot], l, l, h, h, radius, finish.shadeScale, 2)
            val gloss = (0.62f + 0.38f * specular) * (1f - 0.85f * dim) * finish.glossScale * style.gloss
            drawWeighted(canvas, glossShaders, l, l, h, h, radius, gloss, 0)
        } else {
            emptyPaint.color = lerpArgb(EMPTY_RIM, DIM_TARGET, dim * DIM_STRENGTH)
            val e = l + style.emptyRimInset
            val er = radius - style.emptyRimRadiusDelta
            canvas.drawRoundRect(e, e, S - e, S - e, er, er, emptyPaint)
        }

        val highlights = frame.highlights
        if (highlights != null && sticker < highlights.size && highlights[sticker]) {
            val p = frame.pulse
            outlinePaint.color = dangerArgb
            outlinePaint.alpha = (255 * (0.22f + 0.28f * p)).toInt()
            outlinePaint.strokeWidth = style.highlightGlowWidth + style.highlightPulseWidth * p
            canvas.drawRoundRect(l, l, h, h, radius, radius, outlinePaint)
            outlinePaint.alpha = 255
            outlinePaint.strokeWidth = style.highlightRingWidth
            canvas.drawRoundRect(l + 1f, l + 1f, h - 1f, h - 1f, radius - 1f, radius - 1f, outlinePaint)
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
        val eye = scene.eye
        val e = HALF_EXTENT
        val facing = (eye[0] - e * nx) * nx + (eye[1] - e * ny) * ny + (eye[2] - e * nz) * nz
        val presence = ((facing - FOCUS_FADE_START) / (FOCUS_FADE_END - FOCUS_FADE_START)).coerceIn(0f, 1f)
        if (presence <= 0f) return
        for (k in 0 until 4) {
            val su = if (k == 1 || k == 2) e else -e
            val sv = if (k >= 2) e else -e
            project(
                e * nx + su * faceU[d * 3] + sv * faceV[d * 3],
                e * ny + su * faceU[d * 3 + 1] + sv * faceV[d * 3 + 1],
                e * nz + su * faceU[d * 3 + 2] + sv * faceV[d * 3 + 2],
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
     * Weights the four local corners of a cubie by how far towards the screen's top-left they
     * project, so the gloss always sits top-left on screen and glides smoothly between corners as
     * the cube turns. Measured on the cubie at the middle of the face (a [w] x [h] local rectangle
     * already mapped by [faceMatrix]); the other cubies of the face are lit the same way.
     */
    private fun computeGlossWeights(w: Float, h: Float) {
        val p = cellProbe
        val mx = w * 0.5f
        val my = h * 0.5f
        val o = S * 0.5f
        p[0] = mx; p[1] = my
        p[2] = mx - o; p[3] = my - o
        p[4] = mx + o; p[5] = my - o
        p[6] = mx + o; p[7] = my + o
        p[8] = mx - o; p[9] = my + o
        faceMatrix.mapPoints(p)
        var total = 0f
        for (k in 0 until 4) {
            val dx = p[2 + k * 2] - p[0]
            val dy = p[3 + k * 2] - p[1]
            val len = sqrt(dx * dx + dy * dy)
            val s = if (len > 1e-3f) -(dx + dy) / (len * SQRT2) else 0f
            val weight = if (s > 0f) s * s * s else 0f
            glossWeight[k] = weight
            total += weight
        }
        if (total > 1e-4f) for (k in 0 until 4) glossWeight[k] /= total
    }

    /** Perspective-projects a cube-space point into [dst] slot [k]. */
    private fun project(x: Float, y: Float, z: Float, k: Int) {
        val view = scene.view
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

        /** Six labels plus "unknown". */
        const val COLOR_SLOTS = 7

        const val FOCUS_RADIUS = 26f
        const val FOCUS_FADE_START = 0.5f
        const val FOCUS_FADE_END = 3f
        val FOCUS_GLOW_WIDTHS = floatArrayOf(30f, 26f, 22f, 18f, 14f, 10f, 6f, 3.5f)
        val FOCUS_GLOW_ALPHAS = floatArrayOf(0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.06f, 0.08f, 1f)
        const val BOUNCE_THRESHOLD = 0.55f
        const val BOUNCE_STRENGTH = 0.55f

        const val AMBIENT = 0.6f
        const val WRAP = 0.45f
        const val DIM_STRENGTH = 0.74f

        /** Alpha of a sticker's corner shadow at full strength (before [StickerFinish.shadeScale]). */
        const val SHADE_ALPHA = 0x38 shl 24

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
