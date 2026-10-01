package com.andhab.cubelens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.andhab.cubelens.ui.theme.Brand

/**
 * Text painted with a horizontal gradient (the sunset by default). Use it for the one key word of a
 * headline ("Snap. Solve. *Twist.*"), big numbers and celebratory moments; never for body copy.
 */
@Composable
fun GradientText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    colors: List<Color> = Brand.SunsetColors,
) {
    val brush = remember(colors) { Brush.horizontalGradient(colors) }
    Text(text = text, modifier = modifier, style = style.copy(brush = brush))
}

/**
 * Small, widely letter-spaced uppercase label that sits above a title ("STEP 2 OF 6",
 * "HOW IT WORKS"), led by a short accent bar.
 */
@Composable
fun Overline(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Brand.Tangerine,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(width = 14.dp, height = 2.dp)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color)), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.em,
            ),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
