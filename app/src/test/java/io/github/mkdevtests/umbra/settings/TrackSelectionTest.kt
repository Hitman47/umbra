package io.github.mkdevtests.umbra.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackSelectionTest {

    private val defaults = Settings()

    private fun choose(audio: List<Track>, subtitles: List<Track> = emptyList(), settings: Settings = defaults) =
        chooseTracks(audio, subtitles, settings)

    @Test
    fun englishAudioFirstThenFrenchSubtitles() {
        val choice = choose(
            audio = listOf(Track(1, "fre", default = true), Track(2, "eng")),
            subtitles = listOf(Track(1, "fre", "Forcés", forced = true), Track(2, "fre", "Complets"), Track(3, "eng")),
        )
        assertEquals(TrackChoice(audio = 2, subtitles = 2), choice)
    }

    @Test
    fun frenchAudioWhenNoEnglishOnlyForcedSubtitles() {
        val choice = choose(
            audio = listOf(Track(1, "jpn"), Track(2, "fre")),
            subtitles = listOf(Track(1, "fre", "Full"), Track(2, "fre", "Forced")),
        )
        assertEquals(TrackChoice(audio = 2, subtitles = 2), choice)
    }

    @Test
    fun frenchAudioWithoutForcedSubtitlesTurnsThemOff() {
        val choice = choose(audio = listOf(Track(1, "fre")), subtitles = listOf(Track(1, "fre")))
        assertEquals(TrackChoice(audio = 1, subtitles = NO_SUBTITLES), choice)
    }

    @Test
    fun originalLanguageWhenNeitherRankedLanguageExists() {
        val choice = choose(
            audio = listOf(Track(1, "jpn", "Japonais"), Track(2, "ger")),
            subtitles = listOf(Track(1, "eng"), Track(2, "fra", "Français SDH"), Track(3, "fra", "Français")),
        )
        assertEquals(TrackChoice(audio = 1, subtitles = 3), choice)
    }

    @Test
    fun languageFromTitleWhenUntagged() {
        val choice = choose(
            audio = listOf(Track(1, null, "VFF 5.1"), Track(2, null, "English 5.1")),
            subtitles = listOf(Track(1, null, "Sous-titres en français")),
        )
        assertEquals(TrackChoice(audio = 2, subtitles = 1), choice)
    }

    @Test
    fun commentaryOnlyAsLastResort() {
        val choice = choose(audio = listOf(Track(1, "eng", "Commentary"), Track(2, "eng", "Main")))
        assertEquals(2, choice.audio)
    }

    @Test
    fun userOrderAndNoSubtitles() {
        val settings = Settings(audioOrder = listOf(AudioLanguage.French, AudioLanguage.English, AudioLanguage.Original), subtitles = null)
        val choice = choose(audio = listOf(Track(1, "eng"), Track(2, "fre")), subtitles = listOf(Track(1, "fre")), settings = settings)
        assertEquals(TrackChoice(audio = 2, subtitles = NO_SUBTITLES), choice)
    }

    @Test
    fun noFrenchSubtitlesFallsBackToForcedInAudioLanguage() {
        val choice = choose(
            audio = listOf(Track(1, "eng")),
            subtitles = listOf(Track(1, "eng", "English"), Track(2, "eng", "English Forced", forced = true)),
        )
        assertEquals(TrackChoice(audio = 1, subtitles = 2), choice)
    }

    @Test
    fun nothingToChooseFrom() {
        assertEquals(TrackChoice(audio = null, subtitles = NO_SUBTITLES), choose(audio = emptyList()))
    }
}
