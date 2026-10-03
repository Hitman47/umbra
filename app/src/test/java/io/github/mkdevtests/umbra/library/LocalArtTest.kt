package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalArtTest {
    @Test
    fun folderImageByNameOrAlone() {
        val named = folderArt("Spectacles\\Gad", listOf("Spectacles\\Gad\\fanart.jpg", "Spectacles\\Gad\\folder.jpg"), emptyList())
        assertEquals("Spectacles\\Gad\\folder.jpg", named.posters["Spectacles\\Gad"])
        assertEquals("Spectacles\\Gad\\fanart.jpg", named.backdrops["Spectacles\\Gad"])
        assertEquals("Anime\\X\\image.png", folderArt("Anime\\X", listOf("Anime\\X\\image.png"), emptyList()).posters["Anime\\X"])
        // A lone banner is no poster.
        assertNull(folderArt("Anime\\X", listOf("Anime\\X\\banner.jpg"), emptyList()).posters["Anime\\X"])
    }

    @Test
    fun videoImages() {
        val art = folderArt(
            "Films",
            listOf("Films\\Dune-poster.jpg", "Films\\Dune-fanart.jpg", "Films\\Heat.jpg"),
            listOf("Films\\Dune.mkv", "Films\\Heat.mkv"),
        )
        assertEquals("Films\\Dune-poster.jpg", art.posters["Films\\Dune.mkv"])
        assertEquals("Films\\Dune-fanart.jpg", art.backdrops["Films\\Dune.mkv"])
        assertEquals("Films\\Heat.jpg", art.posters["Films\\Heat.mkv"])
        assertNull(art.posters["Films"])
    }

    @Test
    fun libraryTakesTheNasImages() {
        val show = Show(
            "tmdb:1", tmdbId = 1, title = "Kakegurui", poster = "/tmdb.jpg", folders = listOf("Anime\\Kakegurui"),
            seasons = listOf(Season(1, poster = "/s1.jpg", episodes = listOf(Episode(1, 1, "Anime\\Kakegurui\\Saison 1\\E01.mkv", 1)))),
        )
        val movie = Movie("Films\\Dune (2021)\\Dune.mkv", 1, title = "Dune", poster = "/dune.jpg", folder = "Films\\Dune (2021)")
        val art = LocalArt(posters = mapOf("Anime\\Kakegurui" to "Anime\\Kakegurui\\folder.jpg", "Anime\\Kakegurui\\Saison 1" to "Anime\\Kakegurui\\Saison 1\\cover.jpg", "Films\\Dune (2021)" to "Films\\Dune (2021)\\poster.jpg"))
        val library = Library(movies = listOf(movie), shows = listOf(show)).withLocalArt(art)
        assertEquals("nas:Anime\\Kakegurui\\folder.jpg", library.shows.single().poster)
        assertEquals("nas:Anime\\Kakegurui\\Saison 1\\cover.jpg", library.shows.single().seasons.single().poster)
        assertEquals("nas:Films\\Dune (2021)\\poster.jpg", library.movies.single().poster)
        assertEquals("/tmdb.jpg", Library(shows = listOf(show)).withLocalArt(LocalArt()).shows.single().poster)
    }
}
