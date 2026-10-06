package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Test

class KindsTest {
    @Test
    fun animeIsJapaneseAnimation() {
        assertEquals(ShowKind.Anime, Show("a", title = "A", genres = listOf("Animation", "Action"), originCountries = listOf("JP")).kind())
        assertEquals(ShowKind.Anime, Show("b", title = "B", genres = listOf("Animation"), originalLanguage = "ja").kind())
        assertEquals(ShowKind.Animation, Show("c", title = "C", genres = listOf("Animation", "Comédie"), originCountries = listOf("US")).kind())
        assertEquals(ShowKind.Series, Show("d", title = "D", genres = listOf("Drame"), originCountries = listOf("JP")).kind())
    }

    @Test
    fun documentaryFoldersLeaveTheRest() {
        val doc = Movie("Media\\Docs\\Planète.mkv", 1, title = "Planète")
        val film = Movie("Media\\Films\\Dune.mkv", 1, title = "Dune")
        val series = Show("s", title = "Cosmos", seasons = listOf(Season(1, episodes = listOf(Episode(1, 1, "Media\\Docs\\Cosmos\\S01E01.mkv", 1)))))
        val (docs, rest) = Library(movies = listOf(doc, film), shows = listOf(series)).splitDocumentaries(listOf("Media\\Docs"))
        assertEquals(listOf(doc), docs.movies)
        assertEquals(listOf("s"), docs.shows.map { it.key })
        assertEquals(listOf(film), rest.movies)
        assertEquals(emptyList<Show>(), rest.shows)
    }
}
