package io.github.mkdevtests.umbra.trakt

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.core.content.edit
import io.github.mkdevtests.umbra.nas.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

data class TraktStatus(
    val configured: Boolean = false,
    val connected: Boolean = false,
    /** Sends what is played to Trakt. */
    val scrobble: Boolean = true,
    val syncing: Boolean = false,
    /** "12/80" while the episodes of the library's shows download. */
    val syncingShows: String? = null,
    val lastSync: Long = 0,
    /** Code to enter on the Trakt site while connecting. */
    val pendingCode: TraktDeviceCode? = null,
    val error: String? = null,
    /** Asking Trakt for a code: the button waits. */
    val connecting: Boolean = false,
    /**
     * Trakt unreachable while the device has network, and Android's battery
     * optimization on for Nyxara: it cuts the app's network, the user can lift it.
     */
    val batteryBlocked: Boolean = false,
)

/** A network failure (no answer, name not resolved), not Trakt refusing a request. */
private fun Throwable.isNetwork() = this is java.io.IOException && this !is TraktHttpException

/** "Pas de réseau pour Trakt", not "Unable to resolve host api.trakt.tv: No address associated with hostname". */
private fun Throwable.traktMessage() = if (isNetwork()) "Trakt injoignable (pas de réseau pour Nyxara)" else message ?: toUserMessage()

/** What Trakt says was watched or started, by TMDB ids; a cache, downloaded again when Trakt changes. */
@Serializable
data class TraktData(
    /** "m:<tmdb>" or "e:<show tmdb>:<season>:<episode>" → watched at, ms. */
    val watched: Map<String, Long> = emptyMap(),
    /** Same keys → resume point. */
    val playback: Map<String, TraktResume> = emptyMap(),
    val activities: TraktLastActivities? = null,
    /** Watched shows by TMDB id, with the episodes once downloaded. */
    val shows: Map<Int, TraktShowState> = emptyMap(),
    /** The watchlist ("À voir"), latest added first. */
    val watchlist: List<TraktWish> = emptyList(),
)

/** A title of the watchlist, by its TMDB id. */
@Serializable
data class TraktWish(val tmdbId: Int, val isShow: Boolean, val title: String, val year: Int? = null, val listedAt: Long = 0)

/** The activities that change what was watched (the watchlist's date left out: it has its own download). */
private fun TraktLastActivities.ofWatched() = copy(
    movies = movies.copy(watchlistedAt = null), shows = shows.copy(watchlistedAt = null), episodes = episodes.copy(watchlistedAt = null),
)

private fun TraktLastActivities.watchlistStamp() = listOf(movies.watchlistedAt, shows.watchlistedAt)

/** A watched show: its episodes are downloaded again only when [lastWatchedAt] or [resetAt] moves. */
@Serializable
data class TraktShowState(
    val trakt: Int,
    val lastWatchedAt: String? = null,
    val resetAt: String? = null,
    /** "season:episode" → watched at, ms; null until downloaded. */
    val episodes: Map<String, Long>? = null,
)

@Serializable
data class TraktResume(val percent: Double, val at: Long)

fun movieKey(tmdbId: Int) = "m:$tmdbId"

private fun episodesWatched(shows: Map<Int, TraktShowState>): Map<String, Long> = buildMap {
    shows.forEach { (tmdb, show) ->
        show.episodes?.forEach { (code, at) ->
            val (season, number) = code.split(':').map(String::toInt)
            put(episodeKey(tmdb, season, number), at)
        }
    }
}

fun episodeKey(showTmdbId: Int, season: Int, number: Int) = "e:$showTmdbId:$season:$number"

/**
 * The Trakt account: connection, download of what was watched, and scrobbles
 * of what Nyxara plays. Disconnecting only forgets the account on the tablet;
 * Nyxara never removes anything on Trakt (see [TraktEndpoint]).
 */
class Trakt(private val context: Context, private val api: TraktApi, name: String = "trakt") {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // One account per profile: [name] is "trakt" for the owner's.
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "$name.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenLock = Mutex()
    private val syncLock = Mutex()
    private var connectJob: Job? = null
    /** Played in order, after the screen that sent them is gone. */
    private val scrobbles = Channel<Scrobble>(Channel.UNLIMITED)

    /** A write to Trakt; [mark]: kept on the device if the network fails (a film or episode watched). */
    private class Scrobble(val mark: PendingMark?, val run: suspend () -> Unit)

