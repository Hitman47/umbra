package io.github.mkdevtests.umbra.library

import java.text.Normalizer

/**
 * Title and year guessed from a folder or file name. [subtitle] is text after
 * the year that is not a release tag ("The Hobbit - 2013 - The Desolation of Smaug").
 */
data class ParsedName(
    val title: String,
    val year: Int?,
    val subtitle: String? = null,
    /** IDs written in the name the Plex way: "{imdb-tt0055928}", "[tmdbid-603]". */
    val imdbId: String? = null,
    val tmdbId: Int? = null,
)

private val VIDEO_EXTENSION = Regex("""\.(mkv|mp4|m4v|avi|mov|wmv|flv|webm|ts|m2ts|mts|mpg|mpeg|vob|ogv|3gp)$""", RegexOption.IGNORE_CASE)

/** Release group tags in front of anime files: "[SubsPlease] ". */
private val LEADING_GROUPS = Regex("""^\s*(?:\[[^\]]*\]\s*)+""")

/** "Titre (2019)" / "Titre [2019]" — the layout of a tidy NAS. */
private val TITLE_WITH_YEAR = Regex("""^(.+?)[\s._-]*[(\[]((?:19|20)\d{2})[)\]]""")

/** Release names: "Title.2019.1080p.BluRay.x265-GRP". */
private val BARE_YEAR = Regex("""(?<=[\s._(\[-])((?:19|20)\d{2})(?=$|[\s._)\]-])""")

/** First technical tag: everything after it is not part of the title. */
private val RELEASE_TAG = Regex(
    """(?i)[\s._-](2160p|1080p|1080i|720p|576p|480p|4k|uhd|hdr|hdr10|dv|bluray|blu-ray|bdrip|brrip|remux|web-?dl|webrip|web|hdtv|dvdrip|hdlight|cd\d{1,2}|dvd\d{1,2}|disc\s?\d{1,2}|x264|x265|h\.?264|h\.?265|hevc|avc|multi|vff|vfq|vf2|vfi|vostfr|truefrench|french|subfrench|proper|repack|extended|unrated|imax)(?=$|[\s._-])""",
)

/**
 * Tags that can't be words of a title ("MULTI", "1080p"), unlike "French" in
 * "The French Connection 1971": safe to cut at even before the year.
 */
private val STRONG_TAG = Regex(
    """(?i)[\s._-](2160p|1080p|1080i|720p|576p|480p|bluray|blu-ray|bdrip|brrip|remux|web-?dl|webrip|hdtv|dvdrip|hdlight|x264|x265|h\.?264|h\.?265|hevc|multi|vff|vfq|vf2|vfi|vostfr|truefrench|subfrench)(?=$|[\s._-])""",
)

private val IMDB_ID = Regex("""(?i)imdb(?:id)?[\s_=-]?(tt\d{6,9})""")
private val TMDB_ID = Regex("""(?i)tmdb(?:id)?[\s_=-]?(\d{1,8})(?!\d)""")

fun parseMediaName(name: String): ParsedName {
    val base = name.replace(VIDEO_EXTENSION, "").replace(LEADING_GROUPS, "")
    val imdbId = IMDB_ID.find(base)?.groupValues?.get(1)
    val tmdbId = TMDB_ID.find(base)?.groupValues?.get(1)?.toIntOrNull()
    fun titleBefore(end: Int) = base.substring(0, end).let { title ->
        clean(STRONG_TAG.find(title)?.let { title.substring(0, it.range.first) } ?: title)
    }

    TITLE_WITH_YEAR.find(base)?.let {
        return ParsedName(titleBefore(it.groups[1]!!.range.last + 1), it.groupValues[2].toInt(), subtitleAfter(base, it.range.last + 1), imdbId, tmdbId)
    }

    // Last plausible year, so that "2001 A Space Odyssey 1968" keeps "2001" in the title.
    BARE_YEAR.findAll(base).lastOrNull()?.takeIf { it.range.first > 0 }?.let {
        return ParsedName(titleBefore(it.range.first), it.value.toInt(), subtitleAfter(base, it.range.last + 1), imdbId, tmdbId)
    }
    val cut = RELEASE_TAG.find(base)?.range?.first ?: base.length
    return ParsedName(clean(base.substring(0, cut)), null, imdbId = imdbId, tmdbId = tmdbId)
}

