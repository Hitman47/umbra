package io.github.mkdevtests.umbra.home

import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Season
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.trakt.TraktWish
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalsTest {
    private val day = 24 * 3600_000L
    private val now = 100 * day

    private fun episode(season: Int, number: Int, arrived: Long) = Episode(season, number, "S${season}E$number.mkv", 1, modified = arrived)

    private fun show(vararg episodes: Episode) = Show(
        key = "tmdb:1", tmdbId = 1, title = "Show",
        seasons = episodes.groupBy { it.season }.map { (number, list) -> Season(number, episodes = list) },
    )

    private fun watched(file: String, at: Long) = file to Progress(file, 1400.0, 1440.0, at)

    @Test
    fun episodesArrivedAfterTheLastViewing() {
        val show = show(episode(1, 1, 10 * day), episode(1, 2, 10 * day), episode(1, 3, 95 * day))
        val history = mapOf(watched("S1E1.mkv", 50 * day), watched("S1E2.mkv", 51 * day))
        val found = newEpisodes(Library(shows = listOf(show)), history, now).single()
        assertEquals(listOf("S1E3.mkv"), found.episodes.map { it.file })
        assertEquals("S01E03", found.badge)
    }

    @Test
    fun aSeasonNeverWatchedIsANewSeason() {
        val show = show(episode(1, 1, 10 * day), episode(2, 1, 95 * day), episode(2, 2, 96 * day))
        val found = newEpisodes(Library(shows = listOf(show)), mapOf(watched("S1E1.mkv", 50 * day)), now).single()
        assertEquals(2, found.newSeason)
        assertEquals("Saison 2", found.badge)
    }

    @Test
    fun showsNeverWatchedOrTooOldAreLeftOut() {
        val show = show(episode(1, 1, 10 * day), episode(1, 2, 20 * day))
        assertTrue(newEpisodes(Library(shows = listOf(show)), emptyMap(), now).isEmpty())
        assertTrue(newEpisodes(Library(shows = listOf(show)), mapOf(watched("S1E1.mkv", 15 * day)), now).isEmpty())
    }

    @Test
    fun theWatchlistOwnedFirst() {
        val movie = Movie(file = "film.mkv", fileSize = 1, title = "Film", tmdbId = 7)
        val wishes = listOf(TraktWish(9, isShow = false, title = "Absent"), TraktWish(7, isShow = false, title = "Film"))
        val row = watchlistRow(Library(movies = listOf(movie)), wishes)
        assertEquals(listOf(7, 9), row.map { it.tmdbId })
        assertEquals(movie, row.first().movie)
    }
}
