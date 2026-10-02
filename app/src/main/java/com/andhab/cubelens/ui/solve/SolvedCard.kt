package com.andhab.cubelens.ui.solve

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.ui.components.GlassCard
import com.andhab.cubelens.ui.components.GradientText
import com.andhab.cubelens.ui.components.Pill
import com.andhab.cubelens.ui.components.PrimaryButton
import com.andhab.cubelens.ui.components.SecondaryButton
import com.andhab.cubelens.ui.components.softGlow
import com.andhab.cubelens.ui.theme.Brand

/**
 * The finish line: a glowing mint check, "Solved!" in sunset, how many moves it took and a warm
 * sign-off, then the way out ("Scan another cube") and a [onReplay] to watch it again.
 *
 * @param moveCount length of the solution; 0 shows the "Already solved!" variant without replay.
 * @param staged the solution came in several stages (a big cube), which the sign-off honors.
 */
@Composable
internal fun SolvedCard(
    moveCount: Int,
    onScanAnother: () -> Unit,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
    staged: Boolean = false,
) {
    val alreadySolved = moveCount == 0
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 22.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            SolvedBadge()
            Spacer(Modifier.height(16.dp))
            GradientText(
                text = stringResource(if (alreadySolved) R.string.solve_already_solved_title else R.string.solve_solved_title),
                // "Already solved!" is twice as long; a size down keeps it on one line on small phones.
                style = (if (alreadySolved) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium)
                    .copy(textAlign = TextAlign.Center),
                modifier = Modifier.semantics { heading() },
            )
            if (!alreadySolved) {
                Spacer(Modifier.height(10.dp))
                Pill(
                    text = pluralStringResource(R.plurals.solve_move_count, moveCount, moveCount),
                    color = Brand.Mint,
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    when {
                        alreadySolved -> R.string.solve_already_solved_subtitle
                        staged -> R.string.solve_solved_subtitle_staged
                        else -> R.string.solve_solved_subtitle
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = Brand.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PrimaryButton(
                text = stringResource(R.string.solve_scan_another),
                onClick = onScanAnother,
                icon = Icons.Rounded.CameraAlt,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!alreadySolved) {
                Spacer(Modifier.height(12.dp))
                SecondaryButton(
                    text = stringResource(R.string.solve_replay),
                    onClick = onReplay,
                    icon = Icons.Rounded.Replay,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A mint check disc with a soft mint glow and a bright rim. */
@Composable
private fun SolvedBadge() {
    Box(
        modifier = Modifier
            .size(60.dp)
            .softGlow(Brand.Mint, alpha = 0.38f, spread = 22.dp, offsetY = 4.dp)
            .background(Brush.verticalGradient(listOf(Brand.Mint, Brand.Mint.copy(alpha = 0.78f))), CircleShape)
            .border(1.dp, Brush.verticalGradient(listOf(Brand.TextPrimary.copy(alpha = 0.6f), Brand.Mint.copy(alpha = 0f))), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = Brand.Ink, modifier = Modifier.size(34.dp))
    }
}
