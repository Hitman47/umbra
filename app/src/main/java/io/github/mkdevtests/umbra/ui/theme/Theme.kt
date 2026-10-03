package io.github.mkdevtests.umbra.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Nyx's night: a blue-black sky, a violet to magenta glow. */
object Night {
    val Sky = Color(0xFF09080F)
    val Surface = Color(0xFF13111C)
    val SurfaceHigh = Color(0xFF1C1928)
    val SurfaceHighest = Color(0xFF26223A)
    val Violet = Color(0xFFA78BFA)
    val DeepViolet = Color(0xFF7C5CF5)
    val Magenta = Color(0xFFE36BF5)
    val Text = Color(0xFFEDEBF5)
    val TextDim = Color(0xFFA6A1BA)
    val Line = Color(0xFF2E2944)

    /** The accent: buttons, the logo, what is selected. */
    val Glow = Brush.linearGradient(listOf(DeepViolet, Magenta))
}

private val NyxaraColors = darkColorScheme(
    primary = Night.Violet,
    onPrimary = Color(0xFF1A0F3D),
    primaryContainer = Color(0xFF34265F),
    onPrimaryContainer = Color(0xFFEAE3FF),
    secondary = Night.Magenta,
    onSecondary = Color(0xFF3A0B44),
    secondaryContainer = Color(0xFF3B2150),
    onSecondaryContainer = Color(0xFFF8DDFF),
    tertiary = Color(0xFF8AB4FF),
    background = Night.Sky,
    onBackground = Night.Text,
    surface = Night.Sky,
    onSurface = Night.Text,
    surfaceVariant = Night.SurfaceHigh,
    onSurfaceVariant = Night.TextDim,
    surfaceContainerLowest = Color(0xFF07060B),
    surfaceContainerLow = Color(0xFF100E18),
    surfaceContainer = Night.Surface,
    surfaceContainerHigh = Night.SurfaceHigh,
    surfaceContainerHighest = Night.SurfaceHighest,
    outline = Color(0xFF4A4463),
    outlineVariant = Night.Line,
    error = Color(0xFFFF7A90),
)

private val NyxaraType = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(letterSpacing = 1.2.sp),
    )
}

private val NyxaraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Dark-only theme: Nyxara is a video app, a light theme would fight the content. */
@Composable
fun NyxaraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NyxaraColors, typography = NyxaraType, shapes = NyxaraShapes, content = content)
}
