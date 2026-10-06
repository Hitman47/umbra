package io.github.mkdevtests.umbra.nas

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A NAS answers or not, since [since]. */
data class NasState(val id: String, val label: String, val online: Boolean, val since: Long)

/** Why a check ran. */
enum class CheckReason(val label: String) {
    Start("ouverture"),
    Periodic("minute"),
    Network("réseau"),
    Scan("analyse"),
    Manual("demandé"),
    Confirm("confirmation"),
}

/** One check: when, why, and per NAS what happened ("Zima : OK 4 ms"). */
data class NasCheck(val at: Long, val reason: CheckReason, val results: List<String>)

/**
 * Whether each NAS answers: asked when the app comes to the screen, a few
 * seconds after the network changes, then every minute while the app is on
 * screen. Only a connection to its port: nothing is read. A NAS that
 * answered a listing or an opening in the last minute is there; one that
 * doesn't answer is said offline only once a second try, a few seconds
 * later, fails as well: a single lost connection doesn't hide its titles.
 */
class NasMonitor(scope: CoroutineScope, private val router: () -> NasRouter?) {
    private val _states = MutableStateFlow<List<NasState>>(emptyList())
    val states: StateFlow<List<NasState>> = _states.asStateFlow()

    private val _history = MutableStateFlow<List<NasCheck>>(emptyList())

    /** The last checks, newest first: shown in the NAS state's details. */
    val history: StateFlow<List<NasCheck>> = _history.asStateFlow()

    private val requests = Channel<CheckReason>(Channel.UNLIMITED)

    /** Failed tries in a row, per source id. */
    private val failures = HashMap<String, Int>()
    private var lastCheck = 0L

    /** The app is on screen: checked every minute; else not at all. */
    @Volatile
    var foreground = false
        set(value) {
            val coming = value && !field
            field = value
            if (coming) checkNow(CheckReason.Start)
        }

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                var reason = withTimeoutOrNull(PERIOD_MS) { requests.receive() } ?: CheckReason.Periodic
                // The network settles before it is tried: right after a change, connections fail for nothing.
                if (reason == CheckReason.Network) delay(NETWORK_SETTLE_MS)
                // Requests that came meanwhile are answered by this check; one asked for counts more.
                while (true) {
                    val more = requests.tryReceive().getOrNull() ?: break
                    if (more == CheckReason.Manual) reason = more
                }
                if (!foreground) continue
                val automatic = reason != CheckReason.Manual && reason != CheckReason.Start
                if (automatic && System.currentTimeMillis() - lastCheck < MIN_GAP_MS) continue
                runCatching { check(reason) }
                if (failures.values.any { it == 1 }) {
                    delay(CONFIRM_AFTER_MS)
                    runCatching { check(CheckReason.Confirm) }
                }
            }
        }
    }

    fun checkNow(reason: CheckReason = CheckReason.Manual) {
        requests.trySend(reason)
    }

    private suspend fun check(reason: CheckReason) = coroutineScope {
        val nas = router()
        val clients = nas?.connections.orEmpty()
        val now = System.currentTimeMillis()
        lastCheck = now
        val answers = clients.map { client ->
            async {
                val seen = nas?.answeredAt(client.source.id) ?: 0
                if (now - seen < RECENT_MS) {
                    Triple(client.source, null as String?, "a répondu il y a ${(now - seen) / 1000} s")
                } else {
                    val started = System.currentTimeMillis()
                    val error = client.probe()
                    Triple(client.source, error, error ?: "OK ${System.currentTimeMillis() - started} ms")
                }
            }
        }.awaitAll()
        val previous = _states.value.associateBy { it.id }
        val done = System.currentTimeMillis()
        _states.value = answers.map { (source, error, _) ->
            val tries = if (error == null) 0 else (failures[source.id] ?: 0) + 1
            failures[source.id] = tries
            val before = previous[source.id]
            // Offline from the second failure in a row; until then, as it was (online the very first time).
            val online = tries < 2 && (tries == 0 || before?.online != false)
            NasState(source.id, source.label, online, if (before?.online == online) before.since else done)
        }
        val results = answers.map { (source, error, detail) -> "${source.label} : " + if (error == null) detail else "échec · $detail" }
        _history.value = (listOf(NasCheck(now, reason, results)) + _history.value).take(HISTORY)
    }

    private companion object {
        const val PERIOD_MS = 60_000L
        const val NETWORK_SETTLE_MS = 4_000L
        const val CONFIRM_AFTER_MS = 5_000L

        /** Automatic checks closer than this are one. */
        const val MIN_GAP_MS = 10_000L

        /** A NAS that answered the app this recently needs no try. */
        const val RECENT_MS = 60_000L
        const val HISTORY = 20
    }
}
