package io.github.mkdevtests.umbra.nas

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcesTest {
    private val salon = SmbSource("192.168.1.20", listOf("Films", "Media"), id = "a", name = "Zima salon")

    @Test
    fun sharesKeepTheirNameUnlessAnotherNasHasIt() {
        val bureau = SmbSource("zima-2", listOf("media", "Animes"), id = "b", name = "Zima bureau").withRoots(listOf(salon))
        assertEquals(mapOf("media" to "media (Zima bureau)"), bureau.roots)
        assertEquals("Animes", bureau.rootOf("Animes"))

        val router = NasRouter(listOf(SmbNas(salon), SmbNas(bureau)))
        assertEquals(listOf("Animes", "Films", "Media", "media (Zima bureau)"), router.list("").map { it.path })
        assertEquals("b", router.sourceOf("media (Zima bureau)\\Séries\\x.mkv")?.id)
        assertEquals("a", router.sourceOf("MEDIA\\Films")?.id)
    }

    @Test
    fun anEditedSourceKeepsItsRoots() {
        val bureau = SmbSource("zima-2", listOf("Media"), id = "b", roots = mapOf("Media" to "Media (zima-2)"))
        // Renamed: the root stays, so its paths, history and corrections stay valid.
        assertEquals(mapOf("Media" to "Media (zima-2)"), bureau.copy(name = "Bureau").withRoots(listOf(salon)).roots)
        // Alone again: still the same root.
        assertEquals(mapOf("Media" to "Media (zima-2)"), bureau.withRoots(emptyList()).roots)
    }

    @Test
    fun suffixesStayUnique() {
        val second = SmbSource("x", listOf("Media"), id = "b").withRoots(listOf(salon, SmbSource("y", listOf("Media (x)"), id = "c")))
        assertEquals(mapOf("Media" to "Media (x 2)"), second.roots)
    }

    @Test
    fun excludedFoldersAndWhatTheyHold() {
        val source = salon.copy(excluded = listOf("Media\\Photos"))
        assertEquals(true, source.isExcluded("Media\\Photos"))
        assertEquals(true, source.isExcluded("media\\photos\\2019\\x.mp4"))
        assertEquals(false, source.isExcluded("Media\\Photos 2019"))
        assertEquals(false, source.isExcluded("Media"))
        assertEquals(true, NasRouter(listOf(SmbNas(source))).isExcluded("Media\\Photos\\a.mkv"))
    }
}
