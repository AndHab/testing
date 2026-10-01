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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.R
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

/** The prime mark Space Grotesk draws as a straight wedge; its ASCII apostrophe looks like a comma. */
private const val Prime = '′'

/**
 * Cube notation as it should be displayed: "R'" becomes "R′" with a true prime mark, which reads
 * clearly at every size instead of looking like a comma. Use it for every notation shown in
 * [com.andhab.cubelens.ui.theme.DisplayFont], e.g. the big move on the solve screen.
 */
fun displayNotation(notation: String): String = notation.replace('\'', Prime).replace('’', Prime)

/**
 * Cube notation as a screen reader should say it: "R" stays "R", "R'" becomes "R prime" and "R2"
 * becomes "R two" (localized), instead of "R apostrophe".
 */
@Composable
fun spokenNotation(notation: String): String {
    val face = notation.trimEnd('\'', '’', Prime, '2')
    return when {
        face == notation -> notation
        notation.contains('2') -> stringResource(R.string.notation_double, face)
        else -> stringResource(R.string.notation_prime, face)
    }
}

/**
 * One move of a solution in cube notation ("R", "U'", "F2") for move strips and timelines.
 * Upcoming moves are glass tiles, the current move glows with a sunset outline and gradient
 * glyphs and pops slightly larger, and done moves recede into fainter glass while staying legible.
 *
 * Prime moves are drawn with a true prime mark (see [displayNotation]). Screen readers hear the
 * move spelled out plus its state, e.g. "R prime, Current move".
 *
 * @param contentDescription overrides the spoken move; defaults to [spokenNotation].
 */
@Composable
fun MoveChip(
    notation: String,
    modifier: Modifier = Modifier,
    state: MoveState = MoveState.Upcoming,
    contentDescription: String? = null,
) {
    val current = state == MoveState.Current
    val scale by animateFloatAsState(
        targetValue = if (current) 1.08f else 1f,
        animationSpec = CubeLensMotion.select(),
        label = "moveChipScale",
    )
    val spoken = contentDescription ?: spokenNotation(notation)
    val stateLabel = stringResource(
        when (state) {
            MoveState.Done -> R.string.move_state_done
            MoveState.Current -> R.string.move_state_current
            MoveState.Upcoming -> R.string.move_state_upcoming
        },
    )
    Box(
        modifier = modifier
            .clearAndSetSemantics {
                this.contentDescription = spoken
                stateDescription = stateLabel
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(if (current) Modifier.sunsetGlow(alpha = 0.32f, spread = 10.dp, offsetY = 4.dp) else Modifier)
            .then(
                when (state) {
                    MoveState.Current -> Modifier
                        .glassSurface(MoveChipShape, fill = Brand.GlassHigh)
                        .border(1.5.dp, Brand.SunsetBrush, MoveChipShape)
                    MoveState.Upcoming -> Modifier.glassSurface(MoveChipShape)
                    MoveState.Done -> Modifier.glassSurface(
                        MoveChipShape,
                        fill = Brand.Glass.copy(alpha = 0.45f),
                        border = SolidColor(Brand.Hairline),
                    )
                },
            )
            .defaultMinSize(minWidth = 52.dp, minHeight = 52.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Notation is short and must never wrap ("B" over "2" would read as two moves).
        Text(
            text = displayNotation(notation),
            style = if (current) MoveTextStyle.copy(brush = Brand.SunsetHorizontalBrush) else MoveTextStyle,
            color = when (state) {
                MoveState.Done -> Brand.TextSecondary.copy(alpha = 0.75f)
                MoveState.Current -> Color.Unspecified
                MoveState.Upcoming -> Brand.TextPrimary
            },
            maxLines = 1,
            softWrap = false,
        )
    }
}
