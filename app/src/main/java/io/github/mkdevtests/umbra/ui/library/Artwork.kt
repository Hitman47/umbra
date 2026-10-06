package io.github.mkdevtests.umbra.ui.library

import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.mkdevtests.umbra.library.Tmdb

val PosterShape = RoundedCornerShape(14.dp)

/** A grid card's usual width, in dp. */
private const val POSTER_DP = 128

/** 2:3 poster; shows the title on a plain card when TMDB has no artwork. */
@Composable
fun Poster(path: String?, title: String, modifier: Modifier = Modifier, size: String? = null) {
    val top = MaterialTheme.colorScheme.surfaceContainerHighest
    val bottom = MaterialTheme.colorScheme.surfaceContainer
    val fill = remember(top, bottom) { Brush.verticalGradient(listOf(top, bottom)) }
    // A card's width in pixels decides TMDB's picture: sharp on a dense screen or a bigger card, light otherwise.
    val density = LocalDensity.current.density
    val scale = io.github.mkdevtests.umbra.ui.theme.LocalCardScale.current
    val picture = size ?: if (POSTER_DP * scale * density > 300) "w500" else "w342"
    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .clip(PosterShape)
            .background(fill)
            .border(1.dp, Color.White.copy(alpha = 0.06f), PosterShape),
        contentAlignment = Alignment.Center,
    ) {
        if (path == null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            AsyncImage(
                model = Tmdb.image(path, picture),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Full-width backdrop fading into the page background, Infuse style. */
@Composable
fun Backdrop(path: String?, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp? = null) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier = modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier.aspectRatio(16f / 9f))) {
        AsyncImage(
            model = Tmdb.image(path, "w1280"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.55f to background.copy(alpha = 0.35f), 1f to background)),
        )
    }
}

fun formatRuntime(minutes: Int?): String? = minutes?.let { if (it >= 60) "${it / 60} h ${"%02d".format(it % 60)}" else "$it min" }

fun formatRating(rating: Double?): String? = rating?.let { "★ %.1f".format(it) }

/** A small label over artwork, like "✓ Vu" (glowing) or "3/10". */
@Composable
fun CornerBadge(text: String, modifier: Modifier = Modifier) {
    val done = text.startsWith("✓")
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp),
        color = Color.White,
        modifier = modifier
            .padding(6.dp)
            .background(if (done) io.github.mkdevtests.umbra.ui.theme.Night.Glow else Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Black.copy(alpha = 0.65f))), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
