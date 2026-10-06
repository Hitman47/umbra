package io.github.mkdevtests.umbra.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import io.github.mkdevtests.umbra.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The constellation (a play sign drawn by three stars) on the night sky, and the name. */
@Composable
fun NyxaraLogo(modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(size / 3)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF2B2147), Night.Sky))),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.ic_nyxara_mark), contentDescription = null, modifier = Modifier.size(size * 0.86f))
        }
        Text(
            "Nyxara",
            style = TextStyle(
                color = Night.Text,
                fontSize = (size.value * 0.95f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

/** The main action of a screen: a glowing pill ("▶ Lecture"). */
@Composable
fun GlowButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = NyxaraIcons.Play, enabled: Boolean = true) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(Night.Glow)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A secondary action next to [GlowButton]: same shape, glass instead of glow. */
@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A round icon button on artwork: readable on any picture. */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
    ) { Icon(icon, contentDescription = description, tint = Color.White) }
}

/** The bar of a pushed screen: back, title, actions. */
@Composable
fun ScreenTitle(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(NyxaraIcons.Back, contentDescription = "Retour") }
        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

/** A row's title, with an optional action on the right ("Tout voir ›"). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        val heading = @Composable { textModifier: Modifier ->
            Box(modifier = Modifier.size(width = 4.dp, height = 20.dp).clip(RoundedCornerShape(2.dp)).background(Night.Glow))
            // A row that opens in full: its title is a link ("Lecture en cours ›").
            Text(
                if (onClick != null) "$title ›" else title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (onClick != null) textModifier.clickable(onClick = onClick) else textModifier,
            )
        }
        if (maxWidth < 600.dp) {
            // A phone: the actions under the title, scrolling sideways, the title keeps the width.
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { heading(Modifier.weight(1f)) }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = actions,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                heading(Modifier.weight(1f))
                actions()
            }
        }
    }
}

/** A small tag: a genre, a status. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.2.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * A ring around what the remote is on (Android TV, a keyboard): a touch
 * screen never shows it. Put it before the clickable it follows.
 */
fun Modifier.focusRing(shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(14.dp)): Modifier = composed {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(3.dp, Night.Violet, shape) else Modifier)
}
