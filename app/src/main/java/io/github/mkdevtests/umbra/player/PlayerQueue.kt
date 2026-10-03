package io.github.mkdevtests.umbra.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The player's queue as the screen sees it: what plays, what comes next, what
 * played before (⏮ goes back through it), and the buttons Aléatoire,
 * Répéter, Arrêter après. Holds keys only; [itemOf] makes the item of a key
 * when it plays or shows, so a queue of 10,000+ videos costs no more than its keys.
 */
class PlayerQueue(
    val order: PlayOrder,
    private val itemOf: (String) -> PlayItem,
    /** A Perso folder: the next video starts at once, no countdown. */
    val perso: Boolean,
) {
    /** Bumped at each change: the panel reads the order again. */
    var version by mutableIntStateOf(0)
        private set

    /** The keys played in this session, in order; [position] is the one playing. */
    private val history = ArrayList<String>()
    private var position = -1

    var current by mutableStateOf<PlayItem?>(null)
        private set

    /** What plays after [current]; null at the end, or when [stopAfter]. */
    var upNext by mutableStateOf<PlayItem?>(null)
        private set

    /** Playback stops at the end of the current video; off again when another one starts. */
    var stopAfter by mutableStateOf(false)
        private set

    val shuffle get() = order.shuffle
    val repeat get() = order.repeat
    val size get() = order.size

    fun item(key: String) = itemOf(key)

    /** Starts with [key]. */
    fun begin(key: String): PlayItem {
        order.playing(key)
        push(key)
        return current!!
    }

    /** The next video: forward again through what ⏮ went back over, else the order's next; null at the end. */
    fun advance(): PlayItem? {
        stopAfter = false
        if (position < history.lastIndex) {
            position++
            current = itemOf(history[position])
        } else {
            val key = order.next() ?: return null
            push(key)
        }
        changed()
        return current
    }

    /** The video played before this one in this session; null at the first. */
    fun back(): PlayItem? {
        if (position <= 0) return null
        stopAfter = false
        position--
        current = itemOf(history[position])
        changed()
        return current
    }

    /** [key] now, from the panel; the order goes on from it. */
    fun jump(key: String): PlayItem {
        order.playing(key)
        push(key)
        return current!!
    }

    fun toggleShuffle() = change { order.setShuffle(!order.shuffle) }

    /** The walk of the folder found more ([complete]: all of it). */
    fun keysChanged(keys: List<String>, complete: Boolean) = change { order.reset(keys, complete) }

    fun cycleRepeat() = change { order.repeat = order.repeat.next() }

    fun reshuffle() = change { order.reshuffle() }

    fun playNext(key: String) = change { order.playNext(key) }

    fun remove(key: String) = change { order.remove(key) }

    fun toggleStopAfter() = change { stopAfter = !stopAfter }

    /** What comes next, in order: back over the keys ⏮ passed, then the order's. */
    fun upcoming(): List<String> = history.subList(position + 1, history.size) + order.upcoming()

    /** What played before in this session, the latest first, each once. */
    fun earlier(): List<String> = history.subList(0, position.coerceAtLeast(0)).asReversed().distinct().filter { it != currentKey }

    /** The key of [current]. */
    val currentKey get() = history.getOrNull(position)

    private fun push(key: String) {
        stopAfter = false
        // Played from the panel after going back: what was ahead in the history goes.
        while (history.lastIndex > position) history.removeAt(history.lastIndex)
        history += key
        position = history.lastIndex
        current = itemOf(key)
        changed()
    }

    private inline fun change(block: () -> Unit) {
        block()
        changed()
    }

    private fun changed() {
        val next = if (position < history.lastIndex) history[position + 1] else order.peek()
        upNext = next?.takeIf { !stopAfter }?.let(itemOf)
        version++
    }
}
