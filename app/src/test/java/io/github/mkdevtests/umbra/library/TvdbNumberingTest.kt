package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvdbNumberingTest {
    private fun tvdb(season: Int, number: Int, absolute: Int?, aired: String?) = TvdbEpisode(season, number, absolute, aired)

    private fun day(n: Int) = "2020-01-%02d".format(n)

    @Test
    fun absoluteNumbersFollowTheAirDates() {
        // TheTVDB cuts 3 + 3, TMDB 4 + 2.
        val theirs = (1..6).map { tvdb(if (it <= 3) 1 else 2, if (it <= 3) it else it - 3, it, day(it)) }
        val ours = (1..6).map { TmdbSlot(if (it <= 4) 1 else 2, if (it <= 4) it else it - 4, day(it)) }
        val numbering = Numbering(0, pairEpisodes(theirs, ours))

        assertEquals(2 to 1, numbering.place(1, 5, seasonKnown = false)) // "Show - 05"
        assertEquals(1 to 4, numbering.place(2, 1, seasonKnown = true)) // "Saison 2\E01" on TheTVDB
        assertEquals(2 to 2, numbering.place(2, 6, seasonKnown = true)) // "Saison 2\06": absolute
        assertNull(numbering.place(1, 7, seasonKnown = false))
    }

    @Test
    fun doubleEpisodeOnTmdbShiftsTheFollowingOnes() {
        // TheTVDB has episodes 3 and 4 the same day, TMDB one episode for both.
        val theirs = listOf(tvdb(1, 1, 1, day(1)), tvdb(1, 2, 2, day(2)), tvdb(1, 3, 3, day(3)), tvdb(1, 4, 4, day(3)), tvdb(1, 5, 5, day(4)))
        val ours = listOf(TmdbSlot(1, 1, day(1)), TmdbSlot(1, 2, day(2)), TmdbSlot(1, 3, day(3)), TmdbSlot(1, 4, day(4)))
        val numbering = Numbering(0, pairEpisodes(theirs, ours))

        assertEquals(1 to 3, numbering.place(1, 3, seasonKnown = false))
        assertNull(numbering.place(1, 4, seasonKnown = false)) // no TMDB episode of its own
        assertEquals(1 to 4, numbering.place(1, 5, seasonKnown = false))
    }

    @Test
    fun aDayApartIsTheSameEpisodeAndOrderFillsTheGaps() {
        // Japanese date on TheTVDB, the day before on TMDB; no dates for the last one, no absolute numbers at all.
        val theirs = listOf(tvdb(1, 1, null, day(2)), tvdb(1, 2, 0, day(9)), tvdb(1, 3, null, null))
        val ours = listOf(TmdbSlot(0, 1, day(1)), TmdbSlot(1, 1, day(1)), TmdbSlot(1, 2, day(8)), TmdbSlot(1, 3, null))
        val numbering = Numbering(0, pairEpisodes(theirs, ours))

        assertEquals(1 to 1, numbering.place(1, 1, seasonKnown = false))
        assertEquals(1 to 2, numbering.place(1, 2, seasonKnown = false))
        assertEquals(1 to 3, numbering.place(1, 3, seasonKnown = false))
    }

    @Test
    fun specialsAreLeftOut() {
        val theirs = listOf(tvdb(0, 1, null, day(1)), tvdb(1, 1, 1, day(2)))
        val ours = listOf(TmdbSlot(1, 1, day(2)))
        assertEquals(listOf(NumberingEntry(1, 1, 1, 1, 1)), pairEpisodes(theirs, ours))
    }
}
