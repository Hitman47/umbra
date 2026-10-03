package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.history.MatchFix
import io.github.mkdevtests.umbra.nas.NasClient
import io.github.mkdevtests.umbra.nas.NasEntry
import io.github.mkdevtests.umbra.nas.NasRouter
import io.github.mkdevtests.umbra.nas.NasSource
import io.github.mkdevtests.umbra.nas.RemoteFile
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/** A match correction, through the whole scanner: NAS and TMDB faked. */
class MatchFixTest {
    private var listed = 0

    private val files = (1..12).map { "Anime\\Thriller\\Kakegurui\\Saison 1\\Kakegurui - %02d.mkv".format(it) } +
        (1..12).map { "Anime\\Thriller\\Kakegurui\\Saison 2\\Kakegurui xx - %02d.mkv".format(it) }

    private val fakeNas = object : NasClient {
        override val source = NasSource("nas", listOf("Anime"))
        override val currentHost = "nas"
        override fun list(path: String): List<NasEntry> {
            listed++
            if (path.isEmpty()) return listOf(NasEntry("Anime", "Anime", true, 0))
            val prefix = "$path\\"
            return files.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('\\') }.distinct().map { name ->
                val full = prefix + name
                NasEntry(name, full, isDirectory = '.' !in name, size = if ('.' in name) 1_000_000 else 0)
            }
        }
        override fun open(path: String): RemoteFile = error("no")
        override fun availableShares() = null
        override fun close() {}
    }

    private val tmdbCalls = mutableListOf<String>()
    private val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val url = chain.request().url
        tmdbCalls += url.encodedPath + "?" + url.query
        val path = url.encodedPath.removePrefix("/3/")
        val body = when {
            path == "search/tv" -> """{"results":[{"id":2,"name":"Kakegurui","first_air_date":"2018-01-14"},{"id":1,"name":"Kakegurui","first_air_date":"2017-07-01"}]}"""
            path == "tv/1" -> """{"id":1,"name":"Kakegurui","first_air_date":"2017-07-01","overview":"anime","seasons":[{"season_number":1,"episode_count":12},{"season_number":2,"episode_count":12}],"credits":{"cast":[],"crew":[]}}"""
            path == "tv/2" -> """{"id":2,"name":"Kakegurui","first_air_date":"2018-01-14","overview":"live","seasons":[{"season_number":1,"episode_count":10},{"season_number":2,"episode_count":10}],"credits":{"cast":[],"crew":[]}}"""
            path.matches(Regex("tv/\\d+/season/\\d+")) -> """{"episodes":[]}"""
            else -> "{}"
        }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }).build()

    @Test
    fun aCorrectionAppliesAtOnceWithoutTheNas() {
        val first = scan(Library(), emptyMap())
        val groups = first.shows.single().groups.toSet()
        val fixes = groups.associateWith { MatchFix(it, 1) }
        listed = 0
        val fixed = runBlocking { LibraryScanner(NasRouter(listOf(fakeNas)), Tmdb("t", http), fixes) {}.rematch(first, groups, 1) }
        assertEquals(0, listed)
        assertEquals(listOf("tmdb:1"), fixed.shows.map { it.key })
        assertEquals(listOf(12, 12), fixed.shows.single().seasons.map { it.episodes.size })
        assertEquals(groups.toList(), fixed.shows.single().groups)
    }

    private fun scan(previous: Library, fixes: Map<String, MatchFix>) = runBlocking {
        LibraryScanner(NasRouter(listOf(fakeNas)), Tmdb("t", http), fixes) {}.scan(previous, "k")
    }

    @Test
    fun aCorrectionWinsOverTmdbAndStays() {
        // TMDB lists the live action first: the search takes it.
        val first = scan(Library(), emptyMap())
        assertEquals(listOf("tmdb:2"), first.shows.map { it.key })
        val groups = first.shows.single().groups
        assertEquals(listOf("folder:Anime\\Thriller\\Kakegurui"), groups)
        // The user picks the anime: it wins, and stays at the next scan.
        val fixes = groups.associateWith { MatchFix(it, 1) }
        val fixed = scan(first, fixes)
        assertEquals(listOf("tmdb:1"), fixed.shows.map { it.key })
        assertEquals(listOf(12, 12), fixed.shows.single().seasons.map { it.episodes.size })
        assertEquals(listOf("tmdb:1"), scan(fixed, fixes).shows.map { it.key })
    }
}
