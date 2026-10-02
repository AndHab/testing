package com.andhab.cubelens.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.R
import com.andhab.cubelens.ui.SupportedCubeSizes
import com.andhab.cubelens.ui.components.glassSurface
import com.andhab.cubelens.ui.components.sunsetGlow
import com.andhab.cubelens.ui.components.topSheen
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion
import com.andhab.cubelens.ui.theme.DisplayFont

/**
 * The cube-size switch on Home: a dark-glass track with one segment per size ("2×2" … "7×7"), each
 * with a tiny N×N face above its label. The selected size sits on a glowing sunset capsule that
 * springs from segment to segment; a pressed segment dips slightly. Screen readers hear a group of
 * radio buttons ("4 by 4 cube, selected").
 *
 * @param selected the size shown as picked (one of [sizes]).
 * @param onSelect called with a newly picked size.
 */
@Composable
internal fun SizePicker(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    sizes: IntRange = SupportedCubeSizes,
) {
    val count = sizes.count()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(PickerHeight)
            .glassSurface(CircleShape)
            .padding(TrackPadding),
    ) {
        val segment = maxWidth / count
        val indicatorOffset by animateDpAsState(
            targetValue = segment * (selected - sizes.first).coerceIn(0, count - 1),
            animationSpec = CubeLensMotion.select(),
            label = "sizeIndicator",
        )
        // The sunset capsule under the selected segment.
        Box(
            Modifier
                .offset(x = indicatorOffset)
                .width(segment)
                .fillMaxHeight()
                .sunsetGlow(alpha = 0.42f, spread = 10.dp, offsetY = 4.dp)
                .clip(CircleShape)
                .background(Brand.SunsetBrush)
                .topSheen(alpha = 0.28f),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight().selectableGroup()) {
            for (size in sizes) {
                SizeSegment(
                    size = size,
                    selected = size == selected,
                    onClick = { if (size != selected) onSelect(size) },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** Height of the whole picker. */
private val PickerHeight = 60.dp

/** Space between the track's rim and the segments. */
private val TrackPadding = 5.dp

private val SegmentLabelStyle = TextStyle(
    fontFamily = DisplayFont,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    lineHeight = 16.sp,
    letterSpacing = (-0.01).em,
)

/** One size: its mini face over its label, dark on the capsule when [selected], muted otherwise. */
@Composable
private fun SizeSegment(size: Int, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = CubeLensMotion.press(),
        label = "segmentScale",
    )
    val tone by animateColorAsState(
        targetValue = if (selected) Brand.OnAccent else Brand.TextSecondary,
        animationSpec = CubeLensMotion.select(),
        label = "segmentTone",
    )
    val haptics = LocalHapticFeedback.current
    val description = stringResource(R.string.cube_size_description, size)
    Column(
        modifier = modifier
            .clip(CircleShape)
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            }
            // The selectable above supplies role, state and action; this replaces the label's text.
            .clearAndSetSemantics { contentDescription = description }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MiniFace(n = size, color = tone.copy(alpha = if (selected) 0.85f else 0.55f))
        Spacer(Modifier.height(5.dp))
        Text(
            text = stringResource(R.string.cube_size_label, size),
            style = SegmentLabelStyle,
            color = tone,
            maxLines = 1,
        )
    }
}

/** A tiny N×N grid of rounded squares in [color]: the size at a glance. */
@Composable
private fun MiniFace(n: Int, color: Color) {
    Canvas(Modifier.size(MiniFaceSize)) {
        val gap = size.width * 0.06f
        val cell = (size.width - (n - 1) * gap) / n
        val radius = CornerRadius(cell * 0.28f)
        for (row in 0 until n) {
            for (col in 0 until n) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = radius,
                )
            }
        }
    }
}

private val MiniFaceSize = 13.dp
