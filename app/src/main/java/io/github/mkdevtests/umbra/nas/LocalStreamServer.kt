package io.github.mkdevtests.umbra.nas

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import java.net.URLEncoder
import java.util.UUID
import kotlin.concurrent.thread
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * HTTP server on 127.0.0.1 that streams NAS files to mpv.
 *
 * mpv cannot read SMB itself, but it handles HTTP with byte ranges well:
 * each seek becomes a new ranged request, served here from the NAS over SMB.
 * Files are exposed under random tokens, so nothing else on the device can
 * guess a URL and browse the NAS through this server.
 */
class LocalStreamServer(private val nas: () -> NasRouter?) : NanoHTTPD(HOST, 0) {

    /** A file served under a token: [key] names it in the stats ("Films\Dune.mkv", "nfs:Films\Dune.mkv"). */
    private class Target(val key: String, val name: String, val pool: HandlePool)

    private val files = ConcurrentHashMap<String, Target>()

    /** One pool per file, shared by its tokens: a file played again reuses its handles. */
    private val pools = ConcurrentHashMap<String, HandlePool>()

    /** What reading each file cost, for the playback measures. */
    private val stats = ConcurrentHashMap<String, StreamStats>()

    /** Reading figures of [key] since [resetStats]; null if it wasn't read. */
    fun statsFor(key: String): StreamStats? = stats[key]

    fun resetStats(key: String) {
        stats.remove(key)
    }

    /** Returns the URL mpv should open for the NAS file at [path], read through the configured sources (SMB). */
    fun urlFor(path: String): String = urlFor(path, path.substringAfterLast('\\')) {
        (nas() ?: throw java.io.IOException("Aucun NAS configuré")).open(path)
    }

    /** The URL of a file opened by [open] (another protocol), known in the stats as [key]. */
    @Synchronized
    fun urlFor(key: String, name: String, open: () -> RemoteFile): String {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
        val token = UUID.randomUUID().toString().replace("-", "")
        files[token] = Target(key, name, pools.getOrPut(key) { HandlePool(open) })
        // The file name keeps its extension visible to mpv (format probing, window title).
        return "http://$HOST:$listeningPort/$token/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"
    }

    /** Closes the handles kept open, when the player goes away. */
    fun closeIdle() {
        pools.values.forEach { it.closeAll() }
        pools.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val token = session.uri.trimStart('/').substringBefore('/')
        val target = files[token] ?: return text(Response.Status.NOT_FOUND, "Unknown file")

        val stat = stats.getOrPut(target.key) { StreamStats() }
        val opening = System.nanoTime()
        val (file, opened) = try {
            target.pool.acquire()
        } catch (e: Exception) {
            Log.w(TAG, "open ${target.key} failed", e)
            return text(Response.Status.INTERNAL_ERROR, e.toUserMessage())
        }
        if (opened) stat.opened(System.nanoTime() - opening) else stat.reused()
        val size = try {
            file.size
        } catch (e: Exception) {
            target.pool.discard(file)
            return text(Response.Status.INTERNAL_ERROR, e.toUserMessage())
        }
        stat.size = size

        val range = parseRange(session.headers["range"], size)
        if (range == null) {
            target.pool.release(file)
            return text(Response.Status.RANGE_NOT_SATISFIABLE, "Bad range").apply {
                addHeader("Content-Range", "bytes */$size")
            }
        }
        val (start, end) = range
        val length = end - start + 1
        val partial = session.headers.containsKey("range")

        val body = ReadAheadStream(file, target.pool, start, length, stat)
        return newFixedLengthResponse(
            if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mimeType(target.name),
            body,
            length,
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            if (partial) addHeader("Content-Range", "bytes $start-$end/$size")
        }
    }

    private fun text(status: Response.Status, message: String) =
        newFixedLengthResponse(status, MIME_PLAINTEXT, message)

    private companion object {
        const val TAG = "LocalStreamServer"
        const val HOST = "127.0.0.1"

        /** Inclusive byte range for a "Range: bytes=a-b" header, whole file if absent, null if invalid. */
        fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
            if (header == null) return 0L to size - 1
            val spec = header.removePrefix("bytes=").substringBefore(',').trim()
            val first = spec.substringBefore('-').trim()
            val last = spec.substringAfter('-', "").trim()
            val (start, end) = when {
                first.isEmpty() -> (size - (last.toLongOrNull() ?: return null)).coerceAtLeast(0) to size - 1
                else -> (first.toLongOrNull() ?: return null) to (last.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1))
            }
            return if (start in 0..end && start < size) start to end else null
        }

        fun mimeType(path: String) = when (path.substringAfterLast('.').lowercase()) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            else -> "application/octet-stream"
        }
    }
}

