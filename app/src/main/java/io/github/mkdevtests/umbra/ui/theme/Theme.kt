package io.github.mkdevtests.umbra.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val UmbraColors = darkColorScheme(
    primary = Color(0xFF8B7CF6),
    background = Color(0xFF0B0B0F),
    surface = Color(0xFF15151C),
    onBackground = Color(0xFFECECF1),
    onSurface = Color(0xFFECECF1),
)

/** Dark-only theme: Umbra is a video app, a light theme would fight the content. */
@Composable
fun UmbraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = UmbraColors, content = content)
}
