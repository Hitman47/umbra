package io.github.mkdevtests.umbra.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackMemoryTest {
    private val audio = listOf(Track(1, "fre", "VFF 5.1"), Track(2, "eng", "English 5.1"), Track(3, "eng", "Commentary"))
    private val subs = listOf(Track(4, "fre", "Forcés", forced = true), Track(5, "fra", "Complets"), Track(6, "eng", "SDH"))

    @Test
    fun theSameLanguagesAgain() {
        val memory = TrackMemory(audioLang = "en", audioTitle = "Other title", subLang = "fre", subTitle = "Complets")
        assertEquals(TrackChoice(2, 5), rememberedTracks(memory, audio, subs))
    }

    @Test
    fun forcedStaysForcedAndOffStaysOff() {
        assertEquals(4, rememberedTracks(TrackMemory(subLang = "fr", subForced = true), audio, subs).subtitles)
        assertEquals(NO_SUBTITLES, rememberedTracks(TrackMemory(subtitlesOff = true), audio, subs).subtitles)
    }

    @Test
    fun nothingMatchingLeavesTheUsualChoice() {
        assertEquals(TrackChoice(null, null), rememberedTracks(TrackMemory(audioLang = "jpn", subLang = "jpn"), audio, subs))
    }
}
