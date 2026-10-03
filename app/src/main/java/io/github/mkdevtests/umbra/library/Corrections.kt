package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.history.MatchFix

/** A match correction and what the library made of it: the show its files are in now. */
data class Correction(val fix: MatchFix, val show: Show?) {
    /** Its files were found, in the show chosen. */
    val applied get() = show != null && show.tmdbId == fix.tmdbId

    /** "Anime\Thriller\Kakegurui", or "Fichiers « kakegurui »" for loose files. */
    val label get() = fix.groupKey.removePrefix("folder:").takeIf { fix.groupKey.startsWith("folder:") }
        ?: "Fichiers « ${fix.groupKey.substringAfter(':')} »"
}

/** Every correction, the ones not applied first, then by folder. */
fun correctionsOf(fixes: Collection<MatchFix>, library: Library): List<Correction> {
    val showOfGroup = library.shows.flatMap { show -> show.groups.map { it to show } }.toMap()
    return fixes.map { Correction(it, showOfGroup[it.groupKey]) }
        .sortedWith(compareBy<Correction> { it.applied }.thenBy { it.label.lowercase() })
}
