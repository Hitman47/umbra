package io.github.mkdevtests.umbra.perso

import kotlinx.serialization.Serializable
import kotlin.random.Random

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
 * The order a Perso folder plays in, forever: in name order, back to the
 * first after the last; or shuffled, each video once per round ([played]
 * carries the round over from one session to the next), a new round when all
 * are played, never the same video twice in a row.
 */
class PersoOrder(
    private val files: List<String>,
    private val shuffle: Boolean,
    played: Collection<String> = emptyList(),
    private val random: Random = Random.Default,
) {
    private val known = files.toHashSet()

    /** The current round's videos, in the order played. */
    val played: List<String> get() = round.toList()
    private val round = played.filterTo(LinkedHashSet()) { it in known }
    private var pending = ArrayDeque<String>()
    private var last: String? = null

    init {
        require(files.isNotEmpty()) { "no video" }
        if (shuffle) pending = ArrayDeque(files.filter { it !in round }.shuffled(random))
    }

    /** [file] is playing: the order goes on from it. */
    fun playing(file: String) {
        last = file
        if (shuffle) {
            pending.remove(file)
            round += file
        }
    }

    /** The video after the last one played. */
    fun next(): String {
        val file = if (shuffle) nextShuffled() else files[(files.indexOf(last) + 1) % files.size]
        playing(file)
        return file
    }

    private fun nextShuffled(): String {
        if (pending.isEmpty()) {
            round.clear()
            val fresh = files.shuffled(random).toMutableList()
            // The new round doesn't open with the video that closed the last one.
            if (fresh.size > 1 && fresh.first() == last) fresh.add(fresh.removeAt(0))
            pending = ArrayDeque(fresh)
        }
        return pending.removeFirst()
    }
}

/**
 * The video a Perso folder starts with: the one asked, else the last one
 * played if it stopped in the middle, else in name order the one after it
 * (the first without any), else a shuffled one.
 */
fun firstOf(order: PersoOrder, files: List<String>, shuffle: Boolean, asked: String?, state: PersoFolderState, progress: (String) -> PersoProgress?): String {
    val last = state.last?.takeIf { it in files }
    val start = asked?.takeIf { it in files } ?: last?.takeIf { progress(it)?.inProgress == true }
    if (start != null) return start.also(order::playing)
    if (!shuffle && last != null) order.playing(last)
    return order.next()
}
