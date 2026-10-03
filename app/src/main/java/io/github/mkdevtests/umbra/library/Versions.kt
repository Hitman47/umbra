package io.github.mkdevtests.umbra.library

import java.util.Locale

/** One file of a title: the one the library shows, or a copy (another quality, another NAS). */
data class Version(val file: String, val size: Long)

/** The film's files, the one shown first (the biggest, as the scan picked it). */
val Movie.versions: List<Version> get() = listOf(Version(file, fileSize)) + copies.map { Version(it.file, it.size) }

/**
 * A copy of an episode, kept in [Show.duplicates] as "season|number|size|path"
 * (the column holds text: no change to the database). Older scans kept the
 * path alone: no episode known, never offered.
 */
data class EpisodeCopy(val season: Int, val number: Int, val size: Long, val file: String) {
    fun encode() = "$season|$number|$size|$file"

    companion object {
        fun decode(text: String): EpisodeCopy? {
            val parts = text.split('|', limit = 4)
            if (parts.size < 4) return null
            return EpisodeCopy(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2].toLongOrNull() ?: 0, parts[3])
        }

        /** The NAS path of a [Show.duplicates] entry, in either format. */
        fun pathOf(text: String) = decode(text)?.file ?: text
    }
}

/** The episode's files: its own first, then the copies the scan found. */
fun Show.versionsOf(episode: Episode): List<Version> =
    listOf(Version(episode.file, episode.fileSize)) + duplicates.mapNotNull(EpisodeCopy::decode)
        .filter { it.season == episode.season && it.number == episode.number }
        .map { Version(it.file, it.size) }

/** What a version is, read from its file name: "4K · HDR10 · HEVC · REMUX", "TrueHD Atmos · MULTi · 58,2 Go". */
data class VersionInfo(val quality: String, val details: String)

private val TAGS = listOf(
    // Resolution.
    Regex("""(?i)\b(2160p|4k|uhd)\b""") to "4K",
    Regex("""(?i)\b1080[pi]\b""") to "1080p",
    Regex("""(?i)\b720p\b""") to "720p",
    Regex("""(?i)\b(576p|480p|dvdrip|sd)\b""") to "SD",
)
private val DYNAMIC = listOf(
    Regex("""(?i)\b(dv|dovi|dolby[ ._-]?vision)\b""") to "Dolby Vision",
    Regex("""(?i)\bhdr10(\+|plus)""") to "HDR10+",
    Regex("""(?i)\bhdr(10)?\b""") to "HDR",
)
private val CODECS = listOf(
    Regex("""(?i)\b(x265|h[ .]?265|hevc)\b""") to "HEVC",
    Regex("""(?i)\bav1\b""") to "AV1",
    Regex("""(?i)\b(x264|h[ .]?264|avc)\b""") to "H.264",
)
private val SOURCES = listOf(
    Regex("""(?i)\bremux\b""") to "REMUX",
    Regex("""(?i)\b(blu[ .-]?ray|bdrip|brrip)\b""") to "Blu-ray",
    Regex("""(?i)\bweb[ .-]?(dl|rip)?\b""") to "WEB",
    Regex("""(?i)\bhdtv\b""") to "TV",
    Regex("""(?i)\bdvd(rip)?\b""") to "DVD",
)
private val AUDIO = listOf(
    Regex("""(?i)\batmos\b""") to "Atmos",
    Regex("""(?i)\btruehd\b""") to "TrueHD",
    Regex("""(?i)\bdts[ .-]?(hd|ma|x)\b""") to "DTS-HD",
    Regex("""(?i)\bdts\b""") to "DTS",
    Regex("""(?i)\b(ddp|eac3|dd\+)""") to "Dolby Digital+",
    Regex("""(?i)\b(ac3|dd5)""") to "Dolby Digital",
    Regex("""(?i)\baac\b""") to "AAC",
)
private val LANGUAGES = listOf(
    Regex("""(?i)\bmulti\b""") to "MULTi",
    Regex("""(?i)\b(vff|truefrench|vf2|vfq|french)\b""") to "VF",
    Regex("""(?i)\bvostfr\b""") to "VOSTFR",
)

fun describeVersion(file: String, size: Long): VersionInfo {
    val name = file.substringAfterLast('\\').substringBeforeLast('.').replace('_', ' ')
    fun first(tags: List<Pair<Regex, String>>) = tags.firstOrNull { it.first.containsMatchIn(name) }?.second
    fun all(tags: List<Pair<Regex, String>>) = tags.filter { it.first.containsMatchIn(name) }.map { it.second }
    val audio = all(AUDIO).let { found -> if ("DTS-HD" in found) found - "DTS" else found }
    val quality = listOfNotNull(first(TAGS), first(DYNAMIC), first(CODECS), first(SOURCES))
    val details = listOfNotNull(audio.joinToString(" ").ifEmpty { null }, first(LANGUAGES), formatSize(size))
    return VersionInfo(quality.joinToString(" · ").ifEmpty { file.substringAfterLast('.').uppercase() }, details.joinToString(" · "))
}

/** "58,2 Go", "700 Mo". */
fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.FRANCE, "%.1f Go", bytes / (1L shl 30).toDouble())
    else -> "${bytes / (1L shl 20)} Mo"
}
