package io.github.mkdevtests.umbra.perso

import kotlinx.serialization.Serializable
import io.github.mkdevtests.umbra.player.PlayOrder

/** Where a Perso video stopped: its own history, never the library's nor Trakt's. */
@Serializable
data class PersoProgress(val position: Double, val duration: Double, val at: Long = 0) {
    /** Clips are short: the last tenth, or the last 10 s, counts as the end. */
    val watched get() = duration > 0 && (position >= duration * 0.9 || duration - position <= 10)
    val inProgress get() = position >= 10 && !watched
    val fraction get() = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
}

/** What a Perso folder played last, and the videos the current shuffle round already played. */
@Serializable
data class PersoFolderState(
    val last: String? = null,
    val played: List<String> = emptyList(),
)

/**
 * The video a Perso folder starts with: the one asked, else the last one
 * played if it stopped in the middle, else in name order the one after it
 * (the first without any), else a shuffled one.
 */
fun firstOf(order: PlayOrder, asked: String?, state: PersoFolderState, progress: (String) -> PersoProgress?): String {
    val last = state.last?.takeIf(order::contains)
    val start = asked?.takeIf(order::contains) ?: last?.takeIf { progress(it)?.inProgress == true }
    if (start != null) return start.also(order::playing)
    if (!order.shuffle && last != null) order.playing(last)
    return checkNotNull(order.next())
}
