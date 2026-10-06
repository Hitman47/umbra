package io.github.mkdevtests.umbra.nas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A NAS answers or not, since [since]. */
data class NasState(val id: String, val label: String, val online: Boolean, val since: Long)

/**
 * Whether each NAS answers: asked at once when the app comes to the screen,
 * when the network or the sources change, then every minute while the app
 * is on screen. Only a connection to its port: nothing is read.
 */
class NasMonitor(scope: CoroutineScope, private val router: () -> NasRouter?) {
    private val _states = MutableStateFlow<List<NasState>>(emptyList())
    val states: StateFlow<List<NasState>> = _states.asStateFlow()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The app is on screen: checked every minute; else not at all. */
    @Volatile
    var foreground = false
        set(value) {
            val coming = value && !field
            field = value
            if (coming) checkNow()
        }

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                withTimeoutOrNull(PERIOD_MS) { wake.receive() }
                if (foreground) runCatching { check() }
            }
        }
    }

    fun checkNow() {
        wake.trySend(Unit)
    }

    /** The source ids of the NAS that don't answer. */
    val offline: Set<String> get() = _states.value.filter { !it.online }.mapTo(HashSet()) { it.id }

    private suspend fun check() = coroutineScope {
        val clients = router()?.connections.orEmpty()
        val answers = clients.map { client -> async { client.source to client.answers() } }.awaitAll()
        val previous = _states.value.associateBy { it.id }
        val now = System.currentTimeMillis()
        _states.value = answers.map { (source, online) ->
            val before = previous[source.id]
            NasState(source.id, source.label, online, if (before?.online == online) before.since else now)
        }
    }

    private companion object {
        const val PERIOD_MS = 60_000L
    }
}
