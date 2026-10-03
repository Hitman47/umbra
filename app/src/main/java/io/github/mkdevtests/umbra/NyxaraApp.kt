package io.github.mkdevtests.umbra

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import io.github.mkdevtests.umbra.library.LibraryRepository
import io.github.mkdevtests.umbra.library.LocalImages
import io.github.mkdevtests.umbra.player.PlaybackLog
import io.github.mkdevtests.umbra.nas.LocalStreamServer
import io.github.mkdevtests.umbra.nas.NasClient
import io.github.mkdevtests.umbra.nas.NasRouter
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.withRoots
import io.github.mkdevtests.umbra.nas.SourceStore
import io.github.mkdevtests.umbra.settings.SettingsStore
import io.github.mkdevtests.umbra.history.HiddenTitles
import io.github.mkdevtests.umbra.history.HistoryDatabase
import io.github.mkdevtests.umbra.history.MatchFixes
import io.github.mkdevtests.umbra.history.WatchHistory
import io.github.mkdevtests.umbra.update.Updater
import io.github.mkdevtests.umbra.trakt.Trakt
import io.github.mkdevtests.umbra.trakt.TraktApi
import io.github.mkdevtests.umbra.media.MediaInfoStore
import io.github.mkdevtests.umbra.nas.measureNetwork
import io.github.mkdevtests.umbra.subtitles.OpenSubtitles
import io.github.mkdevtests.umbra.subtitles.SubtitleMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import kotlin.concurrent.thread

/** Process-wide objects: the NAS sources, their connections, the stream server and the updater. */
class NyxaraApp : Application(), SingletonImageLoader.Factory {

    val sources by lazy { SourceStore(this) }

    val settings by lazy { SettingsStore(this) }

    /** Every NAS source behind one tree; null until one is set up. */
    @Volatile
    var nas: NasRouter? = null
        private set

    private val _sourceList = MutableStateFlow<List<NasSource>>(emptyList())

    /** The sources, for the screens that list them. */
    val sourceList: StateFlow<List<NasSource>> = _sourceList.asStateFlow()

    val streamServer by lazy { LocalStreamServer.bound({ nas }, imageSecret()) }

    /** Part of the NAS images' URLs, the same at each launch so that their cache survives; never shared. */
    private fun imageSecret(): String {
        val prefs = getSharedPreferences("server", MODE_PRIVATE)
        return prefs.getString("image_secret", null)
            ?: java.util.UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("image_secret", it).apply() }
    }

    val library by lazy { LibraryRepository(this) }

    val updater by lazy { Updater(this, OkHttpClient()) }

    /** What the last playbacks cost to open, seek and play. */
    val measures by lazy { PlaybackLog(filesDir.resolve("playback-measures.json")) }

    /** The user's own data (history, match corrections), kept apart from the library cache. */
    val userData by lazy { HistoryDatabase.open(this) }

    val history by lazy { WatchHistory(userData.dao()) }

    val matchFixes by lazy { MatchFixes(userData.matchFixes()) }
    val hidden by lazy { HiddenTitles(userData.hidden()) }
    val trakt by lazy {
        Trakt(this, TraktApi(BuildConfig.TRAKT_CLIENT_ID, BuildConfig.TRAKT_CLIENT_SECRET, "Nyxara/${BuildConfig.VERSION_NAME}", OkHttpClient()))
    }

    /** Long-lived work of the app (media headers, subtitles), not tied to a screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Quality, languages and subtitles of the video files, read from their headers. */
    val mediaInfo by lazy { MediaInfoStore(filesDir.resolve("media-info.json"), { nas }, scope) }

    val openSubtitles by lazy { OpenSubtitles(this, BuildConfig.OPENSUBTITLES_KEY, "Nyxara v${BuildConfig.VERSION_NAME}", OkHttpClient()) }

    /** Videos copied to the device, played from there even without the NAS. */
    val downloads by lazy { io.github.mkdevtests.umbra.download.Downloads(this) }

    /** Subtitles downloaded for each video, added again when it plays. */
    val subtitleMemory by lazy { SubtitleMemory(this) }

    override fun onCreate() {
        super.onCreate()
        nas = sources.load().takeIf { it.isNotEmpty() }?.let { NasRouter(it.map(NasClient::of)) }
        _sourceList.value = nas?.sources.orEmpty()
        LocalImages.url = { path -> streamServer.imageUrl(path) }
        watchNetwork()
        updater.check()
    }

    /**
     * Away from home: the NAS of [path] answers through Tailscale, or the
     * device is on mobile data. Lighter versions and a deeper cache then.
     */
    fun isAway(path: String? = null): Boolean {
        val host = path?.let { nas?.hostOf(it) } ?: nas?.connections?.firstOrNull()?.currentHost
        if (host != null && io.github.mkdevtests.umbra.nas.isTailnet(host)) return true
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** Reads a big film of the library as playback would: round trip, rate, and what they allow. */
    suspend fun testNetwork(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val router = nas ?: return@withContext "Aucun NAS configuré."
        val movie = library.library.value.movies.maxByOrNull { it.fileSize } ?: return@withContext "Aucun film dans la bibliothèque pour le test."
        runCatching {
            val report = measureNetwork(router, movie.file, streamServer.urlFor(movie.file))
            "${report.summary}. ${report.verdict}"
        }.getOrElse { "Test impossible : ${it.message ?: it}" }
    }

    /** The address of each NAS is chosen again as soon as the device changes networks, not at the next failure. */
    private fun watchNetwork() {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            private var last: android.net.Network? = null

            override fun onAvailable(network: android.net.Network) {
                if (last != null && last != network) nas?.onNetworkChanged()
                last = network
            }

            override fun onLost(network: android.net.Network) {
                nas?.onNetworkChanged()
            }
        })
    }

    /** Posters and backdrops are fetched from TMDB once, then kept on disk up to 1 GB. */
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(1L shl 30)
                .build()
        }
        .build()

    /** What the player opens for the NAS file at [path]: the WebDAV URL itself, else the local server's. */
    fun playUrl(path: String): String = nas?.directUrl(path) ?: streamServer.urlFor(path)

    /** Adds [source], or replaces the one with its id, using [connection], an already verified connection to it. */
    @Synchronized
    fun saveSource(source: NasSource, connection: NasClient) {
        val kept = nas?.connections.orEmpty().filter { it.source.id != source.id }
        val index = nas?.connections?.indexOfFirst { it.source.id == source.id } ?: -1
        val connections = kept.toMutableList().apply { add(if (index >= 0) index else size, connection) }
        switchTo(connections)
    }

    /** NAS restored from a backup, without their passwords: they connect once these are typed. */
    @Synchronized
    fun addSources(added: List<NasSource>) {
        val existing = nas?.connections.orEmpty()
        switchTo(existing + added.map { it.withRoots(existing.map { c -> c.source }) }.map(NasClient::of))
    }

    val backups by lazy { io.github.mkdevtests.umbra.history.BackupManager(this) }

    @Synchronized
    fun removeSource(id: String) = switchTo(nas?.connections.orEmpty().filter { it.source.id != id })

    private fun switchTo(connections: List<NasClient>) {
        sources.save(connections.map { it.source })
        val dropped = nas?.connections.orEmpty().filter { it !in connections }
        nas = connections.takeIf { it.isNotEmpty() }?.let(::NasRouter)
        _sourceList.value = nas?.sources.orEmpty()
        // Closing logs off the NAS: network I/O, not allowed on the main thread.
        if (dropped.isNotEmpty()) thread { dropped.forEach { it.close() } }
    }
}
