package io.github.mkdevtests.umbra.trakt

import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Season
import io.github.mkdevtests.umbra.library.Show
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktHistoryTest {

    private val dune = Movie("Films\\Dune.mkv", 1, tmdbId = 1, title = "Dune", runtime = 155)
    private val unknown = Movie("Films\\Vacances.mkv", 1, title = "Vacances")
    private val episode = Episode(2, 3, "Séries\\Show\\S02E03.mkv", 1, title = "Trois", runtime = 40)
    private val show = Show("tmdb:9", tmdbId = 9, title = "Show", seasons = listOf(Season(2, episodes = listOf(episode))))
    private val library = Library(movies = listOf(dune, unknown), shows = listOf(show))

    @Test
    fun nothingFromTraktKeepsNyxarasHistory() {
        val local = mapOf(dune.file to Progress(dune.file, 10.0, 100.0, 5))
        assertSame(local, withTrakt(library, local, TraktData()))
    }

    @Test
    fun watchedElsewhereShowsWatched() {
        val merged = withTrakt(library, emptyMap(), TraktData(watched = mapOf(movieKey(1) to 1000L, episodeKey(9, 2, 3) to 2000L)))
        assertTrue(merged.getValue(dune.file).watched)
        assertTrue(merged.getValue(episode.file).watched)
        assertEquals(setOf(dune.file, episode.file), merged.keys)
    }

    @Test
    fun startedElsewhereResumesThere() {
        val merged = withTrakt(library, emptyMap(), TraktData(playback = mapOf(movieKey(1) to TraktResume(50.0, 1000L))))
        val progress = merged.getValue(dune.file)
        assertEquals(155 * 60.0, progress.duration, 0.1)
        assertEquals(155 * 30.0, progress.position, 0.1)
    }

    @Test
    fun theLatestWins() {
        val mine = Progress(dune.file, 600.0, 9000.0, 5000L)
        val older = withTrakt(library, mapOf(dune.file to mine), TraktData(watched = mapOf(movieKey(1) to 1000L)))
        assertEquals(mine, older[dune.file])
        val newer = withTrakt(library, mapOf(dune.file to mine), TraktData(playback = mapOf(movieKey(1) to TraktResume(80.0, 9000L))))
        // Nyxara's own duration is the reference for the resume point.
        assertEquals(7200.0, newer.getValue(dune.file).position, 0.1)
    }
}