    /**
     * Watched marks Trakt couldn't get, kept on the device (the app may be
     * closed meanwhile): sent after the next sync that reaches Trakt, each
     * dropped once Trakt has it, so none is sent twice.
     */
    private val pending = MutableStateFlow(
        runCatching { json.decodeFromString<List<PendingMark>>(prefs.getString(KEY_PENDING, null) ?: "[]") }.getOrDefault(emptyList()),
    )

    private fun keepPending(marks: List<PendingMark>) = savePending { (it + marks).distinctBy(PendingMark::target) }

    private fun savePending(change: (List<PendingMark>) -> List<PendingMark>) {
        pending.update(change)
        prefs.edit { putString(KEY_PENDING, json.encodeToString(pending.value)) }
    }

    private suspend fun sendPending() {
        val token = accessToken() ?: return
        for (mark in pending.value) {
            send(TraktEndpoint.ScrobbleStop, token, mark.target, mark.percent)
            savePending { list -> list.filter { it != mark } }
            delay(1_100) // Trakt's limit: one write a second
        }
    }
    private val showIds = HashMap<Int, Int?>()
    private val episodeIds = HashMap<String, Int?>()
    /** False while Android blocks Nyxara's network: no network, or battery saver with Nyxara in the background. */
    private val online = MutableStateFlow(true)

    private val _status = MutableStateFlow(
        TraktStatus(
            configured = api.configured,
            connected = prefs.getString(KEY_REFRESH, null) != null,
            scrobble = prefs.getBoolean(KEY_SCROBBLE, true),
            lastSync = prefs.getLong(KEY_LAST_SYNC, 0),
        ),
    )
    val status: StateFlow<TraktStatus> = _status.asStateFlow()

    private val _data = MutableStateFlow(TraktData())
    val data: StateFlow<TraktData> = _data.asStateFlow()

    init {
        scope.launch {
            runCatching { if (cacheFile.exists()) _data.value = json.decodeFromString(TraktData.serializer(), cacheFile.readText()) }
                .onFailure { Log.w(TAG, "trakt cache unreadable", it) }
        }
        watchNetwork()
        scope.launch {
            for (send in scrobbles) {
                // Without network, wait until Android gives it back, then retry, in order.
                for (attempt in 1..SCROBBLE_ATTEMPTS) {
                    val result = runCatching { send.run() }
                    val error = result.exceptionOrNull() ?: break
                    val offline = error.isNetwork()
                    if (!offline || attempt == SCROBBLE_ATTEMPTS) {
                        Log.w(TAG, "scrobble failed: ${error.message}")
                        // Still no network (Nyxara in the background, battery saver): a watched mark waits for the next sync.
                        if (offline) send.mark?.let { keepPending(listOf(it)) }
                        break
                    }
                    delay(3_000L * attempt)
                    online.first { it }
                }
            }
        }
    }

