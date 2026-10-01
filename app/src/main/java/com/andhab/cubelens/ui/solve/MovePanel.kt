package com.andhab.cubelens.ui.solve

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.cube.Move
import com.andhab.cubelens.ui.components.GlassCard
import com.andhab.cubelens.ui.components.GradientText
import com.andhab.cubelens.ui.components.displayNotation
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.components.spokenNotation
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.DisplayFont
import com.andhab.cubelens.ui.theme.NotationStyle

/**
 * The move to make now, front and center: the notation huge in sunset, the face and direction in
 * plain words (with where to look from, for faces that point away from the person), a [TurnGlyph]
 * pictogram, and "Move 3 of 19" above a slim sunset progress bar.
 *
 * Moving to another move slides the old one out and the new one in, in the direction of travel.
 * Screen readers hear the whole move ("Move 3 of 19: R prime, Right face · clockwise") whenever it
 * changes.
 *
 * The words come first: the panel measures every move of the solution and picks the largest
 * [HeadlineSize] whose words fit on one line beside the notation and pictogram, shrinking both and
 * then leaving the pictogram out on narrow screens or at large text sizes. Should even that not be
 * enough, the words wrap rather than cut off. The notation is sized in dp: it is already
 * display-sized, and letting it grow with the font scale would only squeeze the words beside it.
 *
 * @param currentIndex index of the move to show; clamped to the last move, so a finished solution
 *   keeps showing its final move while the panel leaves the screen.
 * @param position number of moves done, for the progress bar.
 * @param faceColor sticker color of each face's center, used to paint the pictogram.
 */
