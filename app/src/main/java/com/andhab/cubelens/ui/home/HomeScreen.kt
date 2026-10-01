package com.andhab.cubelens.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.ui.components.AuroraBackground
import com.andhab.cubelens.ui.components.BrandLockup
import com.andhab.cubelens.ui.components.GlassCard
import com.andhab.cubelens.ui.components.GradientText
import com.andhab.cubelens.ui.components.Overline
import com.andhab.cubelens.ui.components.PrimaryButton
import com.andhab.cubelens.ui.components.Reveal
import com.andhab.cubelens.ui.components.SecondaryButton
import com.andhab.cubelens.ui.components.Spinner
import com.andhab.cubelens.ui.components.TextAction
import com.andhab.cubelens.ui.components.drawSoftGlow
import com.andhab.cubelens.ui.cube.Cube3D
import com.andhab.cubelens.ui.cube.rememberCubeViewState
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.DisplayFont

/**
 * The landing screen: a hero shot of a slowly spinning, scrambled 3D cube that can be grabbed and
 * flung, a punchy headline, a three-step "how it works" strip and the ways in: scan, type the
 * colors, or watch a random scramble get solved. Everything enters in a quick staggered cascade.
 *
 * @param scrambling a random scramble is being prepared; its action shows progress and is disabled.
 * @param heroColors the cube on show (54 colors, facelet order).
 */
@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onManualEntry: () -> Unit,
    onRandomScramble: () -> Unit,
    modifier: Modifier = Modifier,
    scrambling: Boolean = false,
    heroColors: List<CubeColor> = HeroCubeColors,
) {
    AuroraBackground(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 24.dp),
        ) {
            Reveal(index = 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BrandLockup()
                }
            }
            Reveal(
                index = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                offset = 28.dp,
            ) {
                HeroCube(heroColors, Modifier.fillMaxSize())
            }
            Reveal(index = 2) {
                Column {
                    Overline(stringResource(R.string.home_overline))
                    Spacer(Modifier.height(10.dp))
                    Headline()
                }
            }
            Spacer(Modifier.height(10.dp))
            Reveal(index = 3) {
                Text(
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Brand.TextSecondary,
                )
            }
            Spacer(Modifier.height(20.dp))
            Reveal(index = 4) { HowItWorks() }
            Spacer(Modifier.height(22.dp))
            Reveal(index = 5) {
                PrimaryButton(
                    text = stringResource(R.string.home_scan),
                    onClick = onScan,
                    icon = Icons.Rounded.CameraAlt,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
            Reveal(index = 6) {
                SecondaryButton(
                    text = stringResource(R.string.home_manual),
                    onClick = onManualEntry,
                    icon = Icons.Rounded.GridView,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Reveal(index = 7, modifier = Modifier.fillMaxWidth()) {
                RandomScrambleAction(
                    scrambling = scrambling,
                    onClick = onRandomScramble,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The user's own scrambled cube, as scanned from their photos: a real, colorful mix. */
internal val HeroCubeColors: List<CubeColor> =
    FaceletCube.parse("DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR").toColors()

/** The idling, draggable hero cube, lit from behind by a soft sunset bloom. */
@Composable
private fun HeroCube(colors: List<CubeColor>, modifier: Modifier = Modifier) {
    val state = rememberCubeViewState(colors, initialYaw = -44f, initialPitch = 26f)
    Cube3D(
        state = state,
        modifier = modifier
            .drawBehind {
                val center = Offset(size.width / 2f, size.height * 0.46f)
                val radius = size.minDimension * 0.62f
                drawSoftGlow(Brand.Magenta, alpha = 0.20f, center = center, radiusX = radius)
                drawSoftGlow(Brand.Tangerine, alpha = 0.10f, center = center + Offset(0f, radius * 0.25f), radiusX = radius * 0.7f)
            }
            .padding(vertical = 4.dp),
        interactive = true,
        autoRotate = true,
    )
}

/** "Snap. Solve." over a sunset "Twist.", read as one heading. */
@Composable
private fun Headline() {
    val first = stringResource(R.string.home_headline)
    val accent = stringResource(R.string.home_headline_accent)
    val style = MaterialTheme.typography.displayMedium
    Column(
        Modifier.clearAndSetSemantics {
            contentDescription = "$first $accent"
            heading()
        },
    ) {
        Text(first, style = style, color = Brand.TextPrimary)
        GradientText(accent, style = style)
    }
}

/** Three glass cards: scan, check, solve. */
@Composable
private fun HowItWorks() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StepCard(1, Icons.Rounded.CameraAlt, stringResource(R.string.home_step_scan), stringResource(R.string.home_step_scan_caption))
        StepCard(2, Icons.Rounded.AutoFixHigh, stringResource(R.string.home_step_check), stringResource(R.string.home_step_check_caption))
        StepCard(3, Icons.Rounded.ViewInAr, stringResource(R.string.home_step_solve), stringResource(R.string.home_step_solve_caption))
    }
}

@Composable
private fun RowScope.StepCard(step: Int, icon: ImageVector, title: String, caption: String) {
    val stepLabel = stringResource(R.string.home_step_number, step)
    GlassCard(
        modifier = Modifier
            .weight(1f)
            .semantics(mergeDescendants = true) {},
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(Brand.Tangerine.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Brand.Tangerine, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "0$step",
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = DisplayFont),
                color = Brand.TextTertiary,
                modifier = Modifier.semantics { contentDescription = stepLabel },
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = Brand.TextPrimary, maxLines = 1)
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = Brand.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "Try a random scramble", or a small spinner with "Mixing one up…" while it is being prepared. */
@Composable
private fun RandomScrambleAction(scrambling: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = scrambling,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentAlignment = Alignment.Center,
        modifier = modifier,
        label = "randomScramble",
    ) { busy ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (busy) {
                Row(
                    Modifier.heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Spinner(size = 18.dp, strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.home_random_busy),
                        style = MaterialTheme.typography.labelLarge,
                        color = Brand.TextSecondary,
                    )
                }
            } else {
                TextAction(
                    text = stringResource(R.string.home_random),
                    onClick = onClick,
                    icon = Icons.Rounded.Shuffle,
                )
            }
        }
    }
}
