package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.history.MatchFix
import kotlinx.serialization.Serializable

/** How a scan gave a group of episode files ([group]) its show: [how] is "correction", "reconnue avant" or the TMDB search made. */
@Serializable
data class MatchDecision(val group: String, val files: Int, val how: String, val showKey: String, val title: String, val year: Int?, val tmdbId: Int?) {
    /** "Kakegurui (2017) · TMDB 71446". */
    val show get() = listOfNotNull(title + (year?.let { " ($it)" } ?: ""), tmdbId?.let { "TMDB $it" }).joinToString(" · ")
}

/** "Anime\Thriller\Kakegurui", or "Fichiers « kakegurui »" for loose files named alike. */
fun groupLabel(group: String): String =
    group.removePrefix("folder:").takeIf { group.startsWith("folder:") } ?: "Fichiers « ${group.substringAfter(':')} »"

/** A match correction and what the library made of it: the show its files are in now. */
data class Correction(val fix: MatchFix, val show: Show?) {
    /** Its files were found, in the show chosen. */
    val applied get() = show != null && show.tmdbId == fix.tmdbId

    val label get() = groupLabel(fix.groupKey)
}

/** Every correction, the ones not applied first, then by folder. */
fun correctionsOf(fixes: Collection<MatchFix>, library: Library): List<Correction> {
    val showOfGroup = library.shows.flatMap { show -> show.groups.map { it to show } }.toMap()
    return fixes.map { Correction(it, showOfGroup[it.groupKey]) }
        .sortedWith(compareBy<Correction> { it.applied }.thenBy { it.label.lowercase() })
}
