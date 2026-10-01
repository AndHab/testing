package com.andhab.cubelens.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.andhab.cubelens.R
import com.andhab.cubelens.core.cube.CubeColor
import com.andhab.cubelens.ui.theme.Brand
import com.andhab.cubelens.ui.theme.CubeLensTheme
import com.andhab.cubelens.ui.theme.DisplayFont
import com.andhab.cubelens.ui.theme.NotationStyle
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * Renders the CubeLens design system to PNGs under app/build/outputs/roborazzi/design_*.png:
 * every component on the aurora, a hero mock, the launcher icon under common masks and the splash.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DesignGalleryScreenshotTest {

    @Test
    fun galleryActions() = capture("design_gallery_actions") {
        GalleryPage {
            TopBar(title = "Design system", onBack = {}) {
                CircleIconButton(Icons.Rounded.MoreHoriz, contentDescription = "More", onClick = {}, size = 44.dp)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))
                Overline("CubeLens UI kit")
                Spacer(Modifier.height(10.dp))
                Row {
                    Text("Sunset ", style = MaterialTheme.typography.displaySmall)
                    GradientText("on ink", style = MaterialTheme.typography.displaySmall)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Glowing pills, dark glass and springy motion, so the cube's colors stay the hero.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.TextSecondary,
                )

                Section("Actions")
                PrimaryButton("Scan my cube", onClick = {}, icon = Icons.Rounded.CameraAlt, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                SecondaryButton("Enter colors manually", onClick = {}, icon = Icons.Rounded.GridView, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton("Solving", onClick = {}, loading = true, modifier = Modifier.weight(1f))
                    PrimaryButton("Solve", onClick = {}, enabled = false, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextAction("Replay", onClick = {}, icon = Icons.Rounded.Replay)
                    Spacer(Modifier.weight(1f))
                    CircleIconButton(Icons.Rounded.FlashOn, contentDescription = "Torch", onClick = {})
                    Spacer(Modifier.width(10.dp))
                    CircleIconButton(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Undo", onClick = {}, enabled = false)
                    Spacer(Modifier.width(10.dp))
                    CircleIconButton(Icons.Rounded.CameraAlt, contentDescription = "Capture", onClick = {}, highlighted = true, size = 56.dp)
                }

                Section("Tags & progress")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("6 faces", icon = Icons.Rounded.GridView)
                    Pill("Valid", icon = Icons.Rounded.Check, color = Brand.Mint)
                    Pill("21 moves", icon = Icons.Rounded.Timer, color = Brand.Tangerine)
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepDots(count = 6, current = 2)
                    Spacer(Modifier.weight(1f))
                    Spinner()
                    Spacer(Modifier.width(12.dp))
                    Text("Solving…", style = MaterialTheme.typography.labelLarge, color = Brand.TextSecondary)
                }

                Section("Notation")
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Overline("Move 4 of 21", color = Brand.TextTertiary)
                            Spacer(Modifier.height(6.dp))
                            Text("Turn the right face back", style = MaterialTheme.typography.titleMedium)
                        }
                        GradientText("R'", style = NotationStyle.copy(fontSize = 56.sp, lineHeight = 56.sp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        MoveChip("U2", state = MoveState.Done)
                        MoveChip("L", state = MoveState.Done)
                        MoveChip("R'", state = MoveState.Current)
                        MoveChip("D")
                        MoveChip("B2")
                    }
                }
            }
        }
    }

    @Test
    fun galleryFeedback() = capture("design_gallery_feedback") {
        GalleryPage {
            TopBar(title = "Feedback", onBack = {})
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Overline("Status banners", Modifier.padding(top = 8.dp, bottom = 2.dp))
                StatusBanner(BannerKind.Success, "Looks good — let's solve it", message = "All 54 stickers check out.")
                StatusBanner(BannerKind.Warning, "Two pieces look swapped", message = "Re-scan the front face with a little more light.")
                StatusBanner(BannerKind.Error, "That face has 10 reds", message = "Tap the highlighted stickers to fix them.")
                StatusBanner(BannerKind.Info, "Hold green toward you, white on top")

                Overline("Stickers", Modifier.padding(top = 12.dp, bottom = 2.dp))
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        StickerGrid(SampleFace, Modifier.width(112.dp))
                        StickerGrid(SampleFace.mapIndexed { i, c -> if (i == 8) null else c }, Modifier.width(112.dp), highlighted = setOf(2, 6))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Front", style = MaterialTheme.typography.titleMedium)
                            Text("Glossy stickers, flagged ones glow red.", style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
                        }
                    }
                }

                Overline("Brand", Modifier.padding(top = 12.dp, bottom = 2.dp))
                GlassCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CubeMark(Modifier.size(92.dp))
                        Spacer(Modifier.width(28.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            BrandLockup(markSize = 34.dp)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                CubeMark(Modifier.size(40.dp), glow = false)
                                CubeMark(Modifier.size(28.dp), glow = false)
                                CubeMark(Modifier.size(20.dp), glow = false)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun celebration() = capture("design_celebration") {
        Frame {
            AuroraBackground(Modifier.fillMaxSize(), intensity = 1.3f) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TopBar(onBack = {}) {
                        CircleIconButton(Icons.Rounded.Share, contentDescription = "Share", onClick = {}, size = 44.dp)
                    }
                    Spacer(Modifier.weight(1f))
                    CubeMark(Modifier.size(196.dp))
                    Spacer(Modifier.height(44.dp))
                    Overline("Cube solved", color = Brand.Mint)
                    Spacer(Modifier.height(8.dp))
                    GradientText("Solved!", style = MaterialTheme.typography.displayLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "21 moves, 41 seconds.\nBack to factory fresh.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Brand.TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("21 moves", icon = Icons.Rounded.ViewInAr, color = Brand.Gold)
                        Pill("0:41", icon = Icons.Rounded.Timer, color = Brand.Mint)
                    }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton(
                        "Scan another cube",
                        onClick = {},
                        icon = Icons.Rounded.CameraAlt,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    TextAction("Replay solution", onClick = {}, icon = Icons.Rounded.Replay)
                }
                ConfettiBurst(trigger = 7)
            }
        }
    }

    @Test
    fun hero() = capture("design_hero") {
        Frame {
            AuroraBackground(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrandLockup()
                        Spacer(Modifier.weight(1f))
                        CircleIconButton(Icons.Rounded.History, contentDescription = "History", onClick = {}, size = 44.dp)
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Placeholder slot for the interactive 3D cube.
                        Box(
                            Modifier
                                .size(244.dp)
                                .border(1.dp, Brand.Hairline, HeroCardShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            CubeMark(Modifier.size(168.dp))
                        }
                    }
                    Overline("Rubik's cube solver")
                    Spacer(Modifier.height(12.dp))
                    Text("Snap. Solve.", style = MaterialTheme.typography.displayMedium)
                    GradientText("Twist.", style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Point your camera at any scrambled cube. CubeLens finds the way back and walks you through every turn.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Brand.TextSecondary,
                    )
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HowItWorksCard(1, Icons.Rounded.CameraAlt, "Scan", "Six quick snaps")
                        HowItWorksCard(2, Icons.Rounded.AutoAwesome, "Solve", "In a heartbeat")
                        HowItWorksCard(3, Icons.Rounded.ViewInAr, "Twist", "Turn by turn")
                    }
                    Spacer(Modifier.height(24.dp))
                    PrimaryButton("Scan my cube", onClick = {}, icon = Icons.Rounded.CameraAlt, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    SecondaryButton("Enter colors manually", onClick = {}, icon = Icons.Rounded.GridView, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    @Test
    fun launcherIconCircle() = capture("design_icon_circle") {
        AdaptiveIcon(CircleShape, size = IconSize)
    }

    @Test
    fun launcherIconSquircle() = capture("design_icon_squircle") {
        AdaptiveIcon(SquircleShape, size = IconSize)
    }

    @Test
    fun launcherIconShowcase() = capture("design_icon") {
        Frame {
            Column(
                Modifier
                    .size(411.dp, 420.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFF2B3440), Color(0xFF151A22))))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    AdaptiveIcon(CircleShape, size = 140.dp)
                    AdaptiveIcon(SquircleShape, size = 140.dp)
                }
                Spacer(Modifier.height(40.dp))
                // Home-screen row at the real 192px (64dp @ xxhdpi) size, next to stand-in apps.
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    LauncherCell("CubeLens") { AdaptiveIcon(CircleShape, size = IconSize) }
                    LauncherCell("CubeLens") { AdaptiveIcon(SquircleShape, size = IconSize) }
                    LauncherCell("Themed") { ThemedIcon(size = IconSize) }
                    LauncherCell("Other app") {
                        Box(
                            Modifier
                                .size(IconSize)
                                .clip(CircleShape)
                                .background(Color(0xFFE8EAED)),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun splash() = capture("design_splash") {
        Frame {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brand.Ink),
                contentAlignment = Alignment.Center,
            ) {
                // Android 12+ splash: a 240dp icon masked to a 160dp circle.
                Box(Modifier.size(160.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_splash), contentDescription = null, modifier = Modifier.requiredSize(240.dp))
                }
            }
        }
    }

    private fun capture(name: String, content: @Composable () -> Unit) {
        captureRoboImage("build/outputs/roborazzi/$name.png") { content() }
    }
}

private val IconSize: Dp = 64.dp

private val SampleFace = listOf(
    CubeColor.RED, CubeColor.WHITE, CubeColor.GREEN,
    CubeColor.ORANGE, CubeColor.GREEN, CubeColor.YELLOW,
    CubeColor.BLUE, CubeColor.GREEN, CubeColor.RED,
)

/** Freezes animations and applies the theme. */
@Composable
private fun Frame(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInspectionMode provides true) {
        CubeLensTheme(content)
    }
}

@Composable
private fun GalleryPage(content: @Composable ColumnScope.() -> Unit) {
    Frame {
        AuroraBackground(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize(), content = content)
        }
    }
}