/** True when the name carries an IMDb or TMDB id: Plex-style film names. */
fun hasMediaId(name: String) = IMDB_ID.containsMatchIn(name) || TMDB_ID.containsMatchIn(name)

/** True for "Titre (2019)": a folder named after one film. */
fun isTitleWithYear(name: String) = TITLE_WITH_YEAR.containsMatchIn(name.replace(LEADING_GROUPS, ""))

/** Words after the year, up to the first release tag; null if only tags or noise. */
private fun subtitleAfter(base: String, from: Int): String? {
    val rest = base.substring(from.coerceAtMost(base.length))
    val cut = RELEASE_TAG.find(rest)?.range?.first ?: rest.length
    return clean(rest.substring(0, cut)).takeIf { text -> text.count { it.isLetter() } >= 3 }
}

/** Accent-free lowercase words, to compare titles ("L'Âge de glace" = "l age de glace"). */
fun normalizeTitle(title: String): String =
    title.withoutAccents().lowercase().replace("&", " and ").replace(Regex("""[^a-z0-9]+"""), " ").trim()

private fun clean(raw: String): String {
    // Dots and underscores are word separators in release names, unless the
    // title has spaces already ("Mr. Robot", "S.W.A.T.").
    val spaced = if (' ' in raw.trim()) raw else raw.replace('.', ' ').replace('_', ' ')
    // Edition notes and tags in brackets: "[Extended Cut]", "(colorisé)", "{imdb-tt…}", "(Matsumoto,".
    val bare = spaced
        .replace(Regex("""\[[^\]]*\]|\{[^}]*\}|\((?!(?:19|20)\d{2}\))[^)]*\)"""), " ")
        .replace(Regex("""\s*[(\[{][^)\]}]*$"""), "")
    return bare.replace(Regex("""\s+"""), " ").trim(' ', '-', '.', '(', '[', ',')
}

/** Season number of a folder: "Saison 01", "Season 1", "S01", "Spéciaux" (0). */
fun parseSeasonFolder(name: String): Int? {
    val plain = name.withoutAccents().lowercase().trim()
    if (plain.startsWith("special") || plain.startsWith("speciaux")) return 0
    return Regex("""^(?:saison|season|series|serie|s)[\s._-]*0*(\d{1,3})\b""").find(plain)?.groupValues?.get(1)?.toInt()
}

/** Episode guessed from a file name. [show] is the title written before the episode tag, if any. */
data class EpisodeName(val show: ParsedName?, val season: Int?, val episode: Int)

private val SXXEXX = Regex("""(?i)(?<![a-z0-9])S(\d{1,2})[\s._-]*E(\d{1,3})(?!\d)""")
private val NXNN = Regex("""(?<![\dx])(\d{1,2})x(\d{2,3})(?!\d)""")
private val ANIME = Regex("""^\[[^\]]*\]\s*(.+?)\s+-\s+(\d{1,4})(?:v\d)?(?=$|[\s\[(._])""")
private val DASH_NUMBER = Regex("""^(.+?)\s+-\s+(\d{2,3})(?:v\d)?(?=\s*$|\s*[-\[(.])""")
private val EPISODE_WORD = Regex("""(?i)(?<![a-z])(?:episode|épisode|ep)[\s._-]*(\d{1,3})(?!\d)""")
private val LEADING_NUMBER = Regex("""(?i)^E?(\d{1,3})(?=$|[\s._-])""")
private val TRAILING_SEASON = Regex("""(?i)[\s._-]*(?:saison|season)[\s._-]*(\d{1,2})[\s._-]*$""")

/**
 * Recognises an episode file: "Show.S01E02", "Show 1x02", "[Group] Show - 05",
 * "Show - 05", "Show Episode 5". Inside a season folder, "01 - Pilot" too.
 * Returns null for anything that looks like a film.
 */
