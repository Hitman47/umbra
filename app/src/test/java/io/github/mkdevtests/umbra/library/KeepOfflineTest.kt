package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepOfflineTest {
    private fun episode(root: String, season: Int, number: Int) = Episode(season, number, "$root\\Show\\S%02dE%02d.mkv".format(season, number), 1)

    private fun show(vararg episodes: Episode) =
        Show("tmdb:1", tmdbId = 1, title = "Show", seasons = episodes.groupBy { it.season }.map { (n, e) -> Season(n, episodes = e) })

    @Test
    fun titlesOfAnOfflineNasStayAsTheyWere() {
        val dune = Movie("Bureau\\Dune.mkv", 1, tmdbId = 10, title = "Dune")
        val duneHere = Movie("Salon\\Dune.mkv", 1, tmdbId = 10, title = "Dune")
        val heat = Movie("Bureau\\Heat.mkv", 1, tmdbId = 11, title = "Heat")
        val gone = Movie("Salon\\Old.mkv", 1, tmdbId = 12, title = "Old")
        val previous = Library(
            movies = listOf(dune, heat, gone),
            shows = listOf(show(episode("Salon", 1, 1), episode("Bureau", 1, 2), episode("Bureau", 2, 1))),
        )
        val scanned = Library(movies = listOf(duneHere), shows = listOf(show(episode("Salon", 1, 1), episode("Salon", 1, 2))))

        val result = keepOffline(scanned, previous, setOf("Bureau"))

        // Dune is shown from the NAS that answered; a film deleted from it stays deleted.
        assertEquals(listOf("Salon\\Dune.mkv", "Bureau\\Heat.mkv"), result.movies.map { it.file })
        val seasons = result.shows.single().seasons
        assertEquals(listOf(1, 2), seasons.map { it.number })
        assertEquals(listOf("Salon\\Show\\S01E01.mkv", "Salon\\Show\\S01E02.mkv"), seasons[0].episodes.map { it.file })
        assertEquals(listOf("Bureau\\Show\\S02E01.mkv"), seasons[1].episodes.map { it.file })
    }

    @Test
    fun aShowOnlyOnTheOfflineNasIsKept() {
        val previous = Library(shows = listOf(show(episode("Bureau", 1, 1))))
        assertEquals(listOf("tmdb:1"), keepOffline(Library(), previous, setOf("Bureau")).shows.map { it.key })
        assertEquals(emptyList<Show>(), keepOffline(Library(), previous, emptySet()).shows)
    }

    @Test
    fun aFolderThatDidNotAnswerKeepsItsTitles() {
        val kept = Movie("Media\\Films\\A\\Heat.mkv", 1, tmdbId = 11, title = "Heat")
        val neighbour = Movie("Media\\Films\\B\\Old.mkv", 1, tmdbId = 12, title = "Old")
        val previous = Library(movies = listOf(kept, neighbour))
        // Only "Films\A" failed: its film stays, the deleted one next to it goes.
        assertEquals(listOf("Media\\Films\\A\\Heat.mkv"), keepOffline(Library(), previous, setOf("Media\\Films\\A")).movies.map { it.file })
    }
}
