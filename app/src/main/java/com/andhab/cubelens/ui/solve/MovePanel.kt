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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.core.nxn.LayerMove
import com.andhab.cubelens.ui.components.GlassCard
import com.andhab.cubelens.ui.components.GradientText
import com.andhab.cubelens.ui.components.Overline
import com.andhab.cubelens.ui.components.displayNotation
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.components.glassSurface
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensMotion
import com.andhab.cubelens.ui.theme.DisplayFont
import com.andhab.cubelens.ui.theme.NotationStyle

/**
 * Where the panel is in a solution of several stages, and how to jump between them.
 *
 * @property stages every stage, in order.
 * @property current index of the stage of the current move.
 */
@Immutable
internal data class PanelStages(
    val stages: List<TimelineStage>,
    val current: Int,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
)

/**
 * The move to make now, front and center: the notation huge in sunset, the layers and direction in
 * plain words (with where to look from, for sides that point away from the person), a [TurnGlyph]
 * pictogram, and "Move 3 of 19" above a slim sunset progress bar.
 *
 * For a solution in several [stages], an overline names the stage under way ("STEP 2 OF 3 ·
 * EDGES") beside buttons that jump to the previous and next stage, and the progress bar is split
 * into one segment per stage.
 *
 * Moving to another move slides the old one out and the new one in, in the direction of travel.
 * Screen readers hear the whole move ("Move 3 of 19: R prime, Right face · clockwise") whenever it
 * changes.
 *
 * The words come first: the panel measures every move of the solution once and picks the largest
 * [HeadlineSize] at which every move's words fit beside the notation and pictogram, first on one
 * line each, then letting the layer names of big cubes ("2nd layer from / the bottom") take two
 * balanced lines; on narrow screens or at large text sizes the pictogram is left out, then the
 * notation shrinks, and only then do the other lines wrap too. Rare
 * long notations ("2-3Rw′") are drawn smaller rather than squeezing every move. The headline keeps
 * the height of the tallest move, so the cube above never jumps. The notation is sized in dp: it is
 * already display-sized, and letting it grow with the font scale would only squeeze the words.
 *
 * @param n size of the cube.
 * @param currentIndex index of the move to show; clamped to the last move, so a finished solution
 *   keeps showing its final move while the panel leaves the screen.
 * @param position number of moves done, for the progress bar.
 * @param faceColor sticker color of each side, used to paint the pictogram.
 * @param stages stage header and segments; null for a solution in one stage.
 */
