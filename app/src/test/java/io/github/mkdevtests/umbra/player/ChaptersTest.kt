package io.github.mkdevtests.umbra.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChaptersTest {
    private val anime = listOf(Chapter("Prologue", 0.0), Chapter("Opening", 95.0), Chapter("Part A", 185.0), Chapter("Ending", 1290.0), Chapter("Preview", 1380.0))

    @Test
    fun kinds() {
        assertEquals(ChapterKind.Intro, chapterKind(Chapter("OP", 60.0), 1400.0))
        assertEquals(ChapterKind.Intro, chapterKind(Chapter("Générique", 30.0), 2600.0))
        assertEquals(ChapterKind.Credits, chapterKind(Chapter("Générique", 2500.0), 2600.0))
        assertEquals(ChapterKind.Credits, chapterKind(Chapter("Générique de fin", 2500.0), 2600.0))
        assertEquals(ChapterKind.Credits, chapterKind(Chapter("ED", 1290.0), 1400.0))
        assertEquals(ChapterKind.Other, chapterKind(Chapter("Chapter 3", 600.0), 1400.0))
        // A scene after the credits is part of the film.
        assertEquals(ChapterKind.Other, chapterKind(Chapter("Post-credits scene", 6000.0), 6100.0))
        assertEquals(ChapterKind.Other, chapterKind(Chapter("Opération Tonnerre", 600.0), 6100.0))
    }

    @Test
    fun skippable() {
        assertNull(skippableAt(anime, 30.0, 1420.0))
        val intro = skippableAt(anime, 100.0, 1420.0)!!
        assertEquals(ChapterKind.Intro, intro.kind)
        assertEquals(185.0, intro.end, 0.0)
        val preview = skippableAt(anime, 1390.0, 1420.0)!!
        assertEquals(ChapterKind.Credits, preview.kind)
        assertTrue(preview.last)
        assertEquals(1420.0, preview.end, 0.0)
        assertTrue(hasCredits(anime, 1420.0))
    }
}
