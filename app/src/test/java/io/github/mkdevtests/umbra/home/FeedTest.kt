package io.github.mkdevtests.umbra.home

import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Season
import io.github.mkdevtests.umbra.library.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTest {

    private fun episode(season: Int, number: Int) = Episode(season, number, "S${season}E$number.mkv", 1)

    private val show = Show(
        key = "tmdb:1",
        title = "Show",
        seasons = listOf(
            Season(0, episodes = listOf(episode(0, 1))),
            Season(1, episodes = listOf(episode(1, 1), episode(1, 2))),
            Season(2, episodes = listOf(episode(2, 1))),
        ),
    )

    private fun watched(file: String, at: Long) = file to Progress(file, 1400.0, 1440.0, at)
    private fun stopped(file: String, at: Long, position: Double = 600.0) = file to Progress(file, position, 1440.0, at)

    @Test
    fun watchedThresholds() {
        assertTrue(Progress("a", 1390.0, 1440.0, 0).watched) // last minute of an episode
        assertTrue(Progress("a", 6700.0, 7200.0, 0).watched) // credits of a 2 h film
        assertFalse(Progress("a", 6000.0, 7200.0, 0).watched)
        assertFalse(Progress("a", 30.0, 1440.0, 0).inProgress) // just opened
    }

    @Test
    fun episodeStoppedMidwayIsResumed() {
        val resume = nextUp(show, mapOf(watched("S1E1.mkv", 1), stopped("S1E2.mkv", 2)))!!
        assertEquals("S1E2.mkv", resume.episode!!.file)
        assertEquals(600.0, resume.progress!!.position, 0.0)
    }

    @Test
    fun nextEpisodeAfterTheLastWatchedAcrossSeasons() {
        val resume = nextUp(show, mapOf(watched("S1E1.mkv", 1), watched("S1E2.mkv", 2)))!!
        assertEquals("S2E1.mkv", resume.episode!!.file)
        assertNull(resume.progress)
    }

    @Test
    fun afterASpecialTheFirstUnwatchedEpisode() {
        val resume = nextUp(show, mapOf(watched("S1E1.mkv", 1), watched("S0E1.mkv", 2)))!!
        assertEquals("S1E2.mkv", resume.episode!!.file)
    }

    @Test
    fun finishedShowLeavesTheList() {
        assertNull(nextUp(show, mapOf(watched("S1E1.mkv", 1), watched("S1E2.mkv", 2), watched("S2E1.mkv", 3))))
    }

    @Test
    fun continueWatchingLatestFirstWithoutFinishedFilms() {
        val library = Library(
            movies = listOf(Movie("a.mkv", 1, title = "A"), Movie("b.mkv", 1, title = "B"), Movie("c.mkv", 1, title = "C")),
            shows = listOf(show),
        )
        val history = mapOf(
            stopped("a.mkv", 1),
            watched("b.mkv", 5),
            stopped("c.mkv", 3, position = 20.0),
            stopped("S1E1.mkv", 4),
        )
        assertEquals(listOf("S1E1.mkv", "a.mkv"), continueWatching(library, history).map { it.file })
    }

    @Test
    fun forYouFollowsTheGenresWatched() {
        // Three of each: "Pour toi" mixes the best three times as many titles as it shows.
        val films = (1..3).flatMap { i ->
            listOf(
                Movie("c$i.mkv", 1, title = "Comédie", genres = listOf("Comédie"), rating = 9.0),
                Movie("w$i.mkv", 1, title = "Western", genres = listOf("Western"), rating = 6.0),
            )
        }
        val picks = forYou(films, mapOf("Western" to 3.0), { it.genres }, { it.rating }, seed = 1, count = 1)
        assertEquals("Western", picks.single().title)
        val noHistory = forYou(films, emptyMap(), { it.genres }, { it.rating }, seed = 1, count = 1)
        assertEquals("Comédie", noHistory.single().title)
    }
}
