package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.history.hideKey
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {

    private val dune = Movie("Films\\Dune.mkv", 1, tmdbId = 1, title = "Dune", year = 2021, genres = listOf("Science-Fiction"), directors = listOf("Denis Villeneuve"))
    private val sicario = Movie("Films\\Sicario.mkv", 1, tmdbId = 2, title = "Sicario", year = 2015, genres = listOf("Thriller"), directors = listOf("Denis Villeneuve"))
    private val aDune = Movie("Films\\La Dune.mkv", 1, tmdbId = 3, title = "La Dune", year = 1998, cast = listOf("Bernard Giraudeau"))
    private val episode = Episode(1, 1, "Séries\\Show\\S01E01.mkv", 1, title = "Dune noire")
    private val show = Show("tmdb:9", title = "Show", year = 2019, seasons = listOf(Season(1, episodes = listOf(episode))))
    private val library = Library(movies = listOf(sicario, aDune, dune), shows = listOf(show))

    private fun hit(item: Movie) = SearchHit("movie", item.file)

    @Test
    fun titlesStartingWithTheQueryFirstThenEpisodesThenPeople() {
        val hits = SearchHits(
            titles = listOf(hit(aDune), hit(dune), SearchHit("episode", episode.file)),
            people = listOf(hit(dune), hit(sicario)),
        )
        val found = searchResults(library, emptySet(), "dune", hits, SearchFilters())
        assertEquals(listOf("Dune", "La Dune", "Show", "Sicario"), found.map { it.title })
        assertEquals("Épisode", found[2].note)
    }

    @Test
    fun personFoundSaysWho() {
        val found = searchResults(library, emptySet(), "denis vil", SearchHits(emptyList(), listOf(hit(sicario))), SearchFilters())
        assertEquals("Denis Villeneuve", found.single().note)
    }

    @Test
    fun filtersWithoutQueryBrowseTheLibrary() {
        assertEquals(listOf("Dune"), searchResults(library, emptySet(), "", null, SearchFilters(genre = "Science-Fiction")).map { it.title })
        assertEquals(listOf("La Dune"), searchResults(library, emptySet(), "", null, SearchFilters(decade = 1990)).map { it.title })
        assertEquals(listOf("Show"), searchResults(library, emptySet(), "", null, SearchFilters(kind = SearchKind.Shows)).map { it.title })
    }

    @Test
    fun hiddenTitlesOnlyUnderTheirFilter() {
        val hidden = setOf(dune.hideKey)
        assertEquals(listOf("La Dune", "Show", "Sicario"), searchResults(library, hidden, "", null, SearchFilters()).map { it.title })
        assertEquals(listOf("Dune"), searchResults(library, hidden, "", null, SearchFilters(hidden = true)).map { it.title })
    }

    @Test
    fun seenFilterAndBadges() {
        val special = Episode(0, 1, "Séries\\Show\\S00E01.mkv", 1)
        val episode2 = Episode(1, 2, "Séries\\Show\\S01E02.mkv", 1)
        val twoEpisodes = Library(movies = listOf(dune, sicario), shows = listOf(show.copy(seasons = listOf(Season(0, episodes = listOf(special)), Season(1, episodes = listOf(episode, episode2))))))
        fun watched(file: String) = file to Progress(file, 1400.0, 1440.0, 0)
        fun titles(history: Map<String, Progress>, seen: Boolean?) =
            searchResults(twoEpisodes, emptySet(), "", null, SearchFilters(seen = seen), history).map { it.title to it.badge }

        val partly = mapOf(watched(dune.file), watched(episode.file), sicario.file to Progress(sicario.file, 600.0, 6000.0, 0))
        assertEquals(listOf("Dune" to "✓ Vu"), titles(partly, seen = true))
        assertEquals(listOf("Show" to "1/2", "Sicario" to null), titles(partly, seen = false)) // a film started isn't seen
        // Every episode but the specials: the show is seen.
        assertEquals(listOf("Dune" to "✓ Vu", "Show" to "✓ Vu"), titles(partly + watched(episode2.file), seen = true))
    }

    @Test
    fun ftsQueryPerColumn() {
        assertEquals("text:Denis* text:vil*", ftsQuery("Denis vil", "text")) // the index ignores case
        assertEquals("dune*", ftsQuery("dune!"))
    }
}
