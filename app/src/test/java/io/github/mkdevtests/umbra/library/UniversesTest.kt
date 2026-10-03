package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniversesTest {
    private fun movie(title: String, year: Int, characters: List<String>, saga: Int? = null, directors: List<String> = emptyList(), universes: List<String> = emptyList()) =
        Movie("Films\\$title.mkv", 1, title = title, year = year, sagaId = saga, characters = characters, directors = directors, universes = universes)

    private val rises = movie("The Dark Knight Rises", 2012, listOf("Batman", "Bruce Wayne", "Selina Kyle", "Bane"), saga = 263, directors = listOf("Christopher Nolan"))
    private val library = Library(
        movies = listOf(
            movie("Batman Begins", 2005, listOf("Batman", "Bruce Wayne", "Alfred Pennyworth"), saga = 263, directors = listOf("Christopher Nolan")),
            rises,
            movie("Batman", 1989, listOf("Batman", "Bruce Wayne", "Joker", "Vicki Vale")),
            movie("The Batman", 2022, listOf("Batman", "Bruce Wayne", "Selina Kyle")),
            movie("Joker", 2019, listOf("Joker", "Arthur Fleck")),
            movie("Inception", 2010, listOf("Cobb", "Arthur"), directors = listOf("Christopher Nolan")),
            movie("Dune", 2021, listOf("Paul Atreides", "Lady Jessica")),
        ),
    )

    @Test
    fun charactersFromTmdb() {
        // The four leads only; the hero's name first.
        assertEquals(
            listOf("Batman", "Bruce Wayne", "Selina Kyle", "Bane", "John Daggett"),
            leadCharacters(listOf("Bruce Wayne / Batman", "Selina Kyle", "Bane", "John Daggett (uncredited)", "Alfred")),
        )
        assertEquals(listOf("Arthur Fleck"), leadCharacters(listOf("Himself", "Young Arthur Fleck", "John", "Man #2")))
        assertEquals(listOf("dc extended universe (dceu)"), franchiseKeywords(listOf("DC Extended Universe (DCEU)", "parallel universe", "superhero")))
        assertEquals("DC Extended Universe", universeLabel("dc extended universe (dceu)"))
    }

    @Test
    fun rowsOfAFilmPage() {
        val all = library.linkables()
        val self = all.first { it.title == "The Dark Knight Rises" }
        val characters = characterRow(self, all)!!
        // The trilogy has its own row; the other Batman films come newest first.
        assertEquals("Aussi avec Batman", characters.title)
        assertEquals(listOf("The Batman", "Batman"), characters.items.map { it.title })
        val director = directorRow(self, all, characters.items.mapNotNull { it.movie }.toSet())!!
        assertEquals(listOf("Inception"), director.items.map { it.title })
        assertNull(characterRow(all.first { it.title == "Dune" }, all))
    }

    @Test
    fun universes() {
        val universes = universesOf(library)
        assertEquals(1, universes.size)
        // Joker joins through the Joker of Batman (1989).
        assertEquals("Batman", universes.single().name)
        assertEquals(listOf("Batman", "Batman Begins", "The Dark Knight Rises", "Joker", "The Batman"), universes.single().titles.map { it.title })
    }
}
