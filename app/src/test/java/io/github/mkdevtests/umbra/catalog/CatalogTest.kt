package io.github.mkdevtests.umbra.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class CatalogTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun videos_go_to_the_profile_owning_their_nearest_folder() {
        val people = listOf(
            CatalogPerson("a", "Anna", folders = listOf("Cat/A-D/Anna")),
            CatalogPerson("b", "Bea", folders = listOf("Cat/A-D/Bea", "Other/Bea")),
        )
        val videos = listOf(
            CatalogVideo("Cat/A-D/Anna/one.mp4"),
            CatalogVideo("Cat/A-D/Anna/Studio/two.mp4"),
            CatalogVideo("Other/Bea/three.mkv"),
            CatalogVideo("Group/four.mp4"),
        )
        val index = CatalogIndex(CatalogData(people, videos, root = "Media\\Divers"))
        assertEquals(2, index.videosOf("a").size)
        assertEquals(listOf("Other/Bea/three.mkv"), index.videosOf("b").map { it.path })
        assertEquals(listOf("Group/four.mp4"), index.ungrouped.map { it.path })
        assertEquals("Media\\Divers\\Cat\\A-D\\Anna\\one.mp4", index.nasPath(videos[0]))
    }

    @Test
    fun pages_and_profile_are_read_from_the_server_json() {
        val (people, total) = parsePeople(
            obj("""{"total":1240,"performers":[{"id":"x1","name":"Anna","folders":["Cat/Anna"],"categories":["Cat"],"video_count":3,"size_bytes":42,"followed":true,"photo_version":17.0}]}"""),
            "performers",
        )
        assertEquals(1240, total)
        assertEquals(CatalogPerson("x1", "Anna", listOf("Cat"), listOf("Cat/Anna"), 3, 42, true, 17), people.single())
        val (videos, _) = parseVideos(obj("""{"total":1,"videos":[{"path":"Cat/Anna/a.mp4","size_bytes":5,"related_images":["Cat/Anna/a.jpg"]}]}"""))
        assertEquals(CatalogVideo("Cat/Anna/a.mp4", 5, listOf("Cat/Anna/a.jpg")), videos.single())

        val profile = parseProfile(
            obj("""{"aliases":["Ann"],"profile":{"data":{"id":"9","name":"Anna","bio":"Text.","birthday":"1995-04-08","height":"160cm","extras":{"nationality":"X","shoe_size":"38"},"image":"http://x"}}}"""),
            today = LocalDate.of(2026, 10, 3),
        )
        assertEquals(listOf("Ann"), profile.aliases)
        assertEquals("Text.", profile.text)
        assertEquals(
            listOf("Naissance" to "08/04/1995 · 31 ans", "Nationalité" to "X", "Taille" to "160cm", "Shoe size" to "38"),
            profile.facts,
        )
    }

    /** The catalogue is only ever read: its client sends GET requests and nothing else. */
    @Test
    fun the_catalogue_is_read_only() {
        // The only file that talks to the catalogue (the store only writes its own copy on the device).
        val code = File("src/main/java/io/github/mkdevtests/umbra/catalog/CatalogClient.kt").readText()
        val writes = Regex("""\.(post|put|patch|delete|method)\(""").findAll(code).map { it.value }.toList()
        assertEquals(emptyList<String>(), writes)
        assertTrue(".get()" in code)
    }
}
