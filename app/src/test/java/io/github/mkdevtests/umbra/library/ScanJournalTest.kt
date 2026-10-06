package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.nas.NasEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ScanJournalTest {
    @Test
    fun anAnalysisCutOffResumesFromWhatItListed() {
        val file = File.createTempFile("journal", ".jsonl").apply { delete() }
        val entries = listOf(NasEntry("Dune.mkv", "Media\\Films\\Dune.mkv", false, 42, 7), NasEntry("Saga", "Media\\Films\\Saga", true, 0))
        ScanJournal(file).put("Media\\Films", entries)
        // The app was closed: the next analysis reads the folder from the journal.
        val next = ScanJournal(file)
        assertEquals(entries, next.get("media\\films"))
        assertNull(next.get("Media\\Series"))
        next.put("Media\\Series", emptyList())
        assertEquals(2, ScanJournal(file).resumed)
        // Ended well: nothing left to resume.
        ScanJournal(file).finish()
        assertFalse(file.exists())
    }
}
