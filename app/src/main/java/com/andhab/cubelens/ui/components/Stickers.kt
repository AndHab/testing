package com.andhab.cubelens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette

/**
 * Draws one cube sticker as glossy plastic: a rounded square (≈18% corners) whose color brightens
 * toward the top-left, with a soft specular sheen across its upper half.
 *
 * @param color the sticker's display color, normally from [CubePalette.color].
 */
fun DrawScope.drawSticker(
    color: Color,
    topLeft: Offset,
    size: Size,
    cornerFraction: Float = 0.18f,
) {
    val radius = CornerRadius(size.minDimension * cornerFraction)
    drawRoundRect(
        brush = Brush.linearGradient(
            listOf(lerp(color, Color.White, 0.18f), color, lerp(color, Color.Black, 0.12f)),
            start = topLeft,
            end = topLeft + Offset(size.width, size.height),
        ),
        topLeft = topLeft,
        size = size,
        cornerRadius = radius,
    )
    val inset = size.minDimension * 0.08f
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0f)),
            startY = topLeft.y + inset,
            endY = topLeft.y + size.height * 0.55f,
        ),
        topLeft = topLeft + Offset(inset, inset),
        size = Size(size.width - 2 * inset, size.height * 0.45f),
        cornerRadius = CornerRadius(radius.x * 0.7f),
    )
}

/**
 * One face of the cube as a 3x3 grid of glossy stickers on a black body, e.g. the live scan
 * readout or a face of the review net.
 *
 * Flagged stickers never rely on color alone: the sticker shrinks inside a dark gap and a danger
 * ring, a "!" badge sits on its corner, a soft red glow spills around it, and screen readers hear
 * "Needs checking" after its position and color.
 *
 * @param colors nine sticker colors, row-major as seen; null shows an empty "not set yet" slot.
 * @param highlighted indices (0..8) to flag, e.g. stickers the validator wants re-checked.
 * @param onStickerClick makes stickers tappable (each announces its position and color); give the
 *   grid at least 144dp so every sticker is a 48dp target.
 */
@Composable
fun StickerGrid(
    colors: List<CubeColor?>,
    modifier: Modifier = Modifier,
    highlighted: Set<Int> = emptySet(),
    onStickerClick: ((Int) -> Unit)? = null,
) {
    require(colors.size == 9) { "A face has 9 stickers, got ${colors.size}" }
    val flaggedState = stringResource(R.string.sticker_flagged)
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .background(CubePalette.Body, StickerGridShape)
            // The whole face is painted in one pass so a flag's glow and badge can spill into the
            // gaps without neighbouring cells covering part of them. The cells below are only
            // touch targets and semantics nodes.
            .drawWithContent {
                val cells = StickerGridCells(size.width, GridPadding.toPx(), GridGap.toPx())
                for (index in highlighted) {
                    if (index !in 0 until 9) continue
                    val cell = cells.rect(index)
                    drawSoftGlow(Brand.Danger, alpha = 0.6f, center = cell.center, radiusX = cell.width * 0.9f)
                }
                for (index in 0 until 9) {
                    val color = CubePalette.color(colors[index])
                    val cell = cells.rect(index)
                    if (index in highlighted) drawFlaggedSticker(color, cell) else drawSticker(color, cell.topLeft, cell.size)
                }
                drawContent()
                for (index in highlighted) {
                    if (index in 0 until 9) drawFlagBadge(cells.rect(index))
                }
            }
            .padding(GridPadding),
        verticalArrangement = Arrangement.spacedBy(GridGap),
    ) {
        for (row in 0 until 3) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(GridGap)) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    val flagged = index in highlighted
                    val label = stringResource(R.string.sticker_description, row + 1, col + 1, colorName(colors[index]))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .then(
                                if (onStickerClick != null) {
                                    Modifier
                                        .clip(StickerShape)
                                        .clickable(role = Role.Button) { onStickerClick(index) }
                                } else {
                                    Modifier
                                },
                            )
                            .semantics {
                                contentDescription = label
                                if (flagged) stateDescription = flaggedState
                            },
                    )
                }
            }
        }
    }
}

private val StickerGridShape = RoundedCornerShape(14)
private val StickerShape = RoundedCornerShape(18)
private val GridPadding = 5.dp
private val GridGap = 4.dp

/** Cell geometry of a square [StickerGrid] of side [side], matching its padding and gaps. */
private class StickerGridCells(side: Float, private val padding: Float, private val gap: Float) {
    private val cell = (side - 2f * padding - 2f * gap) / 3f

    fun rect(index: Int): Rect {
        val left = padding + (index % 3) * (cell + gap)
        val top = padding + (index / 3) * (cell + gap)
        return Rect(left, top, left + cell, top + cell)
    }
}

/** Width of a flagged sticker's danger ring. */
private val FlagRingWidth = 2.5.dp

/** Dark gap between the ring and the shrunken sticker, so the ring reads on any sticker color. */
private val FlagRingGap = 2.dp

/**
 * A flagged sticker: shrunk inside an ink gap and a [Brand.Danger] ring. Both edges of the ring sit
 * on near-black, so it reads at ~6:1 whatever the sticker color, red and orange included.
 */
private fun DrawScope.drawFlaggedSticker(color: Color, cell: Rect) {
    val ring = FlagRingWidth.toPx()
    val inset = ring + FlagRingGap.toPx()
    val sticker = cell.deflate(inset)
    val stickerRadius = sticker.minDimension * 0.18f
    // Ink under the whole cell keeps the glow out of the gap.
    drawRoundRect(
        color = Brand.Ink,
        topLeft = cell.topLeft,
        size = cell.size,
        cornerRadius = CornerRadius(stickerRadius + inset),
    )
    drawRoundRect(
        color = Brand.Danger,
        topLeft = cell.topLeft + Offset(ring / 2f, ring / 2f),
        size = Size(cell.width - ring, cell.height - ring),
        cornerRadius = CornerRadius(stickerRadius + inset - ring / 2f),
        style = Stroke(ring),
    )
    drawSticker(color, sticker.topLeft, sticker.size)
}

/** A round "!" badge on the top-right corner of a flagged cell: the non-color cue. */
private fun DrawScope.drawFlagBadge(cell: Rect) {
    val diameter = (cell.width * 0.42f).coerceIn(12.dp.toPx(), 18.dp.toPx())
    val radius = diameter / 2f
    val center = Offset(cell.right - radius * 0.45f, cell.top + radius * 0.45f)
    drawCircle(CubePalette.Body, radius = radius + 1.5.dp.toPx(), center = center)
    drawCircle(Brand.Danger, radius = radius, center = center)
    val stroke = diameter * 0.15f
    drawLine(
        color = Brand.OnAccent,
        start = center + Offset(0f, -diameter * 0.24f),
        end = center + Offset(0f, diameter * 0.05f),
        strokeWidth = stroke,
        cap = StrokeCap.Round,
    )
    drawCircle(Brand.OnAccent, radius = stroke * 0.58f, center = center + Offset(0f, diameter * 0.24f))
}

/** Localized name of a sticker color, or "Not set" for an empty slot. */
@Composable
fun colorName(color: CubeColor?): String = stringResource(
    when (color) {
        null -> R.string.color_not_set
        CubeColor.WHITE -> R.string.color_white
        CubeColor.YELLOW -> R.string.color_yellow
        CubeColor.GREEN -> R.string.color_green
        CubeColor.BLUE -> R.string.color_blue
        CubeColor.RED -> R.string.color_red
        CubeColor.ORANGE -> R.string.color_orange
    },
)
