package io.github.mkdevtests.umbra.library

import java.text.Normalizer

/** Title and year guessed from a folder or file name. */
data class ParsedName(val title: String, val year: Int?)

private val VIDEO_EXTENSION = Regex("""\.(mkv|mp4|m4v|avi|mov|wmv|flv|webm|ts|m2ts|mts|mpg|mpeg|vob|ogv|3gp)$""", RegexOption.IGNORE_CASE)

/** "Titre (2019)" / "Titre [2019]" — the layout of a tidy NAS. */
private val TITLE_WITH_YEAR = Regex("""^(.+?)[\s._-]*[(\[]((?:19|20)\d{2})[)\]]""")

/** Release names: "Title.2019.1080p.BluRay.x265-GRP". */
private val BARE_YEAR = Regex("""(?<=[\s._(\[-])((?:19|20)\d{2})(?=$|[\s._)\]-])""")

/** First technical tag: everything after it is not part of the title. */
private val RELEASE_TAG = Regex(
    """(?i)[\s._-](2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr|hdr10|dv|bluray|blu-ray|bdrip|brrip|remux|web-?dl|webrip|web|hdtv|dvdrip|x264|x265|h\.?264|h\.?265|hevc|avc|multi|vff|vfq|vf2|vostfr|truefrench|french|subfrench|proper|repack|extended|unrated|imax)(?=$|[\s._-])""",
)

fun parseMediaName(name: String): ParsedName {
    val base = name.replace(VIDEO_EXTENSION, "")
    TITLE_WITH_YEAR.find(base)?.let { return ParsedName(clean(it.groupValues[1]), it.groupValues[2].toInt()) }

    // Last plausible year, so that "2001 A Space Odyssey 1968" keeps "2001" in the title.
    BARE_YEAR.findAll(base).lastOrNull()?.takeIf { it.range.first > 0 }?.let {
        return ParsedName(clean(base.substring(0, it.range.first)), it.value.toInt())
    }
    val cut = RELEASE_TAG.find(base)?.range?.first ?: base.length
    return ParsedName(clean(base.substring(0, cut)), null)
}

private fun clean(raw: String): String {
    // Dots and underscores are word separators in release names, unless the
    // title has spaces already ("Mr. Robot", "S.W.A.T.").
    val spaced = if (' ' in raw.trim()) raw else raw.replace('.', ' ').replace('_', ' ')
    return spaced.replace(Regex("""\s+"""), " ").trim(' ', '-', '.', '(', '[')
}

/** Season number of a folder: "Saison 01", "Season 1", "S01", "Spéciaux" (0). */
fun parseSeasonFolder(name: String): Int? {
    val plain = name.withoutAccents().lowercase().trim()
    if (plain.startsWith("special") || plain.startsWith("speciaux")) return 0
    return Regex("""^(?:saison|season|series|serie|s)[\s._-]*0*(\d{1,3})\b""").find(plain)?.groupValues?.get(1)?.toInt()
}

/** (season, episode) from an episode file name; season is null when only the episode is named. */
fun parseEpisodeFile(name: String): Pair<Int?, Int>? {
    Regex("""(?i)\bS(\d{1,2})[\s._-]?E(\d{1,3})""").find(name)?.let {
        return it.groupValues[1].toInt() to it.groupValues[2].toInt()
    }
    Regex("""\b(\d{1,2})x(\d{2,3})\b""").find(name)?.let {
        return it.groupValues[1].toInt() to it.groupValues[2].toInt()
    }
    Regex("""(?i)\b(?:episode|[ée]pisode|ep|e)[\s._-]?(\d{1,3})\b""").find(name)?.let {
        return null to it.groupValues[1].toInt()
    }
    return null
}

/** Lowercase name without accents, to recognise "Séries" / "series" / "Films". */
fun String.withoutAccents(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
