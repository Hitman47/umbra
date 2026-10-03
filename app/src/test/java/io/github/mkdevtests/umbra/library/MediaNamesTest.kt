package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** File names as they sit on the NAS. */
class MediaNamesTest {

    private fun movie(file: String) = parseMediaName(file).let { listOf(it.title, it.year, it.subtitle) }

    @Test
    fun filmsFromFileNames() {
        assertEquals(listOf("Taken", 2008, null), movie("Taken.2008.MULTI.BLURAY.mkv"))
        assertEquals(listOf("Mongol", 2008, null), movie("Mongol 2008.mkv"))
        assertEquals(listOf("Dark Waters", 2019, null), movie("Dark.Waters.2019.TRUEFRENCH.720p.HDLight.x264.AC3-EXTREME_wWw.Extreme-Down.mkv"))
        assertEquals(listOf("Aviator", null, null), movie("Aviator.mkv"))
        assertEquals(listOf("Ce Que Veulent Les Femmes", 2000, null), movie("Ce Que Veulent Les Femmes 2000 multi.mkv"))
        assertEquals(listOf("Eyes Wide Shut", 1999, null), movie("Eyes Wide Shut - 1999.mkv"))
        assertEquals(
            listOf("La chute de la maison blanche - olympus has fallen", 2013, null),
            movie("La chute de la maison blanche - olympus has fallen - 2013.mkv"),
        )
        assertEquals(listOf("The Hobbit", 2013, "The Desolation of Smaug"), movie("The Hobbit - 2013 - The Desolation of Smaug.mkv"))
        assertEquals(listOf("Dune", 2021, null), movie("Dune (2021)"))
        assertEquals(listOf("Your Name", 2016, null), movie("[Group] Your Name (2016) [1080p].mkv"))
        assertEquals(listOf("Troie", 2004, null), movie("Troie [Director's Cut] 2004.mkv"))
        assertEquals(listOf("Pearl Harbor", 2001, null), movie("Pearl Harbor [1080p] MULTI 2001 BluRay x264-PopHD.mkv"))
        assertEquals(listOf("65", 2023, null), movie("65 MULTi 2023.mkv"))
        assertEquals(listOf("Matrix", null, null), movie("Matrix [Remastered].mkv"))
        assertEquals(listOf("Symbol", 2009, null), movie("Symbol (Matsumoto, 2009).mkv"))
        assertEquals(listOf("Stalingrad Snipers", null, null), movie("Stalingrad Snipers.DVD1.avi"))
        assertEquals(listOf("The French Connection", 1971, null), movie("The French Connection 1971.mkv"))
        assertEquals("tt0055928", parseMediaName("James Bond - 01 - James Bond 007 Contre Dr. No - {imdb-tt0055928}.mkv").imdbId)
    }

    @Test
    fun filmsAreNotEpisodes() {
        listOf(
            "Taken.2008.MULTI.BLURAY.mkv",
            "Game Of Thrones Conquest And Rebellion 2017 MULTi.mkv",
            "The Hobbit - 2013 - The Desolation of Smaug.mkv",
            "Star.Wars.Episode.1.1999.mkv",
            "Tombstone.mkv",
            "Film.1080p.x264.mkv",
            "Movie.1920x1080.mkv",
            "James Bond - 01 - James Bond 007 Contre Dr. No - {imdb-tt0055928}.mkv",
            "Schindler’s List - 25 Ans Plus Tard.mkv",
        ).forEach { assertNull(it, parseEpisodeName(it, inSeasonFolder = false)) }
    }

    @Test
    fun episodes() {
        fun episode(file: String, inSeasonFolder: Boolean = false) =
            parseEpisodeName(file, inSeasonFolder)!!.let { listOf(it.show?.title, it.season, it.episode) }

        assertEquals(listOf("Breaking Bad", 1, 2), episode("Breaking.Bad.S01E02.1080p.mkv"))
        assertEquals(listOf("The Office", 3, 12), episode("The Office 3x12.mkv"))
        assertEquals(listOf("Frieren", null, 5), episode("[SubsPlease] Frieren - 05 (1080p) [ABCD1234].mkv"))
        assertEquals(listOf("One Piece", null, 1071), episode("[Erai-raws] One Piece - 1071 [1080p].mkv"))
        assertEquals(listOf("Naruto", null, 12), episode("Naruto - 12.mkv"))
        assertEquals(listOf("Dark", 2, 3), episode("Dark Saison 2 Episode 3.mkv"))
        assertEquals(listOf(null, null, 1), episode("01 - Pilot.mkv", inSeasonFolder = true))
        assertEquals(listOf(null, 1, 4), episode("S01E04.mkv"))
    }

    @Test
    fun seasonFolders() {
        assertEquals(1, parseSeasonFolder("Saison 01"))
        assertEquals(10, parseSeasonFolder("Season 10"))
        assertEquals(0, parseSeasonFolder("Spéciaux"))
        assertNull(parseSeasonFolder("Drame"))
    }

    @Test
    fun episodeNumbersInSeasonFolders() {
        fun number(file: String) = parseEpisodeNumber(file)?.let { it.season to it.episode }
        assertEquals(null to 19, number("Claymore.E19.MULTi.1080p.BluRay.x264-SHiNiGAMi.mkv"))
        assertEquals(null to 3, number("Kakkou.no.Iinazuke.E03.MULTi.1080p.WEB.x264-AMB3R.mkv"))
        assertEquals(null to 54, number("Shingeki No Kyojin 54 ''Héroïque'' Multi 1080P Bluray - Monkey D.Lulu.mkv"))
        assertEquals(1 to 6, number("S0106_HD[LQ][Anime-Ultime].mp4"))
        assertEquals(1 to 7, number("Joker Game S01 - 07 VOSTFR [1080p][X265][10BITS][SR-71].mkv"))
        assertEquals(null to 3, number("Saihate no Paladin - 03 MULTI [BD 1080p x265].mkv"))
        assertEquals(null to 2, number("[Elecman] Blue Submarine NO.6 E02 [BDRIP][720p x264 Multi].mkv"))
        assertNull(number("El Camino A Breaking Bad Movie (2019) MULTi VFi 1080p BluRay EAC3 5.1 x265-k7.mkv"))
        assertNull(number("SPÉCIAL VIDÉOS RUSSES.mp4"))
    }
}
