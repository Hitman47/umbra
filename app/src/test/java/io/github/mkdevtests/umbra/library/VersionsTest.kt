package io.github.mkdevtests.umbra.library

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionsTest {
    @Test
    fun qualityFromTheFileName() {
        assertEquals(
            VersionInfo("4K · Dolby Vision · HEVC · REMUX", "Atmos TrueHD · MULTi · 58,2 Go"),
            describeVersion("Films\\Dune.Part.Two.2024.MULTi.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.Atmos-GRP.mkv", (58.2 * (1L shl 30)).toLong()),
        )
        assertEquals(
            VersionInfo("1080p · H.264 · WEB", "Dolby Digital+ · VF · 4,1 Go"),
            describeVersion("Séries\\Show.S01E01.FRENCH.1080p.WEB-DL.DDP5.1.x264.mkv", (4.1 * (1L shl 30)).toLong()),
        )
        assertEquals(VersionInfo("AVI", "700 Mo"), describeVersion("Zorro Contre Maciste.avi", 700L shl 20))
        assertEquals("DTS-HD", describeVersion("a.1080p.DTS-HD.MA.mkv", 1).details.substringBefore(" ·"))
    }

    @Test
    fun episodeCopiesAndOlderScans() {
        val episode = Episode(1, 2, "Séries\\Show\\S01E02.mkv", 100)
        val show = Show(
            "tmdb:1", title = "Show",
            seasons = listOf(Season(1, episodes = listOf(episode))),
            duplicates = listOf(
                EpisodeCopy(1, 2, 50, "Séries 2\\Show\\S01E02.720p.mkv").encode(),
                EpisodeCopy(1, 3, 50, "Séries 2\\Show\\S01E03.mkv").encode(),
                "Séries\\Show\\old.mkv", // a scan before 0.6
            ),
        )
        assertEquals(listOf(Version(episode.file, 100), Version("Séries 2\\Show\\S01E02.720p.mkv", 50)), show.versionsOf(episode))
        assertEquals("Séries\\Show\\old.mkv", EpisodeCopy.pathOf("Séries\\Show\\old.mkv"))
        assertEquals("Séries 2\\Show\\S01E03.mkv", EpisodeCopy.pathOf(show.duplicates[1]))
    }
}
