package com.andhab.cubelens.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion
import com.andhab.cubelens.ui.theme.DisplayFont

/** Where a move sits in a solution being played back. */
enum class MoveState { Done, Current, Upcoming }

private val MoveChipShape: Shape = RoundedCornerShape(14.dp)

private val MoveTextStyle = TextStyle(
    fontFamily = DisplayFont,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.01).em,
)

/**
 * One move of a solution in cube notation ("R", "U'", "F2") for move strips and timelines.
 * Upcoming moves are glass tiles, the current move glows with a sunset outline and gradient
 * glyphs and pops slightly larger, and done moves recede.
 */
@Composable
fun MoveChip(
    notation: String,
    modifier: Modifier = Modifier,
    state: MoveState = MoveState.Upcoming,
) {
    val current = state == MoveState.Current
    val scale by animateFloatAsState(
        targetValue = if (current) 1.08f else 1f,
        animationSpec = CubeLensMotion.select(),
        label = "moveChipScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (state == MoveState.Done) 0.45f else 1f
            }
            .then(if (current) Modifier.sunsetGlow(alpha = 0.32f, spread = 10.dp, offsetY = 4.dp) else Modifier)
            .glassSurface(MoveChipShape, fill = if (current) Brand.GlassHigh else Brand.Glass)
            .then(if (current) Modifier.border(1.5.dp, Brand.SunsetBrush, MoveChipShape) else Modifier)
            .defaultMinSize(minWidth = 52.dp, minHeight = 52.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Notation is short and must never wrap ("B" over "2" would read as two moves).
        Text(
            text = notation,
            style = if (current) MoveTextStyle.copy(brush = Brand.SunsetHorizontalBrush) else MoveTextStyle,
            color = when (state) {
                MoveState.Done -> Brand.TextTertiary
                MoveState.Current -> Color.Unspecified
                MoveState.Upcoming -> Brand.TextPrimary
            },
            maxLines = 1,
            softWrap = false,
        )
    }
}
