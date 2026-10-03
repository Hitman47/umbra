package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionsTest {
    private fun movie(file: String, title: String, year: Int, saga: Int? = null) =
        Movie(file = file, fileSize = 1, title = title, year = year, sagaId = saga, saga = saga?.let { "Saga $it" }, sagaPoster = saga?.let { "/s$it.jpg" })

    private val library = Library(
        movies = listOf(
            movie("Films\\Dune 2.mkv", "Dune 2", 2024, 5),
            movie("Films\\Dune.mkv", "Dune", 2021, 5),
            movie("Films\\Arrival.mkv", "Arrival", 2016, 9),
            movie("Animation\\Toy Story.mkv", "Toy Story", 1995),
        ),
        shows = listOf(
            Show(key = "a", title = "Frieren", seasons = listOf(Season(1, episodes = listOf(Episode(1, 1, "Anime\\Frieren\\E01.mkv", 1))))),
            Show(key = "b", title = "Dark", seasons = listOf(Season(1, episodes = listOf(Episode(1, 1, "Séries\\Dark\\E01.mkv", 1))))),
        ),
    )

    @Test
    fun sagasWithTwoFilmsOrMore() {
        val sagas = sagasOf(library)
        assertEquals(listOf(5), sagas.map { it.id })
        assertEquals(listOf("Dune", "Dune 2"), sagas.single().movies.map { it.title })
        assertEquals("/s5.jpg", sagas.single().poster)
    }

    @Test
    fun folderShortcut() {
        assertEquals(listOf("Toy Story"), library.inRoot("animation").movies.map { it.title })
        assertEquals(listOf("Frieren"), library.inRoot("Anime").shows.map { it.title })
        assertEquals(emptyList<Movie>(), library.inRoot("Anime").movies)
    }

    @Test
    fun shortcutChoice() {
        val roots = listOf("Animation", "Anime", "Films")
        assertEquals(roots, shortcutRoots(null, roots))
        assertEquals(emptyList<String>(), shortcutRoots(null, listOf("Media")))
        assertEquals(listOf("Films"), shortcutRoots(listOf("Films", "Gone"), roots))
        assertEquals(emptyList<String>(), shortcutRoots(emptyList(), roots))
    }
}