fun parseEpisodeName(fileName: String, inSeasonFolder: Boolean): EpisodeName? {
    val base = fileName.replace(VIDEO_EXTENSION, "")
    if (hasMediaId(base)) return null // "James Bond - 01 - Dr. No {imdb-tt0055928}" is a film

    var prefixSeason: Int? = null
    fun show(prefix: String): ParsedName? {
        val withoutSeason = TRAILING_SEASON.find(prefix)?.let { match ->
            prefixSeason = match.groupValues[1].toInt()
            prefix.substring(0, match.range.first)
        } ?: prefix
        return parseMediaName(withoutSeason).takeIf { name -> name.title.count { it.isLetter() } >= 2 }
    }

    SXXEXX.find(base)?.let {
        return EpisodeName(show(base.substring(0, it.range.first)), it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    NXNN.find(base)?.let {
        return EpisodeName(show(base.substring(0, it.range.first)), it.groupValues[1].toInt(), it.groupValues[2].toInt())
    }
    ANIME.find(base)?.let {
        return EpisodeName(show(it.groupValues[1]), null, it.groupValues[2].toInt())
    }

    // Weaker clues: a year in the name means a film ("Star Wars Episode 1 1999"),
    // unless the file sits in a season folder.
    if (!inSeasonFolder && BARE_YEAR.containsMatchIn(base)) return null
    DASH_NUMBER.find(base)?.let {
        val name = show(it.groupValues[1])
        return EpisodeName(name, prefixSeason, it.groupValues[2].toInt())
    }
    EPISODE_WORD.find(base)?.let {
        val name = show(base.substring(0, it.range.first))
        return EpisodeName(name, prefixSeason, it.groupValues[1].toInt())
    }
    if (inSeasonFolder) {
        LEADING_NUMBER.find(base)?.let { return EpisodeName(null, null, it.groupValues[1].toInt()) }
    }
    return null
}

private val SEASON_DASH = Regex("""(?i)(?<![a-z0-9])S(\d{1,2})\s*-\s*(\d{1,4})(?!\d)""")
private val PACKED_SEASON_EPISODE = Regex("""(?i)^S(\d{2})(\d{2,3})(?!\d)""")
private val E_NUMBER = Regex("""(?i)(?<![a-z])EP?[\s._-]?(\d{1,4})(?!\d)""")
private val LOOSE_DASH = Regex("""\s-\s*(\d{1,4})(?:v\d)?(?!\d)""")
private val STANDALONE_NUMBER = Regex("""(?<![\w'])(\d{1,4})(?![\w'])""")

/**
 * Episode number of a file sitting in a season folder, where the folder
 * already says it is an episode: "Claymore.E19", "Joker Game S01 - 07 VOSTFR",
 * "S0106_HD", "Paladin - 03 MULTI", "Shingeki No Kyojin 54 ''Héroïque''".
 * [EpisodeName.show] is always null: the show is the folder.
 */
fun parseEpisodeNumber(fileName: String): EpisodeName? {
    val noTags = fileName.replace(VIDEO_EXTENSION, "").replace(LEADING_GROUPS, "")
        .replace(Regex("""\[[^\]]*\]|\{[^}]*\}"""), " ")
    val base = RELEASE_TAG.find(noTags)?.let { noTags.substring(0, it.range.first) } ?: noTags
    SEASON_DASH.find(base)?.let { return EpisodeName(null, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    PACKED_SEASON_EPISODE.find(base.trim())?.let { return EpisodeName(null, it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    val number = (E_NUMBER.find(base) ?: EPISODE_WORD.find(base) ?: LOOSE_DASH.find(base))?.groupValues?.get(1)
        ?: STANDALONE_NUMBER.findAll(base.replace(BARE_YEAR, " ")).lastOrNull()?.value
    return number?.toIntOrNull()?.let { EpisodeName(null, null, it) }
}

/** Lowercase name without accents, to recognise "Séries" / "series" / "Films". */
fun String.withoutAccents(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
