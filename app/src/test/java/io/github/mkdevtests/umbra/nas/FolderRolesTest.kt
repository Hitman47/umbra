package io.github.mkdevtests.umbra.nas

import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Season
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.library.available
import io.github.mkdevtests.umbra.library.unavailableKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderRolesTest {
    private val nas = NasSource(host = "zima", shares = listOf("Films"), id = "z")

    @Test
    fun aFolderOfAShareNotReadComesInAlone() {
        val change = withRole(nas, emptyList(), "Videos", "Docs", FolderRole.Documentaries, emptyList(), siblings = listOf("Clips", "Sport"))!!
        assertEquals(listOf("Films", "Videos"), change.source.shares)
        assertEquals(listOf("Videos\\Clips", "Videos\\Sport"), change.source.excluded)
        assertEquals(listOf("Videos\\Docs"), change.documentaries)
        assertEquals(FolderRole.Documentaries, roleOf(change.source, "Videos", "Docs", change.documentaries))
        assertEquals(FolderRole.Off, roleOf(change.source, "Videos", "Clips", change.documentaries))
        assertEquals(FolderRole.Documentaries, roleOf(change.source, "Videos", "Docs\\Nature", change.documentaries))
    }

    @Test
    fun rolesReplaceEachOther() {
        val docs = withRole(nas, emptyList(), "Films", "Docus", FolderRole.Documentaries, emptyList())!!
        val perso = withRole(docs.source, emptyList(), "Films", "Docus", FolderRole.Personal, docs.documentaries)!!
        assertEquals(emptyList<String>(), perso.documentaries)
        assertEquals(listOf("Films\\Docus"), perso.source.personal)
        val back = withRole(perso.source, emptyList(), "Films", "Docus", FolderRole.Library, perso.documentaries)!!
        assertEquals(FolderRole.Library, roleOf(back.source, "Films", "Docus", back.documentaries))
        val off = withRole(back.source, emptyList(), "Films", "Docus", FolderRole.Off, back.documentaries)!!
        assertEquals(listOf("Films\\Docus"), off.source.excluded)
        // The last share is removed from Réglages › Sources, not here; a share not read can't be left out.
        assertNull(withRole(nas, emptyList(), "Films", "", FolderRole.Off, emptyList()))
        assertNull(withRole(nas, emptyList(), "Autre", "", FolderRole.Off, emptyList()))
        assertTrue(looksLikeDocumentaries("Documentaires") && looksLikeDocumentaries("docus") && !looksLikeDocumentaries("Docs perso"))
    }

    @Test
    fun aNasThatDoesNotAnswerLeavesTheListsButNotItsDownloads() {
        val library = Library(
            movies = listOf(Movie(file = "Z2\\Akira.mkv", fileSize = 1, title = "Akira"), Movie(file = "Z1\\Dune.mkv", fileSize = 1, title = "Dune")),
            shows = listOf(
                Show(key = "s", title = "Dark", seasons = listOf(Season(1, episodes = listOf(Episode(1, 1, "Z1\\Dark\\E01.mkv", 1), Episode(1, 2, "Z2\\Dark\\E02.mkv", 1))))),
                Show(key = "t", title = "Cosmos", seasons = listOf(Season(1, episodes = listOf(Episode(1, 1, "Z2\\Cosmos\\E01.mkv", 1))))),
            ),
        )
        val unavailable = { file: String -> file.startsWith("Z2\\") && file != "Z2\\Cosmos\\E01.mkv" }
        val shown = library.available(unavailable)
        assertEquals(listOf("Dune"), shown.movies.map { it.title })
        assertEquals(listOf(1, 1), shown.shows.map { show -> show.seasons.sumOf { it.episodes.size } })
        assertEquals(setOf("Z2\\Akira.mkv"), library.unavailableKeys(unavailable))
    }
}

class SectionRolesTest {
    @Test
    fun theNamesOfSections() {
        org.junit.Assert.assertEquals(FolderRole.Spectacles, sectionOfName("Spectacles"))
        org.junit.Assert.assertEquals(FolderRole.Spectacles, sectionOfName("Stand-up"))
        org.junit.Assert.assertEquals(FolderRole.Spectacles, sectionOfName("One Man Show"))
        org.junit.Assert.assertEquals(FolderRole.Concerts, sectionOfName("Concerts"))
        org.junit.Assert.assertEquals(FolderRole.Documentaries, sectionOfName("Documentaires"))
        org.junit.Assert.assertEquals(null, sectionOfName("Films"))
    }

    @Test
    fun aSpectaclesFolderTakesItsRole() {
        val nas = NasSource("nas", listOf("Videos"), id = "a")
        val change = withRole(nas, emptyList(), "Videos", "Spectacles", FolderRole.Spectacles, Sections())!!
        org.junit.Assert.assertEquals(listOf("Videos\\Spectacles"), change.sections.spectacles)
        org.junit.Assert.assertEquals(FolderRole.Spectacles, roleOf(change.source, "Videos", "Spectacles\\Artiste", change.sections))
        // Back to the library: out of the section.
        val back = withRole(change.source, emptyList(), "Videos", "Spectacles", FolderRole.Library, change.sections)!!
        org.junit.Assert.assertEquals(emptyList<String>(), back.sections.spectacles)
    }
}
