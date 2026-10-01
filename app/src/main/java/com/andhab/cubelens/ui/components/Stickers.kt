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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * @param colors nine sticker colors, row-major as seen; null shows an empty "not set yet" slot.
 * @param highlighted indices (0..8) to flag with a glowing danger ring, e.g. stickers to re-check.
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
    Column(
        modifier = modifier
            .aspectRatio(1f)
            .background(CubePalette.Body, RoundedCornerShape(14))
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in 0 until 3) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    val color = colors[index]
                    val flagged = index in highlighted
                    val label = stringResource(R.string.sticker_description, row + 1, col + 1, colorName(color))
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .then(
                                if (onStickerClick != null) {
                                    Modifier.clickable(role = Role.Button) { onStickerClick(index) }
                                } else {
                                    Modifier
                                },
                            )
                            .semantics { contentDescription = label }
                            .drawBehind {
                                if (flagged) {
                                    drawSoftGlow(Brand.Danger, alpha = 0.5f, center = center, radiusX = size.width * 0.8f)
                                }
                                drawSticker(CubePalette.color(color), Offset.Zero, size)
                                if (flagged) drawFlagRing()
                            },
                    )
                }
            }
        }
    }
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

private fun DrawScope.drawFlagRing() {
    val stroke = 2.5.dp.toPx()
    drawRoundRect(
        color = Brand.Danger,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(size.minDimension * 0.18f),
        style = Stroke(stroke),
    )
}
