package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.nas.within

/** What a show is, for the Séries tab's chips. */
enum class ShowKind(val label: String) { Series("Séries"), Animation("Animation"), Anime("Animés") }

/**
 * From TMDB's genres and origin: animation made in Japan (or in Japanese) is
 * an anime, other animation is animation, the rest are series.
 */
fun Show.kind(): ShowKind {
    val animated = genres.any { it.equals("Animation", ignoreCase = true) || it.equals("Animé", ignoreCase = true) }
    if (!animated) return ShowKind.Series
    val japanese = originCountries.any { it.equals("JP", ignoreCase = true) } || originalLanguage.equals("ja", ignoreCase = true)
    return if (japanese) ShowKind.Anime else ShowKind.Animation
}

/** A film in one of the documentary [folders]. */
fun Movie.isDocumentary(folders: List<String>) = folders.any { file.within(it) }

/** A show with an episode in one of the documentary [folders]. */
fun Show.isDocumentary(folders: List<String>) =
    folders.isNotEmpty() && seasons.any { season -> season.episodes.any { episode -> folders.any { episode.file.within(it) } } }

/** [this] split: the documentaries of [folders], and everything else. */
fun Library.splitDocumentaries(folders: List<String>): Pair<Library, Library> {
    if (folders.isEmpty()) return Library(version = version) to this
    val (docMovies, movies) = movies.partition { it.isDocumentary(folders) }
    val (docShows, shows) = shows.partition { it.isDocumentary(folders) }
    return copy(movies = docMovies, shows = docShows) to copy(movies = movies, shows = shows)
}

/** The library split by [sections]: each section's titles, and the rest (Films and Séries). */
data class SectionSplit(val documentaries: Library, val spectacles: Library, val concerts: Library, val rest: Library)

fun Library.splitSections(sections: io.github.mkdevtests.umbra.nas.Sections): SectionSplit {
    val (documentaries, others) = splitDocumentaries(sections.documentaries)
    val (spectacles, withoutSpectacles) = others.splitDocumentaries(sections.spectacles)
    val (concerts, rest) = withoutSpectacles.splitDocumentaries(sections.concerts)
    return SectionSplit(documentaries, spectacles, concerts, rest)
}
