package com.andhab.cubelens.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.ui.theme.Brand

/**
 * Indeterminate progress: a comet-like arc whose tail fades into a faint track, spinning steadily.
 * Decorative; describe the busy state on the surrounding element (e.g. "Solving…").
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    color: Color = Brand.Tangerine,
    size: Dp = 24.dp,
    strokeWidth: Dp = 2.5.dp,
) {
    val rotation = rememberSpinnerRotation()
    Canvas(modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val arcSize = this.size.copy(width = this.size.width - stroke, height = this.size.height - stroke)
        val topLeft = Offset(inset, inset)
        drawArc(
            color = color.copy(alpha = color.alpha * 0.18f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(stroke),
        )
        rotate(rotation.value) {
            drawArc(
                brush = Brush.sweepGradient(
                    0f to color.copy(alpha = 0f),
                    0.72f to color,
                    1f to color,
                ),
                startAngle = 0f,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun rememberSpinnerRotation(): State<Float> {
    if (LocalInspectionMode.current) return remember { FrozenState(-70f) }
    val transition = rememberInfiniteTransition(label = "spinner")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "spinnerRotation",
    )
}