@Composable
private fun ColumnScope.Section(title: String) {
    Spacer(Modifier.height(26.dp))
    Overline(title)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun RowScope.HowItWorksCard(step: Int, icon: ImageVector, title: String, caption: String) {
    GlassCard(Modifier.weight(1f), contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(Brand.Tangerine.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = Brand.Tangerine, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.weight(1f))
            Text(
                "0$step",
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = DisplayFont),
                color = Brand.TextTertiary,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = Brand.TextSecondary)
    }
}

/** The adaptive icon's layers (108dp canvas) masked to [shape]; [size] is the visible 72dp part. */
@Composable
private fun AdaptiveIcon(shape: Shape, size: Dp) {
    Box(Modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        val layer = Modifier.requiredSize(size * 1.5f)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = layer)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = layer)
    }
}

/** Approximates Android 13 themed icons: the monochrome layer tinted on a tonal disc. */
@Composable
private fun ThemedIcon(size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF3A4A3F)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color(0xFFCDE8D3)),
            modifier = Modifier.requiredSize(size * 1.5f),
        )
    }
}

@Composable
private fun LauncherCell(label: String, icon: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        icon()
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White, textAlign = TextAlign.Center)
    }
}

/** Superellipse (|x|^5 + |y|^5 = 1), close to the squircle mask used by many launchers. */
private val SquircleShape: Shape = GenericShape { size, _ ->
    val n = 5.0
    val steps = 120
    for (i in 0..steps) {
        val t = 2 * Math.PI * i / steps
        val c = cos(t)
        val s = sin(t)
        val x = (abs(c).pow(2 / n) * sign(c) * 0.5 + 0.5) * size.width
        val y = (abs(s).pow(2 / n) * sign(s) * 0.5 + 0.5) * size.height
        if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
    }
    close()
}
