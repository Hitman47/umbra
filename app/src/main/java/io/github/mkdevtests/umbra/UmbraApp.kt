package io.github.mkdevtests.umbra

import android.app.Application
import io.github.mkdevtests.umbra.library.LibraryRepository
import io.github.mkdevtests.umbra.nas.LocalStreamServer
import io.github.mkdevtests.umbra.nas.SmbNas
import io.github.mkdevtests.umbra.nas.SmbSource
import io.github.mkdevtests.umbra.nas.SourceStore
import kotlin.concurrent.thread

/** Process-wide objects: the NAS source, its connection and the stream server. */
class UmbraApp : Application() {

    val sources by lazy { SourceStore(this) }

    @Volatile
    var smb: SmbNas? = null
        private set

    val streamServer by lazy { LocalStreamServer { smb } }

    val library by lazy { LibraryRepository(this) }

    override fun onCreate() {
        super.onCreate()
        smb = sources.load()?.let(::SmbNas)
    }

    /** Saves [source] and switches to [connection], an already verified connection to it. */
    fun useSource(source: SmbSource, connection: SmbNas) {
        sources.save(source)
        val previous = smb
        smb = connection
        // Closing logs off the NAS: network I/O, not allowed on the main thread.
        if (previous != null) thread { previous.close() }
    }
}
