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

    /** Subtitles downloaded for each video, added again when it plays. */
    val subtitleMemory by lazy { SubtitleMemory(this) }

    override fun onCreate() {
        super.onCreate()
        nas = sources.load().takeIf { it.isNotEmpty() }?.let { NasRouter(it.map(NasClient::of)) }
        _sourceList.value = nas?.sources.orEmpty()
        LocalImages.url = { path -> streamServer.imageUrl(path) }
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
