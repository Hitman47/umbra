package io.github.mkdevtests.umbra.player

import kotlin.math.abs
import kotlin.math.sign

/** Seconds of the jumps that don't ask anything: double tap, tap on ⏪ ⏩. */
const val SHORT_JUMP = 10

/**
 * How far a horizontal swipe across the picture moves, [fraction] being the
 * swipe's length over the screen's width: seconds for a short one, minutes
 * for a long one (a tenth of the width ≈ 14 s, half ≈ 3 min, all of it 10 min).
 */
fun swipeSeconds(fraction: Float): Double {
    val x = abs(fraction).coerceAtMost(1.5f).toDouble()
    return fraction.sign * (90 * x + 510 * x * x)
}

/**
 * The jump of each tick while ⏪ or ⏩ is held, [heldMs] after the press:
 * 10 s, then 30 s after 2 s, then a minute after 5 s, like a remote's fast forward.
 */
fun holdStep(heldMs: Long): Int = when {
    heldMs < 2_000 -> 10
    heldMs < 5_000 -> 30
    else -> 60
}

/** "45 s", "1 min", "1 min 30". */
fun durationLabel(seconds: Int): String {
    val s = abs(seconds)
    return when {
        s < 60 -> "$s s"
        s % 60 == 0 -> "${s / 60} min"
        else -> "${s / 60} min ${"%02d".format(s % 60)}"
    }
}
