package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.history.MatchFix
import org.junit.Assert.assertEquals
import org.junit.Test

class CorrectionsTest {
    @Test
    fun appliedOrNot() {
        val anime = Show("tmdb:1", tmdbId = 1, title = "Kakegurui", groups = listOf("folder:Anime\\Thriller\\Kakegurui"))
        val drama = Show("tmdb:2", tmdbId = 2, title = "Kakegurui", groups = listOf("title:kakegurui"))
        val library = Library(shows = listOf(anime, drama))
        val corrections = correctionsOf(
            listOf(MatchFix("folder:Anime\\Thriller\\Kakegurui", 1), MatchFix("title:kakegurui", 1), MatchFix("folder:Gone", 3)),
            library,
        )
        assertEquals(listOf("Fichiers « kakegurui »", "Gone", "Anime\\Thriller\\Kakegurui"), corrections.map { it.label })
        assertEquals(listOf(false, false, true), corrections.map { it.applied })
    }
}
