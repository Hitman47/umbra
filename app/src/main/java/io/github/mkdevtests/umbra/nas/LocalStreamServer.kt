package io.github.mkdevtests.umbra.nas

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.InputStream
import io.github.mkdevtests.umbra.browse.isImageName
import java.net.URLDecoder
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
class LocalStreamServer(
    /** In the images' URLs: kept across launches, so that their cache survives, and unknown to other apps. */
    private val imageSecret: String = UUID.randomUUID().toString().replace("-", ""),
    port: Int = 0,
    private val nas: () -> NasRouter?,
) : NanoHTTPD(HOST, port) {

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

    /** The URL of the NAS image at [path] (folder.jpg…), the same at every launch when the port is free. */
    fun imageUrl(path: String): String {
        synchronized(this) { if (!isAlive) start(SOCKET_READ_TIMEOUT, true) }
        return "http://$HOST:$listeningPort/$IMAGES/$imageSecret/${URLEncoder.encode(path, "UTF-8").replace("+", "%20")}"
    }

    /** Pictures fetched elsewhere than the NAS, by key (see [remoteImageUrl]); null: none. */
    @Volatile
    var remoteImages: ((String) -> ByteArray?)? = null

    /** A stable URL for the picture of [key] from [remoteImages]: the image cache keeps it, whatever address served it. */
    fun remoteImageUrl(key: String): String {
        synchronized(this) { if (!isAlive) start(SOCKET_READ_TIMEOUT, true) }
        return "http://$HOST:$listeningPort/$REMOTE_IMAGES/$imageSecret/${URLEncoder.encode(key, "UTF-8").replace("+", "%20")}"
    }

    private fun serveRemoteImage(encoded: String): Response {
        val bytes = try {
            remoteImages?.invoke(URLDecoder.decode(encoded, "UTF-8"))
        } catch (e: Exception) {
            Log.w(TAG, "remote image", e)
            null
        } ?: return text(Response.Status.NOT_FOUND, "No image")
        return newFixedLengthResponse(Response.Status.OK, "image/webp", bytes.inputStream(), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=2592000")
        }
    }

    private fun serveImage(encoded: String): Response {
        val path = URLDecoder.decode(encoded, "UTF-8")
        if (!isImageName(path)) return text(Response.Status.FORBIDDEN, "Not an image")
        return try {
            val bytes = (nas() ?: return text(Response.Status.NOT_FOUND, "No NAS")).open(path).use { file ->
                if (file.size > MAX_IMAGE) return text(Response.Status.FORBIDDEN, "Too big")
                val buffer = ByteArray(file.size.toInt())
                var filled = 0
                while (filled < buffer.size) {
                    val count = file.read(buffer, filled.toLong(), filled, buffer.size - filled)
                    if (count <= 0) break
                    filled += count
                }
                buffer.copyOf(filled)
            }
            newFixedLengthResponse(Response.Status.OK, imageType(path), bytes.inputStream(), bytes.size.toLong()).apply {
                // The image loader keeps it on disk: the NAS is asked once.
                addHeader("Cache-Control", "max-age=2592000")
            }
        } catch (e: Exception) {
            Log.w(TAG, "image $path", e)
            text(Response.Status.NOT_FOUND, e.toUserMessage())
        }
    }

    /** The first bytes of the files about to play (next episode), read ahead: key → bytes. */
    private val heads = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > MAX_HEADS
    })

    /** Reads the start of [path] now, and keeps a handle on it: the player opens it without waiting on the NAS. */
    fun prefetch(path: String) {
        if (path in heads) return
        thread(name = "nas-prefetch", isDaemon = true) {
            val pool = pools.getOrPut(path) { HandlePool { (nas() ?: throw java.io.IOException("Aucun NAS configuré")).open(path) } }
            runCatching {
                val (file, _) = pool.acquire()
                try {
                    val bytes = ByteArray(minOf(HEAD_BYTES.toLong(), file.size).toInt())
                    var filled = 0
                    while (filled < bytes.size) {
                        val count = file.read(bytes, filled.toLong(), filled, bytes.size - filled)
                        if (count <= 0) break
                        filled += count
                    }
                    heads[path] = bytes.copyOf(filled)
                    pool.release(file)
                } catch (e: Exception) {
                    pool.discard(file)
                    throw e
                }
            }.onFailure { Log.w(TAG, "prefetch $path", it) }
        }
    }

    /** Set by tests: the plan of every request. */
    internal var forcedPlan: ReadPlan? = null

    /** Through Tailscale: bigger blocks, more of them at once. */
    private fun plan(key: String): ReadPlan = forcedPlan ?: when {
        nas()?.hostOf(key.substringAfter("nfs:"))?.let(::isTailnet) == true -> ReadPlan.REMOTE
        lowMemory -> ReadPlan.SMALL
        else -> ReadPlan.LOCAL
    }

    /** A TV, or a device with little memory for the app: less read ahead (see [ReadPlan.SMALL]). */
    var lowMemory = false

    /** Closes the handles kept open, when the player goes away. */
    fun closeIdle() {
        pools.values.forEach { it.closeAll() }
        pools.clear()
    }

    override fun serve(session: IHTTPSession): Response {
        val parts = session.uri.trimStart('/').split('/', limit = 3)
        if (parts.size == 3 && parts[0] == IMAGES) {
            return if (parts[1] == imageSecret) serveImage(parts[2]) else text(Response.Status.NOT_FOUND, "Unknown image")
        }
        if (parts.size == 3 && parts[0] == REMOTE_IMAGES) {
            return if (parts[1] == imageSecret) serveRemoteImage(parts[2]) else text(Response.Status.NOT_FOUND, "Unknown image")
        }
        val token = session.uri.trimStart('/').substringBefore('/')
        val target = files[token] ?: return text(Response.Status.NOT_FOUND, "Unknown file")

        val stat = stats.getOrPut(target.key) { StreamStats() }
        val opening = System.nanoTime()
        val (file, opened) = try {
            target.pool.acquire()
        } catch (first: Exception) {
            // Handles kept from before (connection lost meanwhile): once more, from scratch.
            Log.w(TAG, "open ${target.key} failed, retrying", first)
            target.pool.closeAll()
            try {
                target.pool.acquire()
            } catch (e: Exception) {
                Log.w(TAG, "open ${target.key} failed", e)
                lastOpenError = OpenError(target.key, describe(e))
                return text(Response.Status.INTERNAL_ERROR, e.toUserMessage())
            }
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

        // The start read ahead (next episode): served from memory, the rest from the NAS.
        val head = heads[target.key]?.takeIf { start < it.size }
        val body = if (head == null) {
            current(target.key, ReadAheadStream(file, target.pool, start, length, stat, plan(target.key)))
        } else {
            val part = minOf(length, head.size - start).toInt()
            val rest = length - part
            val tail = if (rest > 0) current(target.key, ReadAheadStream(file, target.pool, start + part, rest, stat, plan(target.key))) else ByteArray(0).inputStream().also { target.pool.release(file) }
            java.io.SequenceInputStream(java.io.ByteArrayInputStream(head, start.toInt(), part), tail)
        }
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

    /**
     * The stream of each file now being sent. The player asks for a new range
     * at each seek and lets the previous one go: stopped at once here, its
     * read-ahead freed, instead of piling up (seeks in a row) until memory runs out.
     */
    private val streaming = java.util.concurrent.ConcurrentHashMap<String, InputStream>()

    private fun current(key: String, stream: InputStream): InputStream {
        streaming.put(key, stream)?.close()
        return stream
    }

    companion object {
        private const val TAG = "LocalStreamServer"
        private const val HOST = "127.0.0.1"
        private const val IMAGES = "img"
        private const val REMOTE_IMAGES = "rimg"
        private const val MAX_IMAGE = 16L shl 20
        private const val HEAD_BYTES = 2 shl 20
        private const val MAX_HEADS = 2

        /** Port tried first, so that image URLs (and their cache) stay the same from one launch to the next. */
        private const val PREFERRED_PORT = 47913

        /** The last file the NAS refused to open, and why: for the player's "Lecture impossible". */
        @Volatile
        var lastOpenError: OpenError? = null

        /** "Le partage … : STATUS_… (SMBApiException)", causes included. */
        private fun describe(e: Throwable): String = generateSequence(e) { it.cause }.take(3)
            .joinToString(" ← ") { "${it.message ?: "?"} (${it.javaClass.simpleName})" }
            .let { e.toUserMessage() + "\n" + it }

        /** A server on [PREFERRED_PORT], or any free port when it is taken. */
        fun bound(nas: () -> NasRouter?, imageSecret: String): LocalStreamServer {
            val preferred = LocalStreamServer(imageSecret, PREFERRED_PORT, nas)
            return try {
                preferred.start(SOCKET_READ_TIMEOUT, true)
                preferred
            } catch (e: java.io.IOException) {
                Log.w(TAG, "port $PREFERRED_PORT taken", e)
                LocalStreamServer(imageSecret, nas = nas)
            }
        }

        private fun imageType(path: String) = when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

        /** Inclusive byte range for a "Range: bytes=a-b" header, whole file if absent, null if invalid. */
        private fun parseRange(header: String?, size: Long): Pair<Long, Long>? {
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

        private fun mimeType(path: String) = when (path.substringAfterLast('.').lowercase()) {
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
/** [key] could not be opened on the NAS: [message]. */
data class OpenError(val key: String, val message: String, val at: Long = System.currentTimeMillis())

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
 * How a request is read from the NAS: blocks of [block] bytes, [parallel]
 * of them asked at once. One read at a time waits a round trip per block,
 * which costs little at home but caps the rate through Tailscale (1 MiB per
 * 40 ms ≈ 200 Mbit/s, per 150 ms ≈ 50): several in flight multiply it.
 */
data class ReadPlan(val block: Int, val parallel: Int) {
    companion object {
        /**
         * At home too, reads in flight pay: one at a time read 270 Mbit/s from a
         * SMB 3.1.1 NAS on a PC, four of 2 MiB 646 (scripts/smb-bench).
         */
        val LOCAL = ReadPlan(block = 2 shl 20, parallel = 4)
        val REMOTE = ReadPlan(block = 2 shl 20, parallel = 4)

        /** A TV at home: 6 MiB ahead at most instead of 16 (its memory is short, the NAS near). */
        val SMALL = ReadPlan(block = 1 shl 20, parallel = 3)
    }
}

/**
 * Reads [length] bytes of a NAS file from [offset], [plan]'s blocks fetched
 * by several threads at once, each with its own handle ([first], then
 * handles from [pool]), and handed to the player in order. The first block
 * is small, so that the player gets its first bytes at once (it asks for a
 * new range at each seek and while it opens a file). Handles go back to
 * [pool] when their thread is done.
 */
private class ReadAheadStream(
    first: RemoteFile,
    private val pool: HandlePool,
    private val offset: Long,
    private val length: Long,
    private val stats: StreamStats,
    private val plan: ReadPlan,
) : InputStream() {
    private val lock = Object()
    private val results = HashMap<Int, Any>()
    /** Blocks to read; lowered when the file ends early. */
    @Volatile private var count = blockCount()
    private var claimed = 0
    private var consumed = 0
    @Volatile private var stopped = false
    private var current: ByteArray? = null
    private var position = 0

    init {
        repeat(plan.parallel.coerceAtMost(count)) { worker ->
            thread(name = "nas-read-$worker", isDaemon = true) { work(if (worker == 0) first else null) }
        }
        if (count == 0) pool.release(first)
    }

    private fun blockCount(): Int = when {
        length <= 0 -> 0
        length <= FIRST_BLOCK -> 1
        else -> 1 + ((length - FIRST_BLOCK + plan.block - 1) / plan.block).toInt()
    }

    private fun startOf(index: Int): Long = if (index == 0) 0 else FIRST_BLOCK + (index - 1).toLong() * plan.block

    private fun sizeOf(index: Int): Int = minOf(if (index == 0) FIRST_BLOCK.toLong() else plan.block.toLong(), length - startOf(index)).toInt()

    /** The next block to fetch, waiting while enough are ready ahead of the player; -1: nothing left. */
    private fun claim(): Int = synchronized(lock) {
        while (!stopped && claimed < count && claimed - consumed >= plan.parallel * 2) lock.wait(100)
        if (stopped || claimed >= count) -1 else claimed++
    }

    private fun work(own: RemoteFile?) {
        var file = own
        var failed = false
        try {
            while (true) {
                val index = claim()
                if (index < 0) break
                val result: Any = try {
                    // Opening another handle may fail too: the player then gets the error, not a wait.
                    val handle = file ?: pool.acquire().first.also { file = it }
                    read(handle, index)
                } catch (e: Throwable) {
                    failed = true
                    e
                }
                synchronized(lock) {
                    results[index] = result
                    // A short block: the file ends there.
                    if (result is Throwable || (result as ByteArray).size < sizeOf(index)) count = minOf(count, index + 1)
                    lock.notifyAll()
                }
                if (failed) break
            }
        } finally {
            file?.let { if (failed) pool.discard(it) else pool.release(it) }
        }
    }

    private fun read(file: RemoteFile, index: Int): ByteArray {
        val size = sizeOf(index)
        val buffer = ByteArray(size)
        val at = offset + startOf(index)
        val started = System.nanoTime()
        var filled = 0
        while (filled < size && !stopped) {
            val read = file.read(buffer, at + filled, filled, size - filled)
            if (read <= 0) break
            filled += read
        }
        stats.read(filled, System.nanoTime() - started)
        return if (filled == size) buffer else buffer.copyOf(filled)
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        var block = current
        if (block == null || position >= block.size) {
            val item = synchronized(lock) {
                if (block != null) {
                    consumed++
                    lock.notifyAll()
                }
                current = null
                while (consumed < count && results[consumed] == null && !stopped) lock.wait(100)
                if (consumed >= count || stopped) return -1
                results.remove(consumed)
            }
            if (item is Throwable) throw (item as? java.io.IOException ?: java.io.IOException(item))
            block = item as ByteArray
            if (block.isEmpty()) return -1
            current = block
            position = 0
        }
        val count = minOf(len, block.size - position)
        System.arraycopy(block, position, b, off, count)
        position += count
        return count
    }

    override fun available(): Int = current?.let { it.size - position } ?: 0

    override fun close() {
        stopped = true
        synchronized(lock) {
            results.clear()
            lock.notifyAll()
        }
    }

    private companion object {
        /** Small, to answer fast. */
        const val FIRST_BLOCK = 128 * 1024
    }
}
