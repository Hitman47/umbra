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
        val releases = parseReleases(json)
        assertEquals(listOf("Film.2021.1080p.BluRay", "Film.2021.1080p.WEB", "Film.2021.2160p.UHD"), releases.map { it.title })
        assertEquals("magnet:?c", releases.first().link)
        assertEquals("2160p", releases.last().quality)
        assertEquals("Dune 2021", searchQuery("Dune", 2021, isShow = false))
        assertEquals("Cosmos", searchQuery("Cosmos", 2014, isShow = true))
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
