package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexKeysTest {
    @Test
    fun accentsSortAndIndexWithTheirLetter() {
        val titles = byTitle(listOf("Zodiac", "Élite", "2001", "Amélie", "eXistenZ", "Ça")) { it }
        assertEquals(listOf("2001", "Amélie", "Ça", "Élite", "eXistenZ", "Zodiac"), titles)
        assertEquals(mapOf("#" to 0, "A" to 1, "C" to 2, "E" to 3, "Z" to 5), letterPositions(titles))
        assertEquals("#", indexLetter("(500) jours ensemble".drop(1)))
        assertEquals("L", indexLetter("L'Âge de glace"))
    }

    @Test
    fun decadesPointAtTheirFirstTitle() {
        assertEquals(linkedMapOf("2020" to 0, "2010" to 2, "1990" to 3), decadePositions(listOf(2023, 2021, 2014, 1999, null)))
    }
}
