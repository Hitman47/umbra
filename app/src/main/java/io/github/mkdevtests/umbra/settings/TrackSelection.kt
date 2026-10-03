package io.github.mkdevtests.umbra.settings

/** Languages offered in the settings, with the tags files use for them (ISO 639-1/2, names). */
enum class Language(val label: String, private val tags: Set<String>) {
    French("Français", setOf("fr", "fre", "fra", "french", "francais", "français", "vf", "vff", "vfq", "vfi")),
    English("Anglais", setOf("en", "eng", "english", "anglais")),
    ;

    fun matches(tag: String?): Boolean {
        val normalized = tag?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return false
        return normalized in tags
    }
}

/** An audio or subtitle track as mpv lists it. */
data class Track(
    val id: Int,
    val lang: String? = null,
    val title: String? = null,
    val forced: Boolean = false,
    val default: Boolean = false,
) {
    /** Forced subtitles only cover foreign dialogue; files often say so in the title alone. */
    val isForced get() = forced || title?.contains(FORCED) == true
    val isCommentary get() = title?.contains(COMMENTARY) == true
    val isHearingImpaired get() = title?.contains(HEARING_IMPAIRED) == true

    /** Language from the tag, else from the title ("Français 5.1"); short title words like "en" are just words. */
    fun isIn(language: Language) = language.matches(lang) ||
        title?.split(WORD)?.any { word -> (word.length >= 3 || word.equals("vf", ignoreCase = true)) && language.matches(word) } == true

    private companion object {
        val FORCED = Regex("""(?i)forc""")
        val COMMENTARY = Regex("""(?i)comment|audio ?desc|\bAD\b""")
        val HEARING_IMPAIRED = Regex("""(?i)\bSDH\b|malentendant|sourds|\bCC\b""")
        val WORD = Regex("""[^\p{L}]+""")
    }
}

/** The tracks to start with; null leaves mpv's own choice, [NO_SUBTITLES] turns them off. */
data class TrackChoice(val audio: Int?, val subtitles: Int?)

const val NO_SUBTITLES = -1

/**
 * Picks audio by the user's language order, then subtitles in their language,
 * never blocking playback: when nothing matches, the closest track wins.
 * - Audio: the first ranked language a track has; "VO" is a track in none of
 *   the ranked languages (the film's own), else the default or first track.
 *   Commentary tracks only when nothing else matches.
 * - Subtitles: full ones in the user's language. When the audio already is in
 *   that language, only forced ones (foreign dialogue). Without any, forced
 *   subtitles in the audio's language, else none.
 */
fun chooseTracks(audio: List<Track>, subtitles: List<Track>, settings: Settings): TrackChoice {
    val chosenAudio = chooseAudio(audio, settings.audioOrder)
    return TrackChoice(chosenAudio?.id, chooseSubtitles(subtitles, chosenAudio, settings.subtitles))
}

private fun chooseAudio(tracks: List<Track>, order: List<AudioLanguage>): Track? {
    if (tracks.isEmpty()) return null
    val ranked = order.mapNotNull { it.language }
    // Main tracks first, the default one first among them.
    val candidates = tracks.sortedWith(compareBy<Track>({ it.isCommentary }, { !it.default }, { it.id }))
    for (choice in order) {
        val language = choice.language
        val match = if (language != null) {
            candidates.firstOrNull { it.isIn(language) }
        } else {
            candidates.firstOrNull { track -> ranked.none(track::isIn) }
        }
        if (match != null) return match
    }
    return candidates.first()
}

private fun chooseSubtitles(tracks: List<Track>, audio: Track?, language: Language?): Int? {
    if (language == null) return NO_SUBTITLES
    val inLanguage = tracks.filter { it.isIn(language) }
    if (audio?.isIn(language) == true) {
        // Already understood: only the foreign bits.
        return inLanguage.firstOrNull { it.isForced }?.id ?: NO_SUBTITLES
    }
    val full = inLanguage.filterNot { it.isForced }
        .sortedWith(compareBy<Track>({ it.isHearingImpaired }, { !it.default }, { it.id }))
    full.firstOrNull()?.let { return it.id }
    inLanguage.firstOrNull()?.let { return it.id }
    val audioForced = tracks.firstOrNull { track ->
        track.isForced && (track.lang != null && track.lang == audio?.lang || Language.entries.any { audio?.isIn(it) == true && track.isIn(it) })
    }
    return audioForced?.id ?: NO_SUBTITLES
}
