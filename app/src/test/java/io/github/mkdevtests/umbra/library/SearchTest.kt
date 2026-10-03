package io.github.mkdevtests.umbra.library

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
    fun ftsQueryPerColumn() {
        assertEquals("text:Denis* text:vil*", ftsQuery("Denis vil", "text")) // the index ignores case
        assertEquals("dune*", ftsQuery("dune!"))
    }
}
