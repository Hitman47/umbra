package io.github.mkdevtests.umbra.nas

import android.net.Uri
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    private val files = ConcurrentHashMap<String, String>()

    /** What reading each NAS file cost, for the playback measures. */
    private val stats = ConcurrentHashMap<String, StreamStats>()

    /** Reading figures of [path] since [resetStats]; null if it wasn't read. */
    fun statsFor(path: String): StreamStats? = stats[path]

    fun resetStats(path: String) {
        stats.remove(path)
    }

    /** Returns the URL mpv should open for the NAS file at [path]. */
    @Synchronized
    fun urlFor(path: String): String {
        if (!isAlive) start(SOCKET_READ_TIMEOUT, true)
        val token = UUID.randomUUID().toString().replace("-", "")
        files[token] = path
        // The file name keeps its extension visible to mpv (format probing, window title).
        return "http://$HOST:$listeningPort/$token/${Uri.encode(path.substringAfterLast('\\'))}"
    }

    override fun serve(session: IHTTPSession): Response {
        val token = session.uri.trimStart('/').substringBefore('/')
        val path = files[token] ?: return text(Response.Status.NOT_FOUND, "Unknown file")
        val router = nas() ?: return text(Response.Status.SERVICE_UNAVAILABLE, "No NAS configured")

        val stat = stats.getOrPut(path) { StreamStats() }
        val opening = System.nanoTime()
        val file = try {
            router.open(path)
        } catch (e: Exception) {
            Log.w(TAG, "open $path failed", e)
            return text(Response.Status.INTERNAL_ERROR, e.toUserMessage())
        }
        stat.opened(System.nanoTime() - opening)
        val size = file.size
        stat.size = size

        val range = parseRange(session.headers["range"], size)
        if (range == null) {
            file.close()
            return text(Response.Status.RANGE_NOT_SATISFIABLE, "Bad range").apply {
                addHeader("Content-Range", "bytes */$size")
            }
        }
        val (start, end) = range
        val length = end - start + 1
        val partial = session.headers.containsKey("range")

        val body = BufferedInputStream(SmbRangeStream(file, start, length, stat), READ_SIZE)
        return newFixedLengthResponse(
            if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mimeType(path),
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

        /**
         * NanoHTTPD copies in 16 KiB chunks; reading the NAS that way would
         * cost one network round trip per 16 KiB. Buffer 1 MiB per SMB read.
         */
        const val READ_SIZE = 1 shl 20

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

    fun opened(nanos: Long) {
        requestCount.incrementAndGet()
        openNanos.addAndGet(nanos)
    }

    fun read(count: Int, nanos: Long) {
        bytes.addAndGet(count.toLong())
        readNanos.addAndGet(nanos)
    }

    /** Requests made by mpv: the first one, then one per seek or so. */
    val requests get() = requestCount.get().toInt()

    /** Mean time to open the file on the NAS, per request. */
    val openMs get() = if (requests > 0) openNanos.get() / requests / 1_000_000 else null

    /** What the NAS delivers while it is read, in Mbit/s (waits on mpv not counted). */
    val readMbps get() = readNanos.get().takeIf { it > 50_000_000 }?.let { bytes.get() * 8.0 / 1e6 / (it / 1e9) }

    val megabytes get() = bytes.get() / 1_000_000
}

/** Reads [length] bytes of an SMB file from [offset]; closing it closes the remote handle. */
private class SmbRangeStream(
    private val file: NasFile,
    private var offset: Long,
    private var remaining: Long,
    private val stats: StreamStats,
) : InputStream() {

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val started = System.nanoTime()
        val count = file.read(b, offset, off, minOf(len.toLong(), remaining).toInt())
        if (count <= 0) return -1
        stats.read(count, System.nanoTime() - started)
        offset += count
        remaining -= count
        return count
    }

    override fun close() = file.close()
}
