package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ExtrasTest {
    private val dune = Movie("Films\\Dune.mkv", 1, tmdbId = 1, title = "Dune", year = 2021)
    private val dune2 = Movie("Films\\Dune 2.mkv", 1, tmdbId = 2, title = "Dune : Deuxième partie", year = 2024)
    private val slayer = Show("tmdb:9", tmdbId = 9, title = "Demon Slayer", year = 2019)
    private val train = Movie("Films\\Mugen.mkv", 1, tmdbId = 3, title = "Demon Slayer : le train de l'infini", year = 2020)
    private val dunkirk = Movie("Films\\Dunkirk.mkv", 1, tmdbId = 4, title = "Dunkirk", year = 2017)
    private val library = Library(movies = listOf(dune, dune2, train, dunkirk), shows = listOf(slayer))

    @Test
    fun aShowFindsItsFilms() {
        assertEquals(listOf("Films\\Mugen.mkv"), linkedTitles(library, slayer.title, null, slayer.key).map { it.movie })
        assertEquals(listOf("tmdb:9"), linkedTitles(library, train.title, null, train.file).map { it.show })
        // "Dune" is not "Dunkirk".
        assertEquals(listOf("Films\\Dune 2.mkv"), linkedTitles(library, dune.title, null, dune.file).map { it.movie })
    }

    @Test
    fun sagaInReleaseOrderAndOwnedFirst() {
        val saga = TmdbCollection(
            5, "Dune - Saga",
            listOf(TmdbSearchItem(2, title = "Dune 2", releaseDate = "2024-02-28"), TmdbSearchItem(1, title = "Dune", releaseDate = "2021-09-15"), TmdbSearchItem(6, title = "Dune 3")),
        )
        val tmdb = TmdbMovieExtras(
            credits = TmdbFullCredits(
                cast = listOf(TmdbCredit("Timothée Chalamet", character = "Paul Atreides", profilePath = "/t.jpg")),
                crew = listOf(TmdbCredit("Hans Zimmer", job = "Original Music Composer"), TmdbCredit("Denis Villeneuve", job = "Director"), TmdbCredit("Denis Villeneuve", job = "Screenplay")),
            ),
            recommendations = TmdbSearch(listOf(TmdbSearchItem(7, title = "Arrival"), TmdbSearchItem(4, title = "Dunkirk"))),
        )
        val extras = movieExtras(dune, tmdb, saga, library)

        assertEquals(listOf("Dune", "Dune 2", "Dune 3"), extras.sagaParts.map { it.title })
        assertEquals(listOf(true, true, false), extras.sagaParts.map { it.owned })
        assertEquals(emptyList<Related>(), extras.linked) // Dune 2 is in the saga already
        assertEquals(listOf("Réalisation", "Musique"), extras.crew.map { it.role })
        assertEquals(listOf("Dunkirk", "Arrival"), extras.recommended.map { it.title })
    }
}
