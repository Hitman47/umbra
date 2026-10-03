package io.github.mkdevtests.umbra.player

import kotlin.random.Random

/** What happens after the last video of the queue, or the current one. */
enum class Repeat(val label: String) {
    None("non"),
    All("tout"),
    One("celle-ci");

    /** The next state of the button: non → tout → celle-ci → non. */
    fun next() = entries[(ordinal + 1) % entries.size]
}

/**
 * The order of a queue, by key (a NAS path): in [keys] order, or shuffled,
 * each key once per round ([played] carries a round over from one session to
 * the next), never the same twice in a row; plus the keys asked to play next
 * and the ones taken out. Nothing is copied per step: a folder of 10,000+
 * videos stays as fast as one of ten.
 */
class PlayOrder(
    val keys: List<String>,
    shuffle: Boolean,
    played: Collection<String> = emptyList(),
    var repeat: Repeat = Repeat.All,
    private val random: Random = Random.Default,
) {
    init {
        require(keys.isNotEmpty()) { "no video" }
    }

    private val position = HashMap<String, Int>(keys.size * 2).also { map -> keys.forEachIndexed { i, key -> map.putIfAbsent(key, i) } }

    /** Where the order goes on from, in [keys]: the last key that came by the order, not one asked to play next. */
    private var cursor: String? = null

    /** Keys played ahead of their turn ("Lire juste après"): the order passes over them, until it comes round again. */
    private val jumped = HashSet<String>()

    var shuffle: Boolean = shuffle
        private set

    /** The key playing. */
    var current: String? = null
        private set

    /** This shuffle round's keys, in the order played. */
    private val round = played.filterTo(LinkedHashSet()) { it in position }

    /** The rest of the round, in its random order; null: drawn when needed. */
    private var pending: ArrayDeque<String>? = null

    /** "Lire juste après", first asked first. */
    private val queued = ArrayDeque<String>()
    private val removed = HashSet<String>()

    /** The round so far, for the next session. */
    val played: List<String> get() = round.toList()

    operator fun contains(key: String) = key in position

    /** The keys still in the queue. */
    val size get() = keys.size - removed.size

    /** [key] plays now: the order goes on from it. */
    fun playing(key: String) {
        current = key
        cursor = key
        round += key
        queued.remove(key)
        pending?.remove(key)
    }

    /** The key after [current], without moving; null at the end with [Repeat.None]. */
    fun peek(): String? = upcomingSequence().firstOrNull()

    /** Moves to the key after [current]; one asked to play next leaves the order where it was. */
    fun next(): String? {
        val key = peek() ?: return null
        val asked = key in queued
        val before = cursor
        playing(key)
        if (asked) {
            cursor = before
            jumped += key
        } else if ((position[key] ?: 0) <= (before?.let(position::get) ?: -1)) {
            jumped.clear() // round the end: every key plays again
        }
        return key
    }

    /** What will play, in order: this round's rest when shuffled, up to the end (or around once with [Repeat.All]). */
    fun upcoming(): List<String> = upcomingSequence().toList()

    fun setShuffle(on: Boolean) {
        shuffle = on
        pending = null
    }

    /** A new round from now: everything again, in a new order. */
    fun reshuffle() {
        round.clear()
        current?.let(round::add)
        pending = null
    }

    /** [key] right after the current one. */
    fun playNext(key: String) {
        removed -= key
        queued.remove(key)
        queued.addFirst(key)
    }

    /** [key] out of the queue for this session. */
    fun remove(key: String) {
        removed += key
        queued.remove(key)
        pending?.remove(key)
    }

    private fun upcomingSequence(): Sequence<String> {
        val asked = queued.filter { it !in removed && it != current }
        val skip = asked.toHashSet()
        val rest = if (shuffle) shuffled() else inOrder()
        val all = asked.asSequence() + rest.filter { it !in skip && it !in removed && it != current }
        // A single video with Repeat.All: it plays again.
        val again = current?.takeIf { repeat != Repeat.None && it !in removed }
        return if (again == null) all else all.ifEmpty { sequenceOf(again) }
    }

    private fun inOrder(): Sequence<String> {
        val from = cursor?.let(position::get)?.plus(1) ?: 0
        val after = (from until keys.size).asSequence().map(keys::get)
        val before = if (repeat != Repeat.None) (0 until from).asSequence().map(keys::get) else emptySequence()
        return (after + before).filter { it !in jumped }
    }

    private fun shuffled(): Sequence<String> {
        val draw = pending?.takeIf { it.isNotEmpty() } ?: draw().also { pending = it }
        return draw.asSequence()
    }

    /** The rest of the round shuffled; a new round when it is over and the queue loops. */
    private fun draw(): ArrayDeque<String> {
        var fresh = keys.filter { it !in round && it !in removed && it != current }
        if (fresh.isEmpty() && repeat != Repeat.None) {
            round.clear()
            current?.let(round::add)
            // The new round doesn't open with the video that closed the last one.
            fresh = keys.filter { it !in removed && it != current }
        }
        return ArrayDeque(fresh.shuffled(random))
    }
}
