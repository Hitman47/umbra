package io.github.mkdevtests.umbra.bench

import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchTest {
    private val library = Library(
        movies = listOf("Dune (2021)\\Dune.mkv", "Heat.mkv", "Alien.mkv").map { Movie("Vidéos\\Films\\$it", 1, title = it.substringBefore('.')) } +
            Movie("Vidéos\\Docs\\Planète.mkv", 1, title = "Planète"),
    )

    @Test
    fun filesOfTheFolderOnly() {
        val files = benchFiles(library, "Vidéos\\Films", 5, seed = 1)
        assertEquals(3, files.size)
        assertEquals(true, files.all { it.path.startsWith("Vidéos\\Films\\") })
        assertEquals("Dune (2021)\\Dune.mkv", relativePath("Vidéos\\Films\\Dune (2021)\\Dune.mkv", "Vidéos\\Films\\"))
        assertEquals(listOf("Vidéos" to 4, "Vidéos\\Films" to 3), videoFolders(library))
    }

    @Test
    fun webdavUrlsAreEncodedOnce() {
        val expected = "http://math%C3%AFeu:p%40ss%3Aw@192.168.1.131:5005/media/sdb1/Vid%C3%A9os/Films/Dune%20(2021)/Dune.mkv"
        assertEquals(expected, webdavUrl("http://192.168.1.131:5005/media/sdb1/Vidéos/Films/", "Dune (2021)\\Dune.mkv", "mathïeu", "p@ss:w"))
        assertEquals(expected, webdavUrl("http://192.168.1.131:5005/media/sdb1/Vid%C3%A9os/Films", "Dune (2021)\\Dune.mkv", "mathïeu", "p@ss:w"))
        assertEquals("https://nas:5006/Films/a%23b.mkv", webdavUrlWithoutAccount("https://nas:5006/Films", "a#b.mkv"))
    }

    @Test
    fun protocolsTakeTurnsFirst() {
        val files = listOf(BenchFile("a", "A"), BenchFile("b", "B"))
        val order = benchOrder(files, Protocol.entries)
        assertEquals(listOf(Protocol.Smb, Protocol.WebDav, Protocol.Nfs, Protocol.WebDav, Protocol.Nfs, Protocol.Smb), order.map { it.second })
    }
}
