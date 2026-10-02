package com.andhab.cubelens.ui.solve

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.Face
import com.andhab.cubelens.ui.components.colorName
import com.andhab.cubelens.ui.components.drawSticker
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubePalette
import com.andhab.cubelens.ui.theme.DisplayFont
import com.andhab.cubelens.ui.theme.LocalStickerPalette
import kotlin.math.ceil

/** How the person should hold the cube for the solution to read right. */
@Immutable
internal sealed interface HoldOrientation {

    /**
     * A cube with fixed centers (odd sizes): the center colors that face the person, the sky and
     * the right hand.
     */
    data class Centers(val front: CubeColor, val top: CubeColor, val right: CubeColor) : HoldOrientation

    /** A cube without fixed centers (even sizes): held as it was scanned. */
    data object AsScanned : HoldOrientation
}

/**
 * The orientation the solution assumes, floating above the cube in a soft capsule led by a tiny
 * cube that shows it:
 *  - fixed centers: "Hold ■ green toward you, ■ white on top", each color name after a swatch of
 *    the cube's own sticker color (from [LocalStickerPalette], so a pastel cube's mint shows as
 *    mint), the tiny cube painted in the front, top and right colors;
 *  - no centers: "Hold it as scanned: first face toward you, fifth on top", the tiny cube marked
 *    1 on the front and 5 on top.
 *
 * One line when it fits inside the screen gutter; on narrow screens with large text, balanced
 * lines in a capsule that hugs the longest line, since every word of it matters.
 */
@Composable
internal fun HoldHint(orientation: HoldOrientation, modifier: Modifier = Modifier) {
    val palette = LocalStickerPalette.current
    val locale = currentLocale()
    val hint: HintText = when (orientation) {
        is HoldOrientation.Centers -> {
            val front = colorName(orientation.front).lowercase(locale)
            val top = colorName(orientation.top).lowercase(locale)
            val template = stringResource(R.string.solve_hold_hint, FrontMark.toString(), TopMark.toString())
            remember(template, front, top) { HintText.withSwatches(template, front, top) }
        }
        HoldOrientation.AsScanned -> {
            val text = stringResource(R.string.solve_hold_hint_scanned)
            remember(text) { HintText(AnnotatedString(text), text) }
        }
    }
    val swatchColors = when (orientation) {
        is HoldOrientation.Centers -> mapOf(FrontSwatch to palette.color(orientation.front), TopSwatch to palette.color(orientation.top))
        HoldOrientation.AsScanned -> emptyMap()
    }
    val inlineContent = remember(swatchColors) {
        swatchColors.mapValues { (_, color) -> InlineTextContent(SwatchPlaceholder) { Swatch(color) } }
    }

    BoxWithConstraints(modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.TopCenter) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val style = MaterialTheme.typography.labelMedium
        val placeholders = remember(hint) { hint.placeholders() }
        val maxTextWidth = maxWidth - HintChrome
        // Null when the hint fits on one line; else the width of its longest balanced line.
        val wrapWidth: Dp? = remember(hint, style, density, maxTextWidth) {
            val oneLine = measurer.measure(hint.text, style, softWrap = false, maxLines = 1, placeholders = placeholders)
            val width = with(density) { oneLine.size.width.toDp() }
            if (width <= maxTextWidth) {
                null
            } else {
                val px = with(density) { maxTextWidth.roundToPx().coerceAtLeast(1) }
                val layout = measurer.measure(
                    hint.text,
                    style.copy(lineBreak = LineBreak.Heading),
                    constraints = Constraints(maxWidth = px),
                    placeholders = placeholders,
                )
                val widest = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
                with(density) { ceil(widest).toDp() }
            }
        }
        val shape = if (wrapWidth == null) RoundedCornerShape(50) else RoundedCornerShape(18.dp)
        val color = Brand.TextSecondary
        Row(
            modifier = Modifier
                .heightIn(min = 34.dp)
                .background(color.copy(alpha = 0.12f), shape)
                .border(1.dp, color.copy(alpha = 0.24f), shape)
                .padding(start = 7.dp, end = 14.dp, top = 5.dp, bottom = 5.dp)
                .clearAndSetSemantics { text = AnnotatedString(hint.plain) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IconGap),
        ) {
            HoldIcon(orientation, Modifier.size(IconSize))
            Text(
                text = hint.text,
                style = if (wrapWidth == null) style else style.copy(lineBreak = LineBreak.Heading),
                color = color,
                inlineContent = inlineContent,
                maxLines = if (wrapWidth == null) 1 else Int.MAX_VALUE,
                softWrap = wrapWidth != null,
                modifier = if (wrapWidth == null) Modifier else Modifier.width(wrapWidth),
            )
        }
    }
}