/** Requests made for one file, time spent opening it on the NAS and reading from it. */
class StreamStats {
    @Volatile var size = 0L
    private val requestCount = AtomicLong()
    private val openNanos = AtomicLong()
    private val bytes = AtomicLong()
    private val readNanos = AtomicLong()

    private val openCount = AtomicLong()

    fun opened(nanos: Long) {
        requestCount.incrementAndGet()
        openCount.incrementAndGet()
        openNanos.addAndGet(nanos)
    }

    /** A request served by a handle kept from an earlier one. */
    fun reused() {
        requestCount.incrementAndGet()
    }

    fun read(count: Int, nanos: Long) {
        bytes.addAndGet(count.toLong())
        readNanos.addAndGet(nanos)
    }

    /** Requests made by mpv: the first one, then one per seek or so. */
    val requests get() = requestCount.get().toInt()

    /** Times the file was opened on the NAS: the other requests reused a handle. */
    val opens get() = openCount.get().toInt()

    /** Mean time to open the file on the NAS. */
    val openMs get() = if (opens > 0) openNanos.get() / opens / 1_000_000 else null

    /** What the NAS delivers while it is read, in Mbit/s (waits on mpv not counted). */
    val readMbps get() = readNanos.get().takeIf { it > 50_000_000 }?.let { bytes.get() * 8.0 / 1e6 / (it / 1e9) }

    val megabytes get() = bytes.get() / 1_000_000
}

/**
 * Reads [length] bytes of a NAS file from [offset] on its own thread, a few
 * blocks ahead of the player: the NAS works while the previous block is sent.
 * The first block is small, so that the player gets its first bytes at once
 * (it asks for a new range at each seek and while it opens a file).
 * The handle goes back to [pool] once the reading thread is done with it.
 */
private class ReadAheadStream(
    private val file: RemoteFile,
    private val pool: HandlePool,
    private val offset: Long,
    private val length: Long,
    private val stats: StreamStats,
) : InputStream() {
    private val blocks = ArrayBlockingQueue<Any>(AHEAD)
    @Volatile private var stopped = false
    private var current: ByteArray? = null
    private var position = 0
    private var ended = false

    init {
        thread(name = "nas-read", isDaemon = true) { fill() }
    }

    private fun fill() {
        var failed = false
        try {
            var next = offset
            var remaining = length
            var block = FIRST_BLOCK
            while (remaining > 0 && !stopped) {
                val size = minOf(block.toLong(), remaining).toInt()
                val buffer = ByteArray(size)
                val started = System.nanoTime()
                var filled = 0
                while (filled < size) {
                    val count = file.read(buffer, next + filled, filled, size - filled)
                    if (count <= 0) break
                    filled += count
                }
                if (filled == 0) break
                stats.read(filled, System.nanoTime() - started)
                put(if (filled == size) buffer else buffer.copyOf(filled))
                next += filled
                remaining -= filled
                if (filled < size) break // end of the file
                block = BLOCK
            }
            put(END)
        } catch (e: Throwable) {
            failed = true
            put(e)
        } finally {
            if (failed) pool.discard(file) else pool.release(file)
        }
    }

    /** Waits for room, unless the player went away. */
    private fun put(item: Any) {
        while (!stopped) {
            if (blocks.offer(item, 100, TimeUnit.MILLISECONDS)) return
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (ended) return -1
        var block = current
        if (block == null || position >= block.size) {
            when (val item = blocks.take()) {
                END -> {
                    ended = true
                    return -1
                }
                is Throwable -> throw (item as? java.io.IOException ?: java.io.IOException(item))
                else -> {
                    block = item as ByteArray
                    current = block
                    position = 0
                }
            }
        }
        val count = minOf(len, block.size - position)
        System.arraycopy(block, position, b, off, count)
        position += count
        return count
    }

    override fun available(): Int = current?.let { it.size - position } ?: 0

    override fun close() {
        stopped = true
        blocks.clear()
    }

    private companion object {
        val END = Any()

        /** Small, to answer fast; then [BLOCK] bytes per NAS read, [AHEAD] blocks ahead at most. */
        const val FIRST_BLOCK = 128 * 1024
        const val BLOCK = 1 shl 20
        const val AHEAD = 4
    }
}