@Composable
internal fun MovePanel(
    moves: List<LayerMove>,
    n: Int,
    currentIndex: Int,
    position: Int,
    faceColor: (Face) -> Color,
    modifier: Modifier = Modifier,
    stages: PanelStages? = null,
) {
    if (moves.isEmpty()) return
    val index = currentIndex.coerceIn(0, moves.lastIndex)
    val move = moves[index]
    val counter = stringResource(R.string.solve_move_counter, index + 1, moves.size)
    val words = moveWords(move, n)
    val description = moveDescription(move, n)
    val spoken = if (words.viewpoint == null) {
        stringResource(R.string.solve_current_move_spoken, counter, spokenMove(move), description)
    } else {
        stringResource(R.string.solve_current_move_spoken_viewpoint, counter, spokenMove(move), description, words.viewpoint)
    }

    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 14.dp, top = if (stages != null) 10.dp else 14.dp, bottom = 18.dp),
    ) {
        if (stages != null) {
            StageHeader(stages, moves.size, position)
            Spacer(Modifier.height(4.dp))
        }
        val texts = headlineTexts(moves, n)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val layout = rememberHeadlineLayout(texts, maxWidth)
            Row(
                modifier = Modifier
                    .heightIn(min = layout.minHeight)
                    .clearAndSetSemantics {
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
                    contentAlignment = Alignment.CenterStart,
                    label = "currentMove",
                ) { shown ->
                    MoveHeadline(moves[shown], n, layout)
                }
                layout.size.glyph?.let { glyphSize ->
                    Spacer(Modifier.width(GlyphGap))
                    TurnGlyph(
                        move = move,
                        n = n,
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
        val starts = remember(stages?.stages, moves.size) { stages?.stages?.map { it.start } ?: listOf(0) }
        SegmentedProgressBar(position = position, moveCount = moves.size, segmentStarts = starts)
    }
}

private val MoveSlideSpec = spring<IntOffset>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

/** Gap between the words and the pictogram. */
private val GlyphGap = 6.dp

/** Size of the viewpoint line's eye icon, and the gap after it. */
private val ViewIconSize = 14.dp
private val ViewIconGap = 5.dp

/** Space between the lines of words. */
private val WordsSpacing = 2.dp

/** Widest a notation is drawn at full size, in multiples of its font size ("3Rw′" fits; "2-3Rw′" shrinks). */
private const val NotationMaxEm = 2.45f

/** Slack kept when deciding whether words fit, so rounding never wraps a line that was measured to fit. */
private val FitSlack = 2.dp

/**
 * Sizes of the move headline, largest first: the notation, the gap after it and the pictogram
 * (null leaves it out).
 */
private enum class HeadlineSize(val notation: Dp, val gap: Dp, val glyph: Dp?) {
    Regular(notation = 72.dp, gap = 16.dp, glyph = 78.dp),
    Compact(notation = 60.dp, gap = 12.dp, glyph = 64.dp),
    Small(notation = 50.dp, gap = 10.dp, glyph = 56.dp),
    WordsOnly(notation = 50.dp, gap = 10.dp, glyph = null),
    Tiny(notation = 40.dp, gap = 8.dp, glyph = null),
}

/**
 * How the headline is laid out for a whole solution: its [size], how many lines a layer name may
 * take, the height that holds the tallest move, and how much each long notation is scaled down.
 */
@Immutable
private class HeadlineLayout(
    val size: HeadlineSize,
    val layerLines: Int,
    val minHeight: Dp,
    private val notationScales: Map<String, Float>,
) {
    /** Factor (≤ 1) for the font size of [notation]. */
    fun scaleOf(notation: String): Float = notationScales[notation] ?: 1f
}

/** The words of one move's headline, as shown. */
private data class HeadlineText(val notation: String, val layers: String, val direction: String, val viewpoint: String?)

/** The headline words of each distinct move in [moves], measured once per solution. */
@Composable
private fun headlineTexts(moves: List<LayerMove>, n: Int): List<HeadlineText> {
    val locale = currentLocale()
    val resources = LocalResources.current
    return remember(moves, n, locale, resources) {
        moves.distinct().map { move ->
            val words = resources.moveWords(move, n)
            HeadlineText(
                notation = displayNotation(move.notation),
                layers = words.layers,
                direction = words.direction.capitalizeFirst(locale),
                viewpoint = words.viewpoint,
            )
        }
    }
}

/**
 * The [HeadlineLayout] for [texts] in [width]: the largest [HeadlineSize] at which every move's
 * words fit with one line each, else the largest at which layer names may take two lines, else
 * [HeadlineSize.Tiny] with every line free to wrap. Measured once per solution and width, so
 * the layout holds still while stepping through the moves.
 */
@Composable
private fun rememberHeadlineLayout(texts: List<HeadlineText>, width: Dp): HeadlineLayout {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    return remember(texts, width, density, typography) {
        HeadlineMeasure(measurer, density, typography.titleLarge, typography.bodyMedium, typography.bodySmall).layout(texts, width)
    }
}

/** Measures headline words for [rememberHeadlineLayout], each distinct string once. */
private class HeadlineMeasure(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val layersStyle: TextStyle,
    private val directionStyle: TextStyle,
    private val viewpointStyle: TextStyle,
) {
    private val oneLine = HashMap<Pair<String, TextStyle>, Dp>()
    private val wrapped = HashMap<Triple<String, Int, Int>, Pair<Int, Dp>>()

    /** Width of a notation in multiples of its font size. */
    private fun notationEm(notation: String): Float {
        val reference = 100.dp
        val fontSize = with(density) { reference.toSp() }
        val width = width(notation, NotationStyle.copy(fontSize = fontSize, lineHeight = fontSize))
        return width / reference
    }

    private fun width(text: String, style: TextStyle): Dp = oneLine.getOrPut(text to style) {
        with(density) { measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toDp() }
    }

    /** Line count and height of [text] in [layersStyle] wrapped (balanced) into [width], up to [maxLines] lines. */
    private fun wrappedLayers(text: String, width: Dp, maxLines: Int): Pair<Int, Dp> {
        val px = with(density) { width.roundToPx() }.coerceAtLeast(1)
        return wrapped.getOrPut(Triple(text, px, maxLines)) {
            val result = measurer.measure(text, layersStyle.copy(lineBreak = LineBreak.Heading), maxLines = maxLines, constraints = Constraints(maxWidth = px))
            val lines = if (result.hasVisualOverflow) maxLines + 1 else result.lineCount
            lines to with(density) { result.size.height.toDp() }
        }
    }

    private fun longestWord(text: String, style: TextStyle): Dp =
        text.split(' ').filter { it.isNotEmpty() }.maxOfOrNull { width(it, style) } ?: 0.dp

    fun layout(texts: List<HeadlineText>, width: Dp): HeadlineLayout {
        val ems = texts.associate { it.notation to notationEm(it.notation) }
        val scales = ems.mapValues { (_, em) -> minOf(1f, NotationMaxEm / em) }

        fun wordsWidth(text: HeadlineText, size: HeadlineSize): Dp {
            val notation = size.notation * minOf(ems.getValue(text.notation), NotationMaxEm)
            val glyph = size.glyph?.let { it + GlyphGap } ?: 0.dp
            return width - glyph - notation - size.gap - FitSlack
        }

        fun fits(text: HeadlineText, size: HeadlineSize, layerLines: Int): Boolean {
            val words = wordsWidth(text, size)
            if (words <= 0.dp) return false
            val oneLine = width(text.layers, layersStyle) <= words
            val layersFit = oneLine || layerLines > 1 &&
                longestWord(text.layers, layersStyle) <= words &&
                wrappedLayers(text.layers, words, layerLines).first <= layerLines
            return layersFit &&
                width(text.direction, directionStyle) <= words &&
                (text.viewpoint == null || width(text.viewpoint, viewpointStyle) + ViewIconSize + ViewIconGap <= words)
        }

        // The pictogram matters more than one-line layer names; both matter more than a big notation.
        val withGlyph = HeadlineSize.entries.filter { it.glyph != null }
        val candidates = withGlyph.map { it to 1 } + withGlyph.map { it to 2 } +
            listOf(HeadlineSize.WordsOnly to 2, HeadlineSize.Tiny to 2)
        val (size, lines) = candidates.firstOrNull { (size, lines) -> texts.all { fits(it, size, lines) } }
            ?: (HeadlineSize.Tiny to WrapAllLayerLines)

        // The tallest move sets the height, so the panel never changes size between moves.
        val wordsHeight = texts.maxOfOrNull { text ->
            val words = wordsWidth(text, size).coerceAtLeast(1.dp)
            val layers = wrappedLayers(text.layers, words, lines).second
            val direction = wrappedHeight(text.direction, directionStyle, words)
            val viewpoint = text.viewpoint?.let { WordsSpacing + wrappedHeight(it, viewpointStyle, words - ViewIconSize - ViewIconGap) } ?: 0.dp
            layers + WordsSpacing + direction + viewpoint
        } ?: 0.dp
        val minHeight = maxOf(wordsHeight, size.notation, size.glyph ?: 0.dp)
        return HeadlineLayout(size, lines, minHeight, scales)
    }

    private fun wrappedHeight(text: String, style: TextStyle, width: Dp): Dp {
        val px = with(density) { width.roundToPx() }.coerceAtLeast(1)
        return with(density) { measurer.measure(text, style, constraints = Constraints(maxWidth = px)).size.height.toDp() }
    }
}

/** Lines a layer name may take when even the smallest headline needs every line of words to wrap. */
private const val WrapAllLayerLines = 3

/**
 * Big notation plus the layers, the turn direction and (for sides pointing away) where to look
 * from, in words, as [layout] says. The words wrap rather than cut off.
 */
@Composable
private fun MoveHeadline(move: LayerMove, n: Int, layout: HeadlineLayout) {
    val notation = displayNotation(move.notation)
    val notationSize = with(LocalDensity.current) { layout.size.notation.toSp() }
    val words = moveWords(move, n)
    Row(verticalAlignment = Alignment.CenterVertically) {
        GradientText(
            text = notation,
            style = NotationStyle.copy(fontSize = notationSize * layout.scaleOf(notation), lineHeight = notationSize),
        )
        Spacer(Modifier.width(layout.size.gap))
        Column(verticalArrangement = Arrangement.spacedBy(WordsSpacing)) {
            Text(
                text = words.layers,
                style = MaterialTheme.typography.titleLarge.copy(lineBreak = LineBreak.Heading),
                color = Brand.TextPrimary,
                maxLines = layout.layerLines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = words.direction.capitalizeFirst(currentLocale()),
                style = MaterialTheme.typography.bodyMedium,
                color = Brand.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            words.viewpoint?.let { viewpoint ->
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
 * "STEP 2 OF 3 · EDGES" with buttons to the previous and next stage. On narrow screens the
 * overline shortens to "2/3 · EDGES". Screen readers hear the stage and how far into it playback is.
 */
@Composable
private fun StageHeader(stages: PanelStages, moveCount: Int, position: Int) {
    val count = stages.stages.size
    val current = stages.current
    val stage = stages.stages[current]
    val stageEnd = stages.stages.getOrNull(current + 1)?.start ?: moveCount
    val stageSize = stageEnd - stage.start
    val done = (position - stage.start).coerceIn(0, stageSize)
    val full = stringResource(R.string.solve_stage_overline, current + 1, count, stage.name)
    val short = stringResource(R.string.solve_stage_overline_short, current + 1, count, stage.name)
    val spoken = stringResource(R.string.solve_stage_spoken, stage.name, current + 1, count, done, stageSize)
    Row(verticalAlignment = Alignment.CenterVertically) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .clearAndSetSemantics { contentDescription = spoken },
        ) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.em)
            val locale = currentLocale()
            val text = remember(full, short, style, density, maxWidth, locale) {
                val fullWidth = with(density) { measurer.measure(full.uppercase(locale), style, softWrap = false, maxLines = 1).size.width.toDp() }
                if (fullWidth + OverlineChrome <= maxWidth) full else short
            }
            Overline(text = text)
        }
        Spacer(Modifier.width(8.dp))
        StageJumpButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.solve_stage_previous),
            enabled = stages.hasPrevious,
            onClick = stages.onPrevious,
        )
        Spacer(Modifier.width(8.dp))
        StageJumpButton(
            icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = stringResource(R.string.solve_stage_next),
            enabled = stages.hasNext,
            onClick = stages.onNext,
        )
    }
}

/** Width the [Overline] adds before its text: the accent bar and the gap after it. */
private val OverlineChrome = 14.dp + 8.dp

/**
 * A small dark-glass disc with a chevron. The disc is 32dp to sit lightly in the header; touches
 * within the standard 48dp target around it still land.
 */
@Composable
private fun StageJumpButton(icon: ImageVector, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.9f else 1f,
        animationSpec = CubeLensMotion.press(),
        label = "stageJumpScale",
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.35f
            }
            .glassSurface(shape = CircleShape, fill = if (pressed) Brand.GlassHigh else Brand.Glass)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Brand.TextPrimary, modifier = Modifier.size(20.dp))
    }
}

