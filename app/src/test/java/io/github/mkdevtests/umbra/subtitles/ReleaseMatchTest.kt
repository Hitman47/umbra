package io.github.mkdevtests.umbra.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMatchTest {
    private val file = releaseTraits("Dune.Part.Two.2024.1080p.WEB-DL.DDP5.1.Atmos-FLUX.mkv")

    @Test
    fun traits() = assertEquals(ReleaseTraits("flux", "web", "1080p"), file)

    @Test
    fun sameReleaseByGroupOrBySourceAndResolution() {
        assertTrue(sameRelease(file, releaseTraits("Dune.Part.Two.2024.2160p.WEB-DL-FLUX")))
        assertTrue(sameRelease(file, releaseTraits("Dune Part Two 2024 1080p WEBRip x264")))
        assertFalse(sameRelease(file, releaseTraits("Dune.Part.Two.2024.1080p.BluRay.x264-SPARKS")))
        assertFalse(sameRelease(releaseTraits("Dune.mkv"), releaseTraits("Dune.1080p.WEB")))
    }
}
