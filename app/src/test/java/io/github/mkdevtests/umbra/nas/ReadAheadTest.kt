package io.github.mkdevtests.umbra.nas

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class ReadAheadTest {
    /** A file whose byte n is n % 251; the NAS gives less than asked, as SMB may. */
    private class FakeFile(override val size: Long) : RemoteFile {
        override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
            if (fileOffset >= size) return -1
            val count = minOf(length.toLong(), size - fileOffset, 300_000).toInt()
            for (i in 0 until count) buffer[bufferOffset + i] = ((fileOffset + i) % 251).toByte()
            return count
        }

        override fun close() {}
    }

    private fun get(url: String, from: Long, to: Long): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Range", "bytes=$from-$to")
        return connection.inputStream.use { it.readBytes() }.also { assertEquals(206, connection.responseCode) }
    }

    @Test
    fun servesRangesAheadAndReusesTheHandle() {
        val opened = AtomicInteger()
        val server = LocalStreamServer { null }
        val url = server.urlFor("key", "a.mkv") { opened.incrementAndGet(); FakeFile(5_000_000) }
        try {
            val body = get(url, 1_000_000, 3_999_999)
            assertArrayEquals(ByteArray(3_000_000) { ((1_000_000L + it) % 251).toByte() }, body)
            Thread.sleep(200) // the reading thread gives the handle back
            assertArrayEquals(ByteArray(10) { ((4_999_990L + it) % 251).toByte() }, get(url, 4_999_990, 4_999_999))
            assertEquals(1, opened.get())
            assertEquals(2, server.statsFor("key")!!.requests)
        } finally {
            server.stop()
        }
    }

    @Test
    fun aSeekCutsTheReadingAndFreesTheHandle() {
        val opened = AtomicInteger()
        val server = LocalStreamServer { null }
        val url = server.urlFor("cut", "b.mkv") { opened.incrementAndGet(); FakeFile(200_000_000) }
        try {
            // The player reads a little of a long range, then seeks: the connection is dropped.
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=0-199999999")
            connection.inputStream.use { it.readNBytes(100_000) }
            connection.disconnect()
            Thread.sleep(1_000) // the server notices, the reading thread stops and gives the handle back
            assertArrayEquals(ByteArray(10) { ((150_000_000L + it) % 251).toByte() }, get(url, 150_000_000, 150_000_009))
            assertEquals(1, opened.get())
        } finally {
            server.stop()
        }
    }
}
