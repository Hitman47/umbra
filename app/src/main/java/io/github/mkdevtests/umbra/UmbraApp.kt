package io.github.mkdevtests.umbra

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import io.github.mkdevtests.umbra.library.LibraryRepository
import io.github.mkdevtests.umbra.nas.LocalStreamServer
import io.github.mkdevtests.umbra.nas.SmbNas
import io.github.mkdevtests.umbra.nas.SmbSource
import io.github.mkdevtests.umbra.nas.SourceStore
import io.github.mkdevtests.umbra.update.Updater
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import kotlin.concurrent.thread

/** Process-wide objects: the NAS source, its connection, the stream server and the updater. */
class UmbraApp : Application(), SingletonImageLoader.Factory {

    val sources by lazy { SourceStore(this) }

    @Volatile
    var smb: SmbNas? = null
        private set

    val streamServer by lazy { LocalStreamServer { smb } }

    val library by lazy { LibraryRepository(this) }

    val updater by lazy { Updater(this, OkHttpClient()) }

    override fun onCreate() {
        super.onCreate()
        smb = sources.load()?.let(::SmbNas)
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

    /** Saves [source] and switches to [connection], an already verified connection to it. */
    fun useSource(source: SmbSource, connection: SmbNas) {
        sources.save(source)
        val previous = smb
        smb = connection
        // Closing logs off the NAS: network I/O, not allowed on the main thread.
        if (previous != null) thread { previous.close() }
    }
}