@Composable
internal fun MovePanel(
    moves: List<Move>,
    currentIndex: Int,
    position: Int,
    faceColor: (Face) -> Color,
    modifier: Modifier = Modifier,
) {
    if (moves.isEmpty()) return
    val index = currentIndex.coerceIn(0, moves.lastIndex)
    val move = moves[index]
    val counter = stringResource(R.string.solve_move_counter, index + 1, moves.size)
    val viewpoint = turnViewpoint(move.face)
    val spoken = if (viewpoint == null) {
        stringResource(R.string.solve_current_move_spoken, counter, spokenNotation(move.notation), moveDescription(move))
    } else {
        stringResource(
            R.string.solve_current_move_spoken_viewpoint,
            counter,
            spokenNotation(move.notation),
            moveDescription(move),
            viewpoint,
        )
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 14.dp, top = 14.dp, bottom = 18.dp),
    ) {
        val texts = headlineTexts(moves)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val size = rememberHeadlineSize(texts, maxWidth)
            Row(
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedContent(
                    targetState = index,
                    modifier = Modifier.weight(1f),
                    transitionSpec = {
                        val direction = if (targetState >= initialState) 1 else -1
                        val enter = slideInHorizontally(MoveSlideSpec) { direction * it / 5 } + fadeIn(tween(220, delayMillis = 60))
                        val exit = slideOutHorizontally(MoveSlideSpec) { -direction * it / 5 } + fadeOut(tween(140))
                        (enter togetherWith exit).using(SizeTransform(clip = false))
                    },
                    label = "currentMove",
                ) { shown ->
                    MoveHeadline(moves[shown], size)
                }
                size.glyph?.let { glyphSize ->
                    Spacer(Modifier.width(GlyphGap))
                    TurnGlyph(
                        turns = move.turns,
                        faceColor = faceColor(move.face),
                        modifier = Modifier.size(glyphSize),
                        key = index,
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = counter,
                style = MaterialTheme.typography.labelLarge,
                color = Brand.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val left = moves.size - position
            Text(
                text = pluralStringResource(R.plurals.solve_moves_to_go, left, left),
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = DisplayFont, fontWeight = FontWeight.Medium),
                color = Brand.TextTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(10.dp))
        SunsetProgressBar(fraction = position.toFloat() / moves.size, steps = moves.size)
    }
}

private val MoveSlideSpec = spring<IntOffset>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

/** Gap between the words and the pictogram. */
private val GlyphGap = 6.dp

/** Size of the viewpoint line's eye icon, and the gap after it. */
private val ViewIconSize = 14.dp
private val ViewIconGap = 5.dp

/**
 * Sizes of the move headline, largest first: the notation, the gap after it and the pictogram
 * (null leaves it out).
 */
private enum class HeadlineSize(val notation: Dp, val gap: Dp, val glyph: Dp?) {
    Regular(notation = 72.dp, gap = 16.dp, glyph = 78.dp),
    Compact(notation = 60.dp, gap = 12.dp, glyph = 64.dp),
    Small(notation = 50.dp, gap = 10.dp, glyph = 52.dp),
    WordsOnly(notation = 50.dp, gap = 10.dp, glyph = null),
}

/** The words of one move's headline, as shown. */
private data class HeadlineText(val notation: String, val face: String, val direction: String, val viewpoint: String?)

/** The headline words of each distinct move in [moves]. */
@Composable
private fun headlineTexts(moves: List<Move>): List<HeadlineText> {
    val locale = currentLocale()
    return moves.distinct().map { move ->
        HeadlineText(
            notation = displayNotation(move.notation),
            face = faceName(move.face),
            direction = turnDirection(move).capitalizeFirst(locale),
            viewpoint = turnViewpoint(move.face),
        )
    }
}

/**
 * The largest [HeadlineSize] at which every one of [texts] fits in [width] with each line of words
 * on one line; [HeadlineSize.WordsOnly] (whose words may wrap) when none does. Measured once per
 * solution and width, so the layout holds still while stepping through the moves.
 */
@Composable
private fun rememberHeadlineSize(texts: List<HeadlineText>, width: Dp): HeadlineSize {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    return remember(texts, width, density, typography) {
        fun measure(text: String, style: TextStyle): Dp =
            with(density) { measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toDp() }

        fun fits(text: HeadlineText, size: HeadlineSize): Boolean {
            val notationSize = with(density) { size.notation.toSp() }
            val notation = measure(text.notation, NotationStyle.copy(fontSize = notationSize, lineHeight = notationSize))
            val words = maxOf(
                measure(text.face, typography.titleLarge),
                measure(text.direction, typography.bodyMedium),
                text.viewpoint?.let { measure(it, typography.bodySmall) + ViewIconSize + ViewIconGap } ?: 0.dp,
            )
            val glyph = size.glyph?.let { it + GlyphGap } ?: 0.dp
            return notation + size.gap + words + glyph <= width
        }

        HeadlineSize.entries.firstOrNull { size -> texts.all { fits(it, size) } } ?: HeadlineSize.WordsOnly
    }
}

/**
 * Big notation plus the face, the turn direction and (for faces pointing away) where to look from,
 * in words, at [size]. The words wrap onto a second line rather than cut off.
 */
@Composable
private fun MoveHeadline(move: Move, size: HeadlineSize) {
    val notationSize = with(LocalDensity.current) { size.notation.toSp() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GradientText(
            text = displayNotation(move.notation),
            style = NotationStyle.copy(fontSize = notationSize, lineHeight = notationSize),
        )
        Spacer(Modifier.width(size.gap))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = faceName(move.face),
                style = MaterialTheme.typography.titleLarge,
                color = Brand.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = turnDirection(move).capitalizeFirst(currentLocale()),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            turnViewpoint(move.face)?.let { viewpoint ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Visibility,
                        contentDescription = null,
                        tint = Brand.TextTertiary,
                        modifier = Modifier.size(ViewIconSize),
                    )
                    Spacer(Modifier.width(ViewIconGap))
                    Text(
                        text = viewpoint,
                        style = MaterialTheme.typography.bodySmall,
                        color = Brand.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}


/**
 * Slim progress bar: a faint track and a sunset fill whose glowing head eases to [fraction].
 *
 * @param steps number of discrete steps, for accessibility.
 */
@Composable
private fun SunsetProgressBar(fraction: Float, steps: Int, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow),
        label = "solveProgress",
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, steps = (steps - 1).coerceAtLeast(0))
            },
    ) {
        val thickness = 4.dp.toPx()
        val y = size.height / 2f
        val startX = thickness / 2f
        val endX = size.width - thickness / 2f
        drawLine(
            color = Color.White.copy(alpha = 0.09f),
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )
        if (progress <= 0f) return@Canvas
        val headX = startX + (endX - startX) * progress
        drawSoftGlow(Brand.Tangerine, alpha = 0.45f, center = Offset(headX, y), radiusX = 14.dp.toPx(), radiusY = 8.dp.toPx())
        drawLine(
            brush = Brush.horizontalGradient(Brand.SunsetColors, startX = 0f, endX = size.width),
            start = Offset(startX, y),
            end = Offset(headX, y),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )
    }
}
