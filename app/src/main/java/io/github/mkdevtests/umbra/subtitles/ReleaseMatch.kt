package io.github.mkdevtests.umbra.subtitles

/** Languages offered for online subtitles: code → name. */
val SUBTITLE_LANGUAGES = linkedMapOf(
    "fr" to "Français", "en" to "Anglais", "es" to "Espagnol", "de" to "Allemand", "it" to "Italien",
    "pt-PT" to "Portugais", "pt-BR" to "Portugais (Brésil)", "nl" to "Néerlandais", "ar" to "Arabe", "ja" to "Japonais",
)

/** "fr" → "Français", "pt-BR" → "Portugais (Brésil)". */
fun subtitleLanguageName(code: String): String =
    SUBTITLE_LANGUAGES[code] ?: java.util.Locale.forLanguageTag(code).getDisplayName(java.util.Locale.FRENCH).replaceFirstChar { it.uppercase() }.ifEmpty { code }

/** What a release name says: who made it, from what, at which resolution. */
data class ReleaseTraits(val group: String?, val source: String?, val resolution: String?)

private val RESOLUTIONS = setOf("2160p", "1080p", "720p", "576p", "480p")
private val SOURCES = mapOf(
    "bluray" to "bluray", "bdrip" to "bluray", "brrip" to "bluray", "bdremux" to "bluray", "remux" to "bluray",
    "webdl" to "web", "web" to "web", "webrip" to "web", "amzn" to "web", "nf" to "web", "dsnp" to "web",
    "hdtv" to "hdtv", "dvdrip" to "dvd", "dvd" to "dvd",
)

/** "Dune.2021.1080p.WEB-DL.DDP5.1.Atmos-FLUX.mkv" → group FLUX, source web, 1080p. */
fun releaseTraits(name: String): ReleaseTraits {
    val base = name.substringBeforeLast('.').takeIf { name.substringAfterLast('.').length in 2..4 && '.' in name } ?: name
    val words = base.lowercase().replace("web-dl", "webdl").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
    val group = base.substringAfterLast('-', "").trim().takeIf { it.length in 2..20 && ' ' !in it && '.' !in it }?.lowercase()
    return ReleaseTraits(
        group = group,
        source = words.firstNotNullOfOrNull { SOURCES[it] },
        resolution = words.firstOrNull { it in RESOLUTIONS },
    )
}

/**
 * A subtitle made for the same release as the file: same group, or same
 * source and resolution. Such a subtitle is usually in sync.
 */
fun sameRelease(file: ReleaseTraits, subtitle: ReleaseTraits): Boolean = when {
    file.group != null && file.group == subtitle.group -> true
    file.source != null && file.resolution != null -> file.source == subtitle.source && file.resolution == subtitle.resolution
    else -> false
}
