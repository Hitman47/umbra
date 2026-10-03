package io.github.mkdevtests.umbra.nas

import java.io.Closeable
import java.util.ArrayDeque

/** A file opened for reading on a NAS, whatever the protocol. */
interface RemoteFile : Closeable {
    val size: Long

    /** Reads up to [length] bytes at [fileOffset] into [buffer] from [bufferOffset]; -1 or 0 at the end. */
    fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int
}

/**
 * Open handles of one file, kept between the HTTP requests of mpv: it asks
 * for a new range at the opening (header, index at the end) and at each
 * seek, and opening the file on the NAS costs a round trip or more each time.
 * A handle serves one request at a time; idle ones are closed after a while.
 */
class HandlePool(private val open: () -> RemoteFile) {
    private class Idle(val file: RemoteFile, val since: Long)

    private val idle = ArrayDeque<Idle>()

    /** A handle and whether it had to be opened. */
    fun acquire(): Pair<RemoteFile, Boolean> {
        synchronized(this) { idle.pollLast() }?.let { return it.file to false }
        return open() to true
    }

    fun release(file: RemoteFile) {
        val stale = synchronized(this) {
            idle.addLast(Idle(file, System.currentTimeMillis()))
            closeStale()
        }
        stale.forEach { runCatching { it.close() } }
    }

    /** A handle that failed: closed with the idle ones, which may have failed as well (NAS reconnected). */
    fun discard(file: RemoteFile) {
        val all = synchronized(this) { idle.map { it.file }.also { idle.clear() } }
        (all + file).forEach { runCatching { it.close() } }
    }

    fun closeAll() = discard(EMPTY)

    private fun closeStale(): List<RemoteFile> {
        val limit = System.currentTimeMillis() - IDLE_MS
        val stale = idle.filter { it.since < limit || idle.size > MAX_IDLE }.map { it.file }
        idle.removeAll { it.file in stale }
        return stale
    }

    private companion object {
        const val IDLE_MS = 60_000L
        const val MAX_IDLE = 8

        val EMPTY = object : RemoteFile {
            override val size = 0L
            override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int) = -1
            override fun close() {}
        }
    }
}