/**
 * Slim progress bar: one faint track per stage (a single track for a one-stage solution) and a
 * sunset fill whose glowing head eases to [position].
 *
 * @param segmentStarts index of the first move of each segment, starting with 0.
 */
@Composable
private fun SegmentedProgressBar(position: Int, moveCount: Int, segmentStarts: List<Int>, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(
        targetValue = position.toFloat().coerceIn(0f, moveCount.toFloat()),
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessLow),
        label = "solveProgress",
    )
    val fraction = if (moveCount == 0) 1f else position.toFloat() / moveCount
    Canvas(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clearAndSetSemantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, steps = (moveCount - 1).coerceAtLeast(0))
            },
    ) {
        val thickness = 4.dp.toPx()
        val gap = if (segmentStarts.size > 1) 4.dp.toPx() else 0f
        val minSegment = 6.dp.toPx()
        val y = size.height / 2f
        val segments = segmentStarts.size
        val sizes = List(segments) { s -> (segmentStarts.getOrNull(s + 1) ?: moveCount) - segmentStarts[s] }
        // Every segment gets a minimum width; the rest is shared in proportion to its moves.
        val free = (size.width - gap * (segments - 1) - minSegment * segments).coerceAtLeast(0f)
        val brush = Brush.horizontalGradient(Brand.SunsetColors, startX = 0f, endX = size.width)
        // Segment bounds and how much of each is filled; the glowing head sits at the end of the fill.
        val lefts = FloatArray(segments)
        val widths = FloatArray(segments)
        val fills = FloatArray(segments)
        var head: Offset? = null
        var x = 0f
        for (s in 0 until segments) {
            lefts[s] = x
            widths[s] = minSegment + free * sizes[s] / moveCount.coerceAtLeast(1)
            fills[s] = if (sizes[s] == 0) 0f else ((progress - segmentStarts[s]) / sizes[s]).coerceIn(0f, 1f)
            if (fills[s] > 0f) head = Offset(x + (widths[s] * fills[s]).coerceAtLeast(thickness), y)
            x += widths[s] + gap
        }
        for (s in 0 until segments) {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.09f),
                topLeft = Offset(lefts[s], y - thickness / 2f),
                size = Size(widths[s], thickness),
                cornerRadius = CornerRadius(thickness / 2f),
            )
        }
        head?.let { drawSoftGlow(Brand.Tangerine, alpha = 0.45f, center = it, radiusX = 14.dp.toPx(), radiusY = 8.dp.toPx()) }
        for (s in 0 until segments) {
            if (fills[s] <= 0f) continue
            drawRoundRect(
                brush = brush,
                topLeft = Offset(lefts[s], y - thickness / 2f),
                size = Size((widths[s] * fills[s]).coerceAtLeast(thickness), thickness),
                cornerRadius = CornerRadius(thickness / 2f),
            )
        }
    }
}
