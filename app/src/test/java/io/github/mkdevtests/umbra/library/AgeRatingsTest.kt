package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgeRatingsTest {
    @Test
    fun franceFirstThenTheUnitedStates() {
        assertEquals(12, ageOf(listOf(CountryRating("US", "PG"), CountryRating("FR", "12"))))
        assertEquals(13, ageOf(listOf(CountryRating("FR", ""), CountryRating("US", "PG-13"))))
        assertEquals(0, ageOf(listOf(CountryRating("FR", "U"))))
        assertEquals(0, ageOf(listOf(CountryRating("US", "TV-Y"))))
    }

    @Test
    fun unknownStaysUnknown() {
        assertNull(ageOf(emptyList()))
        assertNull(ageOf(listOf(CountryRating("JP", "G"), CountryRating("FR", "NR"))))
    }
}

class ForAgeTest {
    @Test
    fun onlyTheTitlesRatedForTheAge() {
        val library = Library(
            movies = listOf(
                Movie(file = "a", fileSize = 1, title = "A", tmdbId = 1),
                Movie(file = "b", fileSize = 1, title = "B", tmdbId = 2),
                Movie(file = "c", fileSize = 1, title = "C", tmdbId = 3),
                Movie(file = "d", fileSize = 1, title = "D"),
            ),
        )
        val kept = library.forAge(mapOf("m:1" to 0, "m:2" to 12, "m:3" to -1), maxAge = 10)
        assertEquals(listOf("a"), kept.movies.map { it.file })
    }
}
