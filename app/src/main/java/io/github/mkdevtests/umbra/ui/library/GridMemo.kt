package io.github.mkdevtests.umbra.ui.library

/**
 * The grids' cards and their order, kept from one visit of a tab to the next:
 * computed again only when the library, the history or a choice changed, not
 * each time the tab opens. One result per [name]; objects compare by identity
 * (the library and the history are new objects when they change), simple
 * values (choices) by equality.
 */
internal object GridMemo {
    private class Entry(val inputs: List<Any?>, val value: Any?)

    private val entries = HashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun <T> of(name: String, vararg inputs: Any?, compute: () -> T): T {
        val known = entries[name]
        if (known != null && known.inputs.size == inputs.size && inputs.indices.all { same(known.inputs[it], inputs[it]) }) return known.value as T
        return compute().also { entries[name] = Entry(inputs.toList(), it) }
    }

    private fun same(a: Any?, b: Any?) = when (b) {
        null, is Enum<*>, is String, is Number, is Boolean -> a == b
        else -> a === b
    }
}
