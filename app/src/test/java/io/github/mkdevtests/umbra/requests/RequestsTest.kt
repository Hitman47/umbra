package io.github.mkdevtests.umbra.requests

import io.github.mkdevtests.umbra.library.RemoteTitle
import io.github.mkdevtests.umbra.library.RemoteTitles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class RequestsTest {
    @Test
    fun releasesPutThePreferredQualityFirstThenTheMostSeeded() {
        val json = Json.parseToJsonElement(
            """[
              {"title":"Film.2021.2160p.UHD","size":40000000000,"seeders":90,"indexer":"A","magnetUrl":"magnet:?a"},
              {"title":"Film.2021.1080p.WEB","size":8000000000,"seeders":12,"indexer":"B","downloadUrl":"http://p/1"},
              {"title":"Film.2021.1080p.BluRay","size":12000000000,"seeders":40,"indexer":"C","magnetUrl":"magnet:?c"},
              {"title":"Sans lien","size":1}
            ]""",
        ).jsonArray
        val releases = ranked(parseReleases(json))
        assertEquals(listOf("Film.2021.1080p.BluRay", "Film.2021.1080p.WEB", "Film.2021.2160p.UHD"), releases.map { it.title })
        assertEquals("magnet:?c", releases.first().link)
        assertEquals("2160p", releases.last().quality)
        assertEquals("Dune 2021", searchQuery("Dune", 2021, isShow = false))
        assertEquals("Cosmos", searchQuery("Cosmos", 2014, isShow = true))
    }

    @Test
    fun preferredLanguagesComeFirstButTheOthersStay() {
        val releases = listOf(
            Release("Film.2021.1080p.VOSTFR.WEB", 1, 500, "A", magnet = "m1"),
            Release("Film.2021.1080p.VFF.WEB", 1, 10, "A", magnet = "m2"),
            Release("Film.2021.1080p.MULTI.WEB", 1, 5, "A", magnet = "m3"),
            Release("Film.2021.720p.MULTI.WEB", 1, 900, "A", magnet = "m4"),
        )
        assertEquals(listOf("m3", "m2", "m1", "m4"), ranked(releases).map { it.link })
        assertEquals(listOf("MULTI", "VFF", "VOSTFR"), languagesOf(releases))
        assertEquals(listOf("1080p", "720p"), qualitiesOf(releases))
        assertEquals(listOf("m3", "m4"), ReleaseFilter(language = "MULTI").apply(releases).map { it.link })
    }

    @Test
    fun theSameTorrentOnSeveralIndexersIsOneLine() {
        val releases = listOf(
            Release("Film 2021 1080p", 100, 10, "A", magnet = "m1", infoHash = "ABC"),
            Release("Film.2021.1080p", 100, 8, "B", magnet = "m2", infoHash = "abc"),
            Release("Autre", 5, 1, "B", magnet = "m3"),
        )
        val lines = grouped(releases)
        assertEquals(2, lines.size)
        assertEquals(listOf("B"), lines.first().second.map { it.indexer })
    }

    @Test
    fun releaseNamesAreTakenApart() {
        val film = ReleaseTitle.parse("Ghost.in.the.Shell.1995.MULTI.1080p.BluRay.x265-GROUP")
        assertEquals("Ghost in the Shell", film.title)
        assertEquals(1995, film.year)
        assertEquals("1080p", film.tag(TagKind.Resolution))
        assertEquals("MULTI", film.tag(TagKind.Language))
        assertEquals("x265", film.tag(TagKind.Codec))
        val episode = ReleaseTitle.parse("Cosmos S01E03 VFF 720p WEB-DL H.264")
        assertEquals("Cosmos", episode.title)
        assertEquals(1, episode.season)
        assertEquals(3, episode.episode)
        assertEquals("WEB-DL", episode.tag(TagKind.Source))
        assertEquals("Cosmos · S01E03", episode.heading)
        assertEquals("5 j", ageLabel(120.0))
    }

    @Test
    fun askedTitlesLeaveWhenTheyArriveOrAfterAMonth() {
        val now = 100L * 24 * 3600_000
        val list = listOf(
            Requested(1, false, "Arrivé", "r", now - 1000),
            Requested(2, true, "Attendu", "r", now - 1000),
            Requested(3, false, "Ancien", "r", now - REQUEST_KEEP_MS - 1),
        )
        assertEquals(listOf(2), pruned(list, { id, _ -> id == 1 }, now).map { it.tmdbId })
    }

    @Test
    fun absentTitlePagesAreKeptAWeek() {
        var now = 1_000L
        val file = File.createTempFile("remote", ".json")
        RemoteTitles(file) { now }.put(RemoteTitle(10, false, "Dune"))
        assertEquals("Dune", RemoteTitles(file) { now }.get(10, false)?.title)
        assertNull(RemoteTitles(file) { now }.get(10, true))
        now += RemoteTitles.MAX_AGE_MS
        assertNull(RemoteTitles(file) { now }.get(10, false))
        file.delete()
    }
}
