package com.andhab.cubelens.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion

/**
 * Small non-interactive tag ("6 faces", "18 moves", "Beta"): a pill tinted with [color], with an
 * optional leading icon.
 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = Brand.TextSecondary,
) {
    Row(
        modifier = modifier
            .heightIn(min = 30.dp)
            .background(color.copy(alpha = 0.12f), CircleShape)
            .border(1.dp, color.copy(alpha = 0.24f), CircleShape)
            .padding(start = if (icon != null) 10.dp else 12.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Step progress: past steps as soft dots, upcoming steps as faint dots, and the [current] step as a
 * wider glowing sunset capsule that springs between positions.
 *
 * @param count number of steps (e.g. 6 faces to scan).
 * @param current zero-based index of the active step; values outside `0 until count` show no
 *   active capsule (e.g. `count` once everything is done marks all steps as past).
 */
@Composable
fun StepDots(
    count: Int,
    current: Int,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.step_progress, (current + 1).coerceIn(1, count.coerceAtLeast(1)), count)
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = current.coerceIn(0, count).toFloat(),
                range = 0f..count.coerceAtLeast(1).toFloat(),
                steps = (count - 1).coerceAtLeast(0),
            )
        },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            StepDot(state = when {
                index == current -> StepState.Active
                index < current -> StepState.Done
                else -> StepState.Upcoming
            })
        }
    }
}

private enum class StepState { Done, Active, Upcoming }

@Composable
private fun StepDot(state: StepState) {
    val active = state == StepState.Active
    val width by animateDpAsState(
        targetValue = if (active) 26.dp else 8.dp,
        animationSpec = CubeLensMotion.select(),
        label = "stepDotWidth",
    )
    val color by animateColorAsState(
        targetValue = when (state) {
            StepState.Done -> Brand.TextPrimary.copy(alpha = 0.6f)
            StepState.Active -> Brand.Tangerine
            StepState.Upcoming -> Brand.TextPrimary.copy(alpha = 0.3f)
        },
        animationSpec = CubeLensMotion.select(),
        label = "stepDotColor",
    )
    Box(
        Modifier
            .size(width = width, height = 8.dp)
            .then(
                if (active) {
                    Modifier
                        .sunsetGlow(alpha = 0.45f, spread = 6.dp, offsetY = 1.dp)
                        .background(Brand.SunsetHorizontalBrush, CircleShape)
                } else {
                    Modifier.background(color, CircleShape)
                },
            ),
    )
}
