package com.andhab.cubelens.ui.cube

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.components.colorName
import com.andhab.cubelens.ui.theme.Brand
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One face of a cube as a big N×N grid of glossy stickers, for editing a face of a big cube whose
 * net stickers are too small to tap comfortably. N is inferred from the N² colors.
 *
 * The grid is a square on a dark glass plate that fills the available width (or height, if
 * tighter). Each sticker's touch target is its whole cell, gap included, so targets are at least
 * 40dp wherever the width allows it (a 7×7 on a phone gets about 48dp). Stickers are drawn in
 * [com.andhab.cubelens.ui.theme.LocalStickerPalette]; unknown (`null`) stickers are hollow with a dashed rim, the selected one
 * lifts inside a white ring, and flagged ones never rely on color alone: a pulsing [Brand.Danger]
 * glow, a ring separated from the sticker by a dark gap, and a "!" badge.
 *
 * @param colors the face's N² stickers, row-major as in [com.andhab.cubelens.core.nxn.NxNGeometry]
 *   (as the camera sees the face).
 * @param highlightStickers face-local indices (0 until N²) to flag.
 * @param selectedSticker face-local index of the sticker being edited, if any.
 * @param onStickerClick called with the face-local index of a tapped sticker; `null` makes the
 *   grid read-only.
 */
@Composable
fun FaceEditor(
    colors: List<CubeColor?>,
    modifier: Modifier = Modifier,
    highlightStickers: Set<Int> = emptySet(),
    selectedSticker: Int? = null,
    onStickerClick: ((Int) -> Unit)? = null,
) {
    val n = CubeSizes.ofFace(colors.size)
    val pulse = if (highlightStickers.isNotEmpty()) rememberHighlightPulse() else null
    val flagged = remember(highlightStickers, n) {
        highlightStickers.filter { it in 0 until n * n }.sorted().toIntArray()
    }
    // Written by the measure pass, read by the draw pass (which always follows it).
    val measured = remember { MeasuredEditor() }

    Layout(
        content = {
            for (i in 0 until n * n) {
                val color = colors[i]
                StickerCell(
                    index = i,
                    color = color,
                    description = stringResource(R.string.sticker_description, i / n + 1, i % n + 1, colorName(color)),
                    flagged = i in highlightStickers,
                    selected = i == selectedSticker,
                    onClick = onStickerClick,
                    insetFraction = EditorMetrics.CELL_INSET_FRACTION,
                    roomy = true,
                )
            }
        },
        modifier = modifier.drawWithCache {
            val metrics = measured.metrics ?: return@drawWithCache onDrawBehind {}
            val plate = EditorPlate(metrics, this)
            val glow = if (flagged.isEmpty()) {
                null
            } else {
                GlowSprite.create(
                    shape = Size(metrics.sticker, metrics.sticker),
                    cornerRadius = metrics.sticker * STICKER_CORNER,
                    spread = min(FLAG_GLOW_SPREAD.dp.toPx(), metrics.sticker * 0.5f),
                    colors = listOf(Brand.Danger),
                )
            }
            onDrawBehind {
                with(plate) { drawPlate() }
                if (glow != null && pulse != null) {
                    val p = pulse.value
                    for (i in flagged) {
                        drawGlow(
                            sprite = glow,
                            shapeTopLeft = Offset(metrics.stickerX(i), metrics.stickerY(i)),
                            alpha = 0.55f + 0.45f * p,
                            spreadFraction = 0.72f + 0.28f * p,
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val metrics = EditorMetrics.fit(constraints, n, this)
        measured.metrics = metrics
        val cell = metrics.cell.roundToInt().coerceAtLeast(1)
        val placeables = measurables.map { it.measure(Constraints.fixed(cell, cell)) }
        val side = metrics.side.roundToInt()
        layout(side, side) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place(metrics.cellX(i).roundToInt(), metrics.cellY(i).roundToInt())
            }
        }
    }
}

/**
 * Geometry of a [FaceEditor] in pixels: a square plate of [side] with [padding], N stickers per row
 * separated by gaps, and touch cells one sticker pitch wide centered on each sticker.
 */
internal class EditorMetrics private constructor(val side: Float, val n: Int) {
    val padding = side * PADDING_FRACTION
    val sticker = (side - 2 * padding) / (n + (n - 1) * GAP)
    val gap = sticker * GAP

    /** Side of a sticker's touch cell: the sticker plus a gap. */
    val cell = sticker + gap

    fun stickerX(i: Int): Float = padding + (i % n) * cell
    fun stickerY(i: Int): Float = padding + (i / n) * cell
    fun cellX(i: Int): Float = stickerX(i) - gap / 2
    fun cellY(i: Int): Float = stickerY(i) - gap / 2

    companion object {
        /** Gap between stickers, in stickers. */
        private const val GAP = 0.12f

        /** Plate padding, as a fraction of the plate. */
        private const val PADDING_FRACTION = 0.04f

        /** A sticker's inset inside its touch cell, as a fraction of the cell. */
        val CELL_INSET_FRACTION = GAP / 2 / (1 + GAP)

        /** Preferred touch cell when the width is unbounded, in dp. */
        private const val PREFERRED_CELL_DP = 52

        /** The largest square editor that fits [constraints]. */
        fun fit(constraints: Constraints, n: Int, density: Density): EditorMetrics {
            val preferred = with(density) { (n * PREFERRED_CELL_DP).dp.toPx() }
            val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else preferred
            val maxH = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.MAX_VALUE
            return EditorMetrics(min(maxW, maxH).coerceAtLeast(1f), n)
        }
    }
}

/** The dark glass plate behind a [FaceEditor], with its brushes built once per size. */
private class EditorPlate(metrics: EditorMetrics, density: Density) {
    private val plateSize = Size(metrics.side, metrics.side)
    private val radius = CornerRadius(metrics.padding + metrics.sticker * STICKER_CORNER * 1.4f)
    private val fill = Brush.verticalGradient(PLATE_COLORS, startY = 0f, endY = metrics.side)
    private val rimWidth = with(density) { 1.dp.toPx() }
    private val rim = Stroke(rimWidth)
    private val rimBrush = Brush.verticalGradient(RIM_COLORS, startY = 0f, endY = metrics.side)

    fun DrawScope.drawPlate() {
        drawRoundRect(fill, size = plateSize, cornerRadius = radius)
        drawRoundRect(
            brush = rimBrush,
            topLeft = Offset(rimWidth / 2, rimWidth / 2),
            size = Size(plateSize.width - rimWidth, plateSize.height - rimWidth),
            cornerRadius = radius,
            style = rim,
        )
    }
}

/** Holder for the metrics of the last measure pass of a [FaceEditor]. */
private class MeasuredEditor {
    var metrics: EditorMetrics? = null
}

/** Reach of a flagged sticker's glow, in dp (less for small stickers). */
private const val FLAG_GLOW_SPREAD = 12f
