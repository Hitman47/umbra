package io.github.mkdevtests.umbra.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The device is a TV: driven by a remote. */
@Composable
fun isTv(): Boolean = (LocalContext.current.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/** Lists shorter than this have no index bar: a scroll is enough. */
const val INDEX_BAR_MIN_ITEMS = 30

/** The room the index bar takes at the right of a list. */
@Composable
fun indexBarWidth() = when {
    isTv() -> 44.dp
    LocalContext.current.resources.configuration.screenWidthDp >= 600 -> 28.dp
    else -> 20.dp
}

/**
 * A thin vertical bar of [labels] ("#", "A"…"Z", or decades) at the side of a
 * long list: touched or slid along, the list jumps to the first item of the
 * label under the finger (its [positions]; a label without item: the next one
 * that has some), a bubble showing the label. With a remote: ▲ ▼ move from
 * label to label, the list following. [bubble] is the bubble's text ("1990"
 * for the label "90").
 */
@Composable
fun IndexBar(
    labels: List<String>,
    positions: Map<String, Int>,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
    bubble: (String) -> String = { it },
) {
    if (labels.isEmpty()) return
    val tv = isTv()
    val wide = LocalContext.current.resources.configuration.screenWidthDp >= 600
    val width = indexBarWidth()
    val fontSize = if (tv) 13.sp else if (wide) 11.sp else 10.sp
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(1) }
    var active by remember { mutableStateOf<Int?>(null) }
    var focused by remember { mutableStateOf(false) }

    fun target(index: Int): Int? = (index until labels.size).firstNotNullOfOrNull { positions[labels[it]] }
        ?: (index downTo 0).firstNotNullOfOrNull { positions[labels[it]] }

    fun pick(index: Int) {
        val chosen = index.coerceIn(labels.indices)
        if (chosen == active) return
        active = chosen
        target(chosen)?.let(onJump)
    }

    fun at(y: Float) = (y / height * labels.size).toInt()

    // Too short for every label (a phone held sideways): one in two, the others as dots.
    val compact = with(density) { height.toDp() } / labels.size < 13.dp
    Box(modifier = modifier.width(width).fillMaxHeight()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = if (focused || active != null) 0.12f else 0.06f), RoundedCornerShape(50))
                .then(if (focused) Modifier.border(2.dp, Night.Violet, RoundedCornerShape(50)) else Modifier)
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(labels, positions) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        pick(at(down.position.y))
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull() ?: break
                            if (!change.pressed) break
                            change.consume()
                            pick(at(change.position.y))
                        }
                        active = null
                    }
                }
                .onFocusChanged {
                    focused = it.isFocused
                    if (!it.isFocused) active = null
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> { pick((active ?: 1) - 1); true }
                        Key.DirectionDown -> { pick((active ?: -1) + 1); true }
                        else -> false
                    }
                }
                .focusable()
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            labels.forEachIndexed { index, label ->
                val present = label in positions
                Text(
                    if (compact && index % 2 == 1) "•" else label,
                    fontSize = fontSize,
                    lineHeight = fontSize,
                    fontWeight = if (index == active) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = when {
                        index == active -> Color.White
                        present -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                )
            }
        }
        active?.let { index ->
            val size = if (tv) 88.dp else if (wide) 80.dp else 64.dp
            val y = with(density) { ((index + 0.5f) / labels.size * height).toDp() } - size / 2
            Box(
                modifier = Modifier
                    .offset { IntOffset(-(size + 12.dp).roundToPx(), y.roundToPx()) }
                    .size(size)
                    .background(Night.DeepViolet, RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomStartPercent = 50, bottomEndPercent = 10)),
                contentAlignment = Alignment.Center,
            ) {
                val text = bubble(labels[index])
                Text(text, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = if (text.length > 2) 22.sp else 36.sp)
            }
        }
    }
}
