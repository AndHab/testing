package com.andhab.cubelens.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.core.cube.FaceletCube
import com.andhab.cubelens.core.nxn.NxNCube
import com.andhab.cubelens.core.nxn.NxNScrambler
import com.andhab.cubelens.ui.DEFAULT_CUBE_SIZE
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
import com.andhab.cubelens.ui.cube.CubeViewState
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.DisplayFont
import kotlin.random.Random

/**
 * The landing screen: a hero shot of a slowly spinning, scrambled 3D cube that can be grabbed and
 * flung, a size switch right under it (2×2 to 7×7; the hero morphs into the picked size), a punchy
 * headline, a three-step "how it works" strip and the ways in: scan, type the colors, or watch a
 * random scramble get solved. Everything enters in a quick staggered cascade.
 *
 * The hero takes whatever height the rest leaves free. On short screens the "how it works" strip
 * makes way for it, and if even then the hero would drop below a comfortable size, the page
 * scrolls instead (and the hero stops taking drags, so they scroll the page).
 *
 * @param size the picked cube size (3 for a 3×3); the hero, the size switch and the scan button
 *   follow it.
 * @param onSizeChange called with a newly picked size.
 * @param scrambling a random scramble is being prepared; its action shows progress and is disabled.
 */
@Composable
fun HomeScreen(
    onScan: () -> Unit,
    onManualEntry: () -> Unit,
    onRandomScramble: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = DEFAULT_CUBE_SIZE,
    onSizeChange: (Int) -> Unit = {},
    scrambling: Boolean = false,
) {
    val heroColors = remember(size) { heroCubeColors(size) }
    val sizeLabel = stringResource(R.string.cube_size_label, size)
    AuroraBackground(modifier.fillMaxSize()) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            val scroll = rememberScrollState()
            // maxValue is Int.MAX_VALUE until the first layout, so the hero starts out still.
            val fits = scroll.maxValue == 0
            HomeLayout(
                viewportHeight = maxHeight,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(horizontal = 24.dp),
                brand = {
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
                },
                hero = {
                    Reveal(index = 1, offset = 28.dp) {
                        HeroCube(heroColors, interactive = fits, modifier = Modifier.fillMaxSize())
                    }
                },
                picker = {
                    Reveal(index = 2) {
                        SizePicker(
                            selected = size,
                            onSelect = onSizeChange,
                            modifier = Modifier.padding(top = 2.dp, bottom = 24.dp),
                        )
                    }
                },
                intro = {
                    Column {
                        Reveal(index = 3) {
                            Column {
                                Overline(stringResource(R.string.home_overline))
                                Spacer(Modifier.height(10.dp))
                                Headline()
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Reveal(index = 4) {
                            Text(
                                text = stringResource(R.string.home_subtitle),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Brand.TextSecondary,
                            )
                        }
                    }
                },
                steps = {
                    Column {
                        Spacer(Modifier.height(20.dp))
                        Reveal(index = 5) { HowItWorks() }
                    }
                },
                actions = {
                    Column {
                        Spacer(Modifier.height(22.dp))
                        Reveal(index = 6) {
                            PrimaryButton(
                                text = stringResource(R.string.home_scan, sizeLabel),
                                onClick = onScan,
                                icon = Icons.Rounded.CameraAlt,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Reveal(index = 7) {
                            SecondaryButton(
                                text = stringResource(R.string.home_manual),
                                onClick = onManualEntry,
                                icon = Icons.Rounded.GridView,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Reveal(index = 8, modifier = Modifier.fillMaxWidth()) {
                            RandomScrambleAction(
                                scrambling = scrambling,
                                onClick = onRandomScramble,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                },
            )
        }
    }
}

/** The hero never gets smaller than this; below it, the page scrolls instead. */
private val MinHeroHeight = 200.dp

/** The "how it works" strip only stays while the hero keeps at least this much height. */
private val ComfortableHeroHeight = 240.dp

/**
 * Stacks [brand], [hero], [picker], [intro], [steps] and [actions] top to bottom, each at full
 * width. The hero gets the height the others leave free in [viewportHeight], but never less than
 * [MinHeroHeight]; [steps] are left out (not placed) when keeping them would squeeze the hero below
 * [ComfortableHeroHeight]. The result is taller than the viewport only when the smallest hero
 * doesn't fit, so the caller should let it scroll.
 */
@Composable
private fun HomeLayout(
    viewportHeight: Dp,
    brand: @Composable () -> Unit,
    hero: @Composable () -> Unit,
    picker: @Composable () -> Unit,
    intro: @Composable () -> Unit,
    steps: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        contents = listOf(brand, hero, picker, intro, steps, actions),
        modifier = modifier,
    ) { slots, constraints ->
        val (brandSlot, heroSlot, pickerSlot, introSlot, stepsSlot) = slots
        val actionsSlot = slots[5]
        val width = constraints.maxWidth
        val anyHeight = Constraints(minWidth = width, maxWidth = width)
        val above = brandSlot.map { it.measure(anyHeight) }
        val switch = pickerSlot.map { it.measure(anyHeight) }
        val below = introSlot.map { it.measure(anyHeight) }
        val stepRow = stepsSlot.map { it.measure(anyHeight) }
        val bottom = actionsSlot.map { it.measure(anyHeight) }

        val viewport = viewportHeight.roundToPx()
        val fixedHeight = (above + switch + below + bottom).sumOf { it.height }
        val stepsHeight = stepRow.sumOf { it.height }
        val showSteps = viewport - fixedHeight - stepsHeight >= ComfortableHeroHeight.roundToPx()
        val othersHeight = fixedHeight + if (showSteps) stepsHeight else 0
        val heroHeight = maxOf(MinHeroHeight.roundToPx(), viewport - othersHeight)
        val heroes = heroSlot.map { it.measure(Constraints.fixed(width, heroHeight)) }

        layout(width, othersHeight + heroHeight) {
            var y = 0
            fun stack(placeables: List<Placeable>) = placeables.forEach {
                it.place(0, y)
                y += it.height
            }
            stack(above)
            stack(heroes)
            stack(switch)
            stack(below)
            if (showSteps) stack(stepRow)
            stack(bottom)
        }
    }
}

/** The user's own scrambled cube, as scanned from their photos: a real, colorful mix. */
internal val HeroCubeColors: List<CubeColor> =
    FaceletCube.parse("DLLRURUDLBFFLRUFDDRRUFFBRDLULDLDBFUBBBFBLDDFBUULFBURRR").toColors()

/**
 * The hero cube of size [n]: the user's own scanned 3×3, or a fixed, well-mixed scramble of the
 * other sizes (the same one every time, so Home always looks the same).
 */
internal fun heroCubeColors(n: Int): List<CubeColor> =
    if (n == 3) {
        HeroCubeColors
    } else {
        val seed = HeroScrambleSeeds[n] ?: n
        NxNCube.solved(n).apply(NxNScrambler.randomMoves(n, Random(seed))).toColors()
    }

/**
 * Seeds of the hero scrambles, per size: picked so that the three faces on show are lively and well
 * mixed (many colors, few same-colored neighbors, no color dominating a face).
 */
private val HeroScrambleSeeds = mapOf(2 to 7, 4 to 19, 5 to 118, 6 to 63, 7 to 20)

/**
 * The idling hero cube, lit from behind by a soft sunset bloom; draggable when [interactive].
 * When [colors] change to another cube (e.g. another size), the cube shrinks away, swaps and pops
 * back with a springy overshoot and a quick twirl.
 */
@Composable
private fun HeroCube(colors: List<CubeColor>, interactive: Boolean, modifier: Modifier = Modifier) {
    val state = remember { CubeViewState(colors, initialYaw = -44f, initialPitch = 26f) }
    val shown = remember { Animatable(1f) }
    LaunchedEffect(colors) {
        if (state.colors == colors) return@LaunchedEffect
        shown.animateTo(0f, tween(durationMillis = 150, easing = FastOutLinearInEasing))
        state.snapTo(colors)
        var last = 0f
        shown.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) {
            // A twirl that eases out with the pop.
            state.yaw += (value - last) * MORPH_TWIRL_DEGREES
            last = value
        }
    }
    Cube3D(
        state = state,
        modifier = modifier
            .drawBehind {
                val center = Offset(size.width / 2f, size.height * 0.46f)
                val radius = size.minDimension * 0.62f
                drawSoftGlow(Brand.Magenta, alpha = 0.20f, center = center, radiusX = radius)
                drawSoftGlow(Brand.Tangerine, alpha = 0.10f, center = center + Offset(0f, radius * 0.25f), radiusX = radius * 0.7f)
            }
            .graphicsLayer {
                val t = shown.value
                val scale = 0.78f + 0.22f * t
                scaleX = scale
                scaleY = scale
                alpha = t.coerceIn(0f, 1f)
            }
            .padding(vertical = 4.dp),
        interactive = interactive,
        autoRotate = true,
    )
}

/**
 * Extra spin (degrees, the way it idles) the hero cube gets as it pops back in another size: a
 * quarter turn, so it lands on the same flattering three-quarter angle.
 */
private const val MORPH_TWIRL_DEGREES = 90f

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

/** Three glass cards of equal height: scan, check, solve. */
@Composable
private fun HowItWorks() {
    Row(
        modifier = Modifier.height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
            .fillMaxHeight()
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
        Text(title, style = MaterialTheme.typography.titleSmall, color = Brand.TextPrimary)
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = Brand.TextSecondary,
            maxLines = 2,
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
