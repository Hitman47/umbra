package io.github.mkdevtests.umbra.media

import io.github.mkdevtests.umbra.nas.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/** Fixtures made with ffmpeg: a 1080p "HDR10" MKV with two audio and two subtitle tracks, a 720p MP4 with its index at the end. */
class MediaInfoTest {

    private class LocalFile(file: File) : RemoteFile {
        private val raf = RandomAccessFile(file, "r")
        var reads = 0
        override val size = raf.length()
        override fun read(buffer: ByteArray, fileOffset: Long, bufferOffset: Int, length: Int): Int {
            reads++
            raf.seek(fileOffset)
            return raf.read(buffer, bufferOffset, length)
        }
        override fun close() = raf.close()
    }

    private fun fixture(name: String) = File(javaClass.getResource("/media/$name")!!.toURI())

    @Test
    fun matroska() {
        val file = LocalFile(fixture("sample.mkv"))
        val info = readMediaInfo(file, "sample.mkv")
        assertNotNull(info)
        info!!
        assertEquals(1920, info.video?.width)
        assertEquals(1080, info.video?.height)
        assertEquals("H.264", info.video?.codec)
        assertEquals("HDR10", info.video?.hdr)
        assertEquals(listOf("AC3", "AAC"), info.audio.map { it.codec })
        assertEquals(listOf(6, 2), info.audio.map { it.channels })
        assertEquals(listOf("fr", "en"), info.audio.map { it.language })
        assertEquals(listOf("SRT", "SRT"), info.subtitles.map { it.codec })
        assertEquals(listOf(false, true), info.subtitles.map { it.forced })
        assertEquals(1.0, info.duration!!, 0.2)
        assertEquals(listOf("1080p", "HDR10", "H.264", "AC3 5.1"), info.badges())
        assertEquals(listOf("VFF", "EN"), info.audioLanguages())
        assertEquals(listOf("FR", "EN forcés"), info.subtitleLanguages())
        assertEquals("1080p HDR10 · VFF, EN · ST FR, EN forcés", info.compactLine())
        assertTrue("header only: ${file.reads} reads", file.reads <= 3)
    }

    @Test
    fun mp4WithIndexAtTheEnd() {
        val info = readMediaInfo(LocalFile(fixture("sample.mp4")), "sample.mp4")!!
        assertEquals(1280, info.video?.width)
        assertEquals(720, info.video?.height)
        assertEquals("H.264", info.video?.codec)
        assertNull(info.video?.hdr)
        assertEquals(listOf("AAC"), info.audio.map { it.codec })
        assertEquals(listOf(2), info.audio.map { it.channels })
        assertEquals(listOf("fr"), info.audio.map { it.language })
        assertEquals(listOf("en"), info.subtitles.map { it.language })
        assertEquals("720p · FR · ST EN", info.compactLine())
        assertEquals(1.0, info.duration!!, 0.2)
    }

    @Test
    fun unknownFormat() {
        val temp = File.createTempFile("video", ".avi").apply { writeBytes(ByteArray(1024) { 'R'.code.toByte() }); deleteOnExit() }
        assertNull(readMediaInfo(LocalFile(temp), "video.avi"))
    }

    @Test
    fun names() {
        assertEquals("DTS-HD MA", codecName("A_DTS", "DTS-HD MA 7.1"))
        assertEquals("TrueHD Atmos", codecName("A_TRUEHD", "Français TrueHD Atmos"))
        assertEquals("E-AC3", codecName("A_EAC3", null))
        assertEquals("fr", isoLanguage("fre"))
        assertEquals("fr", isoLanguage("fr-CA"))
        assertNull(isoLanguage("und"))
        assertEquals("4K", resolutionLabel(3840, 1600))
        assertEquals("1080p", resolutionLabel(1920, 800))
    }
}
