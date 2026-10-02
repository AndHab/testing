package com.andhab.cubelens.ui.review

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FormatPaint
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.components.colorName
import com.andhab.cubelens.ui.components.drawSticker
import com.andhab.cubelens.ui.components.sunsetGlow
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion
import com.andhab.cubelens.ui.theme.CubePalette
import com.andhab.cubelens.ui.theme.DisplayFont
import com.andhab.cubelens.ui.theme.LocalStickerPalette

/**
 * The six sticker colors as big glossy swatches in the cube's own colors ([LocalStickerPalette]),
 * each with a live counter underneath ("12/16") that turns mint with a check at exactly
 * [perColor] and red above it. The [brush] color is lifted inside a sunset ring.
 *
 * @param counts how many stickers have each color.
 * @param perColor how many stickers of each color a finished cube has (N²: 9 for a 3×3).
 * @param brush the color currently picked up for painting, if any.
 * @param enabled false while solving: swatches ignore taps.
 */
@Composable
internal fun ColorPalette(
    counts: Map<CubeColor, Int>,
    perColor: Int,
    brush: CubeColor?,
    onColorTap: (CubeColor) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (color in CubeColor.entries) {
            Swatch(
                color = color,
                count = counts[color] ?: 0,
                perColor = perColor,
                active = brush == color,
                enabled = enabled,
                onClick = { onColorTap(color) },
            )
        }
    }
}

/** Side of a swatch. */
private val SwatchSize = 48.dp

@Composable
private fun Swatch(
    color: CubeColor,
    count: Int,
    perColor: Int,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.9f
            active -> 1.06f
            else -> 1f
        },
        animationSpec = CubeLensMotion.press(),
        label = "swatchScale",
    )
    val ring by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = CubeLensMotion.select(),
        label = "swatchRing",
    )
    val haptics = LocalHapticFeedback.current
    val name = colorName(color)
    val countDescription = stringResource(R.string.review_count_description, name, count, perColor)
    val painting = stringResource(R.string.review_swatch_painting)
    val fill = LocalStickerPalette.current.color(color)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = countDescription
            role = Role.Button
            selected = active
            if (active) stateDescription = painting
            if (enabled) {
                onClick {
                    onClick()
                    true
                }
            }
        },
    ) {
        Box(
            modifier = Modifier
                .size(SwatchSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(if (ring > 0.01f) Modifier.sunsetGlow(alpha = 0.5f * ring, spread = 10.dp, offsetY = 4.dp) else Modifier)
                .drawBehind {
                    val ringWidth = 2.5.dp.toPx()
                    // The body shrinks inside the ring as it appears, like a picked-up swatch.
                    val inset = (ringWidth + 3.dp.toPx()) * ring
                    if (ring > 0.01f) {
                        drawRoundRect(
                            brush = Brand.SunsetBrush,
                            topLeft = Offset(ringWidth / 2f, ringWidth / 2f),
                            size = Size(size.width - ringWidth, size.height - ringWidth),
                            cornerRadius = CornerRadius(size.minDimension * 0.26f),
                            style = Stroke(ringWidth),
                            alpha = ring,
                        )
                    }
                    drawRoundRect(
                        color = CubePalette.Body,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius((size.minDimension - 2 * inset) * 0.24f),
                    )
                    val pad = 3.dp.toPx()
                    drawSticker(
                        color = fill,
                        topLeft = Offset(inset + pad, inset + pad),
                        size = Size(size.width - 2 * (inset + pad), size.height - 2 * (inset + pad)),
                        cornerFraction = 0.22f,
                    )
                }
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onClick()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Icon(
                    imageVector = Icons.Rounded.FormatPaint,
                    contentDescription = null,
                    // Dark on light stickers (white, yellow, every pastel), white on the rest.
                    tint = if (fill.luminance() > 0.45f) Brand.OnAccent else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        CountChip(count, perColor)
    }
}

/**
 * "7/9" in a small capsule: neutral below [perColor], mint with a check at exactly [perColor], red
 * above. A complete count shows short, as "✓ 9" (or "✓ 49"), on every size: six chips still fit
 * side by side on a small phone, and the check says "all there".
 */
@Composable
private fun CountChip(count: Int, perColor: Int) {
    val complete = count == perColor
    val tone by animateColorAsState(
        targetValue = when {
            complete -> Brand.Mint
            count > perColor -> Brand.Danger
            else -> Brand.TextTertiary
        },
        animationSpec = CubeLensMotion.select(),
        label = "countTone",
    )
    Row(
        modifier = Modifier
            .widthIn(min = 44.dp)
            .height(22.dp)
            .background(tone.copy(alpha = 0.13f), CircleShape)
            .border(1.dp, tone.copy(alpha = 0.28f), CircleShape)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
    ) {
        if (complete) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = tone, modifier = Modifier.size(12.dp))
        }
        Text(
            text = if (complete) "$perColor" else stringResource(R.string.review_count, count, perColor),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = DisplayFont, fontSize = 12.sp),
            color = tone,
            maxLines = 1,
        )
    }
}