    /** Follows the default network; when it comes back usable, a sync that failed meanwhile runs again. */
    private fun watchNetwork() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        val usable = HashMap<Network, Boolean>()
        fun update(network: Network, value: Boolean?) {
            synchronized(usable) {
                if (value == null) usable.remove(network) else usable[network] = value
                val now = usable.values.any { it }
                if (now && !online.value) {
                    Log.i(TAG, "network back")
                    if (_status.value.error != null) sync()
                }
                online.value = now
            }
        }
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = update(network, !blocked)
                override fun onAvailable(network: Network) = update(network, usable[network] ?: true)
                override fun onLost(network: Network) = update(network, null)
            })
        }.onFailure { Log.w(TAG, "network not followed", it) }
    }

    /** Starts the device code flow: the code to enter shows in [status] until the user approves it. */
    fun connect() {
        if (connectJob?.isActive == true || !api.configured) return
        connectJob = scope.launch {
            _status.update { it.copy(connecting = true, error = null) }
            try {
                // Android may give the network back a few seconds late: asked again before giving up.
                val code = retryOnNetwork { api.deviceCode() }
                _status.update { it.copy(pendingCode = code, error = null, connecting = false) }
                val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
                var interval = code.interval.coerceAtLeast(1) * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(interval)
                    val token = try {
                        api.deviceToken(code.deviceCode)
                    } catch (e: TraktHttpException) {
                        when (e.code) {
                            400 -> continue // not approved yet
                            429 -> { interval += 1000; continue }
                            418 -> throw IllegalStateException("Connexion refusée sur Trakt.")
                            else -> throw IllegalStateException("Code expiré, recommence.")
                        }
                    } catch (e: java.io.IOException) {
                        // No answer (network cut, app in the background): the code stays valid, asked again.
                        Log.i(TAG, "device token: ${e.message}")
                        continue
                    }
                    saveToken(token)
                    _status.update { it.copy(connected = true, pendingCode = null) }
                    sync(force = true)
                    return@launch
                }
                throw IllegalStateException("Code expiré, recommence.")
            } catch (e: CancellationException) {
                _status.update { it.copy(pendingCode = null, connecting = false) }
                throw e
            } catch (e: Exception) {
                _status.update { it.copy(pendingCode = null, connecting = false, error = e.traktMessage()) }
            }
        }
    }

    private suspend fun <T> retryOnNetwork(block: suspend () -> T): T {
        repeat(SYNC_ATTEMPTS - 1) { attempt ->
            try {
                return block()
            } catch (e: java.io.IOException) {
                if (!e.isNetwork()) throw e
                delay(SYNC_RETRY_MS * (attempt + 1))
            }
        }
        return block()
    }

    fun cancelConnect() {
        connectJob?.cancel()
    }

    /** Forgets the account on this tablet. Nothing changes on Trakt. */
    fun disconnect() {
        connectJob?.cancel()
        prefs.edit { remove(KEY_ACCESS); remove(KEY_REFRESH); remove(KEY_EXPIRES); remove(KEY_LAST_SYNC); remove(KEY_PENDING) }
        pending.value = emptyList()
        _data.value = TraktData()
        cacheFile.delete()
        _status.update { TraktStatus(configured = it.configured, scrobble = it.scrobble) }
    }

    fun setScrobble(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SCROBBLE, enabled) }
        _status.update { it.copy(scrobble = enabled) }
    }

    /** TMDB ids of the library's shows: only their episodes are downloaded. */
    @Volatile
    private var libraryShows: Set<Int> = emptySet()

    fun setLibraryShows(ids: Set<Int>) {
        if (ids == libraryShows) return
        libraryShows = ids
        sync()
    }

    /** Downloads what was watched, unless Trakt says nothing changed since the last time. */
    fun sync(force: Boolean = false) {
        if (!_status.value.connected) return
        scope.launch { syncNow(force) }
    }

    /**
     * [syncOnce], tried again while the network fails: coming back to the
     * front, Android may give Nyxara its network a few seconds late.
     */
    private suspend fun syncNow(force: Boolean) = syncLock.withLock {
        _status.update { it.copy(syncing = true, error = null, batteryBlocked = false) }
        try {
            for (attempt in 1..SYNC_ATTEMPTS) {
                try {
                    syncOnce(force)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!e.isNetwork() || attempt == SYNC_ATTEMPTS) throw e
                    Log.i(TAG, "sync attempt $attempt: ${e.message}")
                    delay(SYNC_RETRY_MS * attempt)
                    online.first { it }
                }
            }
            // Through: the watched marks that couldn't go before.
            if (pending.value.isNotEmpty()) scrobbles.trySend(Scrobble(null, ::sendPending))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync failed: ${e.message}")
            _status.update { it.copy(error = "Synchronisation impossible : ${e.traktMessage()}", batteryBlocked = e.isNetwork() && batteryOptimized()) }
        } finally {
            _status.update { it.copy(syncing = false, syncingShows = null) }
        }
    }

    /** Android's battery optimization applies to Nyxara: its network is cut whenever it leaves the screen. */
    fun batteryOptimized(): Boolean =
        context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == false

    /** The user lifted the battery optimization: the sync that failed runs again. */
    fun onBatteryExempted() {
        if (_status.value.batteryBlocked) sync()
    }

    private suspend fun syncOnce(force: Boolean) {
        val token = accessToken() ?: return
        val activities = api.lastActivities(token)
        val old = _data.value
        var data = old
        // An empty show list is a cache from before the show index: download it again.
        if (force || activities.ofWatched() != old.activities?.ofWatched() || old.shows.isEmpty()) {
            val watched = HashMap<String, Long>()
            val movies = api.watchedMovies(token)
            movies.forEach { item ->
                item.movie.ids.tmdb?.let { watched[movieKey(it)] = millis(item.lastWatchedAt) }
            }
            val shows = HashMap<Int, TraktShowState>()
            api.watchedShows(token).forEach { item ->
                val tmdb = item.show.ids.tmdb ?: return@forEach
                val trakt = item.show.ids.trakt ?: return@forEach
                val known = old.shows[tmdb]
                val same = known != null && known.lastWatchedAt == item.lastWatchedAt && known.resetAt == item.resetAt
                shows[tmdb] = TraktShowState(trakt, item.lastWatchedAt, item.resetAt, known?.episodes?.takeIf { same })
            }
            val playback = HashMap<String, TraktResume>()
            val started = api.playbackMovies(token) + api.playbackEpisodes(token)
            started.forEach { item ->
                val key = item.movie?.ids?.tmdb?.let(::movieKey)
                    ?: item.show?.ids?.tmdb?.let { show -> item.episode?.let { episodeKey(show, it.season, it.number) } }
                    ?: return@forEach
                playback[key] = TraktResume(item.progress, millis(item.pausedAt))
            }
            data = TraktData(watched, playback, activities, shows, old.watchlist)
            Log.i(TAG, "synced: ${movies.size} films, ${shows.size} shows, ${playback.size} resume points")
        }
        // The watchlist, when it changed (or never read).
        if (force || old.activities?.watchlistStamp() != activities.watchlistStamp() || (old.watchlist.isEmpty() && old.activities == null)) {
            val wishes = api.watchlist(token).mapNotNull { item ->
                val media = item.movie ?: item.show ?: return@mapNotNull null
                val tmdb = media.ids.tmdb ?: return@mapNotNull null
                TraktWish(tmdb, isShow = item.show != null, title = media.title.orEmpty(), year = media.year, listedAt = item.listedAt?.let(::millis) ?: 0)
            }.sortedByDescending { it.listedAt }
            data = data.copy(watchlist = wishes, activities = activities)
        }
        // Episodes come show by show, only for the shows of the library that changed.
        val wanted = libraryShows
        val stale = data.shows.filter { (tmdb, show) -> tmdb in wanted && show.episodes == null }
        if (stale.isNotEmpty()) {
            val shows = data.shows.toMutableMap()
            stale.entries.forEachIndexed { index, (tmdb, show) ->
                _status.update { it.copy(syncingShows = "${index + 1}/${stale.size}") }
                val progress = api.showProgress(token, show.trakt)
                // Episodes watched before a reset on Trakt don't count any more.
                val resetAt = progress.resetAt?.let(::millis) ?: 0
                val episodes = HashMap<String, Long>()
                progress.seasons.forEach { season ->
                    season.episodes.forEach { episode ->
                        val at = episode.lastWatchedAt?.let(::millis) ?: 0
                        if (episode.completed && at > resetAt) episodes["${season.number}:${episode.number}"] = at
                    }
                }
                shows[tmdb] = show.copy(episodes = episodes)
            }
            data = data.copy(shows = shows)
            Log.i(TAG, "episodes of ${stale.size} shows downloaded")
        }
        if (data !== old) {
            data = data.copy(watched = data.watched.filterKeys { it.startsWith("m:") } + episodesWatched(data.shows))
            _data.value = data
            cacheFile.writeText(json.encodeToString(TraktData.serializer(), data))
        }
        val now = System.currentTimeMillis()
        prefs.edit { putLong(KEY_LAST_SYNC, now) }
        _status.update { it.copy(lastSync = now) }
    }

    /** Sends a scrobble for [target] at [percent]; ignored when disconnected or turned off. */
    fun scrobble(endpoint: TraktEndpoint, target: TraktTarget, percent: Double) {
        val status = _status.value
        if (!status.connected || !status.scrobble) return
        // Trakt ignores a stop under 1 % (422).
        if (endpoint == TraktEndpoint.ScrobbleStop && percent < 1) return
        // A stop past 80 % marks it watched on Trakt: kept for later when the network fails.
        scrobbles.trySend(
            Scrobble(PendingMark(target, percent).takeIf { endpoint == TraktEndpoint.ScrobbleStop && percent >= 80 }) {
                val token = accessToken() ?: return@Scrobble
                send(endpoint, token, target, percent)
                if (endpoint == TraktEndpoint.ScrobbleStop) {
                    delay(3_000)
                    syncNow(force = false)
                }
            },
        )
    }

    /**
     * Marks [targets] watched on Trakt, as a player finishing them would: a
     * scrobble stop at 100 % each (Trakt adds a play), one at a time, then a sync.
     * Nothing is ever removed on Trakt: "not watched" stays on the tablet.
     */
    fun markWatched(targets: List<TraktTarget>) {
        val status = _status.value
        if (!status.connected || !status.scrobble || targets.isEmpty()) return
        scrobbles.trySend(
            Scrobble(null) {
                val token = accessToken() ?: return@Scrobble
                val missed = targets.filter { target ->
                    val failure = runCatching { send(TraktEndpoint.ScrobbleStop, token, target, 100.0) }.exceptionOrNull()
                    failure?.let { Log.w(TAG, "not marked on Trakt: ${target.label}", it) }
                    delay(1_100) // Trakt's limit: one write a second
                    failure?.isNetwork() == true
                }
                // No network: marked once Trakt answers again.
                if (missed.isNotEmpty()) keepPending(missed.map { PendingMark(it, 100.0) })
                syncNow(force = false)
            },
        )
    }

    private suspend fun send(endpoint: TraktEndpoint, token: String, target: TraktTarget, percent: Double) {
        val body = when {
            target.movieTmdb != null -> TraktScrobble(percent, movie = TraktScrobbleItem(TraktIds(tmdb = target.movieTmdb)))
            target.showTmdb != null -> {
                val id = episodeId(target.showTmdb, target.season, target.episode)
                if (id == null) {
                    Log.w(TAG, "no Trakt episode for ${target.label}, not scrobbled")
                    return
                }
                TraktScrobble(percent, episode = TraktScrobbleItem(TraktIds(trakt = id)))
            }
            else -> return
        }
        try {
            val answer = api.scrobble(endpoint, token, body)
            // What Trakt understood: action, progress and the episode or film it matched.
            val what = listOf("action", "progress", "movie", "show", "episode").mapNotNull { key -> answer[key]?.let { "$key=$it" } }
            Log.i(TAG, "${endpoint.name} ${target.label} at ${percent.toInt()} % → ${what.joinToString(" ").take(600)}")
        } catch (e: TraktHttpException) {
            if (e.code != 409) throw e // 409: just scrobbled, Trakt keeps the first one
        }
    }

    /** Trakt id of an episode, by the TMDB numbering Trakt shares; cached for the session. */
    private suspend fun episodeId(showTmdb: Int, season: Int, number: Int): Int? {
        val key = episodeKey(showTmdb, season, number)
        if (key in episodeIds) return episodeIds[key]
        val show = if (showTmdb in showIds) showIds[showTmdb] else api.showByTmdb(showTmdb).also { showIds[showTmdb] = it }
        val id = show?.let {
            try {
                api.episode(it, season, number).ids.trakt
            } catch (e: TraktHttpException) {
                if (e.code == 404) null else throw e
            }
        }
        episodeIds[key] = id
        return id
    }

    /**
     * A valid access token, refreshed when it expires within the hour. Refresh
     * tokens are single-use: the new pair is saved before anything else.
     */
    private suspend fun accessToken(): String? = tokenLock.withLock {
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return@withLock null
        val access = prefs.getString(KEY_ACCESS, null)
        if (access != null && prefs.getLong(KEY_EXPIRES, 0) - System.currentTimeMillis() > 3_600_000) return@withLock access
        try {
            api.refresh(refresh).also(::saveToken).accessToken
        } catch (e: TraktHttpException) {
            if (e.code == 400 || e.code == 401) {
                // The refresh token is dead: the user must connect again. Nothing is lost on Trakt.
                prefs.edit { remove(KEY_ACCESS); remove(KEY_REFRESH); remove(KEY_EXPIRES) }
                _status.update { it.copy(connected = false, error = "Connexion Trakt expirée : reconnecte-toi.") }
                null
            } else {
                throw e
            }
        }
    }

    private fun saveToken(token: TraktToken) {
        prefs.edit(commit = true) {
            putString(KEY_ACCESS, token.accessToken)
            putString(KEY_REFRESH, token.refreshToken)
            putLong(KEY_EXPIRES, (token.createdAt + token.expiresIn) * 1000)
        }
    }

    private fun millis(iso: String) = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0)

    private companion object {
        const val TAG = "Trakt"
        const val SCROBBLE_ATTEMPTS = 4
        const val SYNC_ATTEMPTS = 4
        const val SYNC_RETRY_MS = 2_000L
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_SCROBBLE = "scrobble"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_PENDING = "pending_marks"
    }
}

/** A watched mark waiting for the network: a scrobble stop at [percent]. */
@Serializable
data class PendingMark(val target: TraktTarget, val percent: Double)

/** What a played file is on Trakt: a film by its TMDB id, or an episode by its show's TMDB id and TMDB numbers. */
@Serializable
data class TraktTarget(
    val movieTmdb: Int? = null,
    val showTmdb: Int? = null,
    val season: Int = 0,
    val episode: Int = 0,
    /** For the log: "Dune", "Monogatari S02E03". */
    val label: String = "",
)
