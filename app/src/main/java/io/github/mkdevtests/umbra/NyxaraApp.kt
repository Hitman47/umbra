package io.github.mkdevtests.umbra

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import io.github.mkdevtests.umbra.library.LibraryRepository
import io.github.mkdevtests.umbra.player.PlaybackLog
import io.github.mkdevtests.umbra.nas.LocalStreamServer
import io.github.mkdevtests.umbra.nas.NasClient
import io.github.mkdevtests.umbra.nas.NasRouter
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.SourceStore
import io.github.mkdevtests.umbra.settings.SettingsStore
import io.github.mkdevtests.umbra.history.HiddenTitles
import io.github.mkdevtests.umbra.history.HistoryDatabase
import io.github.mkdevtests.umbra.history.MatchFixes
import io.github.mkdevtests.umbra.history.WatchHistory
import io.github.mkdevtests.umbra.update.Updater
import io.github.mkdevtests.umbra.trakt.Trakt
import io.github.mkdevtests.umbra.trakt.TraktApi
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

    val streamServer by lazy { LocalStreamServer { nas } }

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

    override fun onCreate() {
        super.onCreate()
        nas = sources.load().takeIf { it.isNotEmpty() }?.let { NasRouter(it.map(NasClient::of)) }
        _sourceList.value = nas?.sources.orEmpty()
        updater.check()
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
