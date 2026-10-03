package io.github.mkdevtests.umbra.subtitles

import io.github.mkdevtests.umbra.nas.RemoteFile

/**
 * OpenSubtitles' hash of a video file: its size plus the 64-bit little-endian
 * words of its first and last 64 KiB. Two reads, whatever the file's size;
 * it finds subtitles timed for this very release.
 */
fun movieHash(file: RemoteFile): String? {
    val size = file.size
    if (size < CHUNK) return null
    var hash = size
    for (offset in listOf(0L, size - CHUNK)) {
        val buffer = ByteArray(CHUNK)
        var filled = 0
        while (filled < CHUNK) {
            val read = file.read(buffer, offset + filled, filled, CHUNK - filled)
            if (read <= 0) return null
            filled += read
        }
        for (i in 0 until CHUNK step 8) {
            var word = 0L
            for (b in 7 downTo 0) word = (word shl 8) or (buffer[i + b].toLong() and 0xFF)
            hash += word
        }
    }
    return "%016x".format(hash)
}

private const val CHUNK = 64 * 1024
