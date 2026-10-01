package com.andhab.cubelens.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.ui.theme.CubeLensMotion
import kotlinx.coroutines.delay

/**
 * Staggered entrance: fades [content] in while it slides up by [offset], starting
 * `index * CubeLensMotion.StaggerMillis` after first composition. Wrap each item of a list or each
 * block of a hero in a `Reveal` with increasing [index] for the signature CubeLens entrance.
 * Renders fully visible in inspection mode (previews, screenshot tests).
 */
@Composable
fun Reveal(
    index: Int,
    modifier: Modifier = Modifier,
    offset: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    val static = LocalInspectionMode.current
    val progress = remember { Animatable(if (static) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            delay(index.coerceAtLeast(0) * CubeLensMotion.StaggerMillis)
            progress.animateTo(1f, CubeLensMotion.enter())
        }
    }
    Box(
        modifier.graphicsLayer {
            val p = progress.value
            alpha = p.coerceIn(0f, 1f)
            translationY = (1f - p) * offset.toPx()
        },
    ) {
        content()
    }
}

/** A [State] that never changes; stands in for running animations in inspection mode. */
internal class FrozenState<T>(override val value: T) : State<T>
