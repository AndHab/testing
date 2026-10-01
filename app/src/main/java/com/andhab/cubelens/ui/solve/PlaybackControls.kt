package com.andhab.cubelens.ui.solve

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.R
import com.andhab.cubelens.ui.components.CircleIconButton
import com.andhab.cubelens.ui.components.glassSurface
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion
import com.andhab.cubelens.ui.theme.DisplayFont

/**
 * Transport controls for [playback], symmetric around a big glowing play/pause disc:
 * start over, previous, play/pause, next and the speed selector, which steps through
 * 0.5×, 1× and 2× and always shows the current speed.
 */
@Composable
internal fun PlaybackControls(
    playback: SolvePlayback,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(
            icon = Icons.Rounded.Replay,
            contentDescription = stringResource(R.string.solve_restart),
            onClick = playback::restart,
            size = SideButtonSize,
            enabled = playback.position > 0 || playback.target > 0,
        )
        CircleIconButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = stringResource(R.string.solve_previous),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                playback.previous()
            },
            size = StepButtonSize,
            enabled = playback.canGoBack,
        )
        CircleIconButton(
            icon = if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = stringResource(if (playback.isPlaying) R.string.solve_pause else R.string.solve_play),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                playback.togglePlay()
            },
            size = PlayButtonSize,
            highlighted = true,
        )
        CircleIconButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = stringResource(R.string.solve_next),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                playback.next()
            },
            size = StepButtonSize,
            enabled = playback.canGoForward,
        )
        SpeedButton(speed = playback.speed, onClick = playback::cycleSpeed, size = SideButtonSize)
    }
}

private val SideButtonSize: Dp = 46.dp
private val StepButtonSize: Dp = 56.dp
private val PlayButtonSize: Dp = 76.dp

private val SpeedTextStyle = TextStyle(
    fontFamily = DisplayFont,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    lineHeight = 16.sp,
)

/** Dark-glass disc showing the current speed; each tap selects the next one. */
@Composable
private fun SpeedButton(speed: PlaybackSpeed, onClick: () -> Unit, size: Dp) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = CubeLensMotion.press(),
        label = "speedScale",
    )
    val description = stringResource(R.string.solve_speed)
    val changeLabel = stringResource(R.string.solve_speed_change)
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .glassSurface(
                shape = CircleShape,
                fill = if (pressed) Brand.GlassHigh else Brand.Glass,
                border = SpeedBorderBrush,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClickLabel = changeLabel,
                onClick = onClick,
            )
            .semantics {
                contentDescription = description
                stateDescription = speed.label
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = speed.label,
            style = SpeedTextStyle,
            color = if (speed == PlaybackSpeed.Normal) Brand.TextPrimary else Brand.Gold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private val SpeedBorderBrush = Brush.verticalGradient(listOf(Color(0x40FFFFFF), Color(0x14FFFFFF)))