private val IconSize = 24.dp
private val IconGap = 7.dp

/** Width the capsule adds around its text: start padding, icon, gap and end padding. */
private val HintChrome = 7.dp + IconSize + IconGap + 14.dp

/** Stand-ins for the color names while the localized template is filled in. */
private const val FrontMark = ''
private const val TopMark = ''

private const val FrontSwatch = "frontSwatch"
private const val TopSwatch = "topSwatch"

/** A swatch sits in the text like a capital letter, a hair narrower than tall. */
private val SwatchPlaceholder = Placeholder(width = 0.95.em, height = 0.95.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter)

/**
 * The hint as drawn ([text], with inline swatches) and as read aloud or searched for ([plain]).
 */
@Immutable
private class HintText(val text: AnnotatedString, val plain: String) {

    /** Where the inline swatches sit in [text], for measuring. */
    fun placeholders(): List<AnnotatedString.Range<Placeholder>> =
        text.getStringAnnotations(InlineContentTag, 0, text.length).map { AnnotatedString.Range(SwatchPlaceholder, it.start, it.end) }

    companion object {
        /** The tag Compose's text uses to mark inline content. */
        private const val InlineContentTag = "androidx.compose.foundation.text.inlineContent"

        /**
         * Fills [template] (with [FrontMark] and [TopMark] where the color names go) with a swatch,
         * a non-breaking space and the name, so a swatch never ends up apart from its name.
         */
        fun withSwatches(template: String, front: String, top: String): HintText {
            val text = buildAnnotatedString {
                for (ch in template) {
                    when (ch) {
                        FrontMark -> {
                            appendInlineContent(FrontSwatch, "■")
                            append(' ')
                            append(front)
                        }
                        TopMark -> {
                            appendInlineContent(TopSwatch, "■")
                            append(' ')
                            append(top)
                        }
                        else -> append(ch)
                    }
                }
            }
            val plain = template.replace(FrontMark.toString(), front).replace(TopMark.toString(), top)
            return HintText(text, plain)
        }
    }
}

/** A tiny glossy sticker of [color] on a sliver of black body, like a sticker on the cube. */
@Composable
private fun Swatch(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val side = size.minDimension
        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        drawRoundRect(CubePalette.Body, topLeft, Size(side, side), CornerRadius(side * 0.26f))
        val inset = side * 0.1f
        drawSticker(color, topLeft + Offset(inset, inset), Size(side - 2 * inset, side - 2 * inset), cornerFraction = 0.24f)
    }
}

/**
 * A tiny cube showing how to hold it: painted in the front, top and right center colors, or (held
 * as scanned) glass with the front face marked 1 and the top marked 5, in sunset tones.
 */
@Composable
private fun HoldIcon(orientation: HoldOrientation, modifier: Modifier) {
    val palette = LocalStickerPalette.current
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val side = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        when (orientation) {
            is HoldOrientation.Centers -> {
                val pictogram = CubePictogram.of(3)
                drawCubePictogram(pictogram, center, side) { sticker ->
                    when (sticker.face) {
                        Face.U -> palette.color(orientation.top)
                        Face.F -> palette.color(orientation.front)
                        else -> palette.color(orientation.right)
                    }
                }
            }
            HoldOrientation.AsScanned -> {
                // Solid faces (no stickers to cross the numbers): 1 in front, 5 on top.
                val pictogram = CubePictogram.of(2)
                drawPath(roundedPolygon(pictogram.outline.map { center + it * side }, side * 0.06f), CubePalette.Body)
                for ((face, fill) in listOf(Face.U to Brand.Gold, Face.F to Brand.Coral, Face.R to Color.White.copy(alpha = 0.16f))) {
                    val panel = pictogram.facePanel(face, inset = 0.14f).map { center + it * side }
                    drawPath(roundedPolygon(panel, side * 0.08f), fill)
                }
                for ((face, label) in listOf(Face.F to "1", Face.U to "5")) {
                    val faceCenter = pictogram.facePanel(face, inset = 0f).let { it.reduce { a, b -> a + b } / it.size.toFloat() }
                    val layout = measurer.measure(label, NumberStyle)
                    drawText(
                        layout,
                        color = Brand.OnAccent,
                        topLeft = center + faceCenter * side - Offset(layout.size.width / 2f, layout.size.height / 2f),
                    )
                }
            }
        }
    }
}

private val NumberStyle = TextStyle(fontFamily = DisplayFont, fontWeight = FontWeight.Bold, fontSize = 9.sp, lineHeight = 9.sp)
