package io.github.mkdevtests.umbra.subtitles

import io.github.mkdevtests.umbra.nas.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieHashTest {
    private class Bytes(private val data: ByteArray) : RemoteFile {
        override val size = data.size.toLong()
        override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
            val count = minOf(length.toLong(), size - fileOffset).toInt()
            if (count <= 0) return -1
            System.arraycopy(data, fileOffset.toInt(), buffer, bufferOffset, count)
            return count
        }
        override fun close() {}
    }

    @Test
    fun sameAsOpenSubtitlesReference() {
        // Expected value from the reference algorithm (Python, struct "<Q").
        val data = ByteArray(200_000) { ((it * 7 + 3) % 251).toByte() }
        assertEquals("e6ed0e283146465f", movieHash(Bytes(data)))
    }

    @Test
    fun tooSmall() = assertNull(movieHash(Bytes(ByteArray(1000))))
}
