package io.github.mkdevtests.umbra.library

/** Someone of the cast or crew on a title's page; [role] is the character or the job. */
data class Person(val name: String, val role: String?, val photo: String?)

/** A title shown next to another; [movie] or [show] is set when it is in the library. */
data class Related(val title: String, val year: Int?, val poster: String?, val movie: String? = null, val show: String? = null) {
    val owned get() = movie != null || show != null
}

/** What a title's page shows besides the library's own data, fetched from TMDB when it opens. */
data class Extras(
    val cast: List<Person> = emptyList(),
    val crew: List<Person> = emptyList(),
    /** "Dune - Saga" and its films, in release order. */
    val saga: String? = null,
    val sagaParts: List<Related> = emptyList(),
    /** Films and shows of the library whose title continues this one's ("Demon Slayer" → "Demon Slayer : le train de l'infini"). */
    val linked: List<Related> = emptyList(),
    /** TMDB's recommendations, those in the library first. */
    val recommended: List<Related> = emptyList(),
)

/** Jobs worth showing on a film's page, in this order. */
private val MOVIE_JOBS = listOf("Director", "Screenplay", "Writer", "Novel", "Original Music Composer")

private val JOB_NAMES = mapOf(
    "Director" to "Réalisation",
    "Screenplay" to "Scénario",
    "Writer" to "Scénario",
    "Novel" to "Roman",
    "Original Music Composer" to "Musique",
    "Creator" to "Création",
)

fun movieExtras(movie: Movie, tmdb: TmdbMovieExtras, saga: TmdbCollection?, library: Library): Extras {
    val credits = tmdb.credits ?: TmdbFullCredits()
    val sagaIds = saga?.parts.orEmpty().mapTo(HashSet()) { it.id }
    val crew = credits.crew.filter { it.job in MOVIE_JOBS }
        .sortedBy { MOVIE_JOBS.indexOf(it.job) }
        .distinctBy { it.name }
        .take(8)
        .map { Person(it.name, JOB_NAMES[it.job], it.profilePath) }
    return Extras(
        cast = credits.cast.take(CAST).map { Person(it.name, it.character?.ifBlank { null }, it.profilePath) },
        crew = crew,
        saga = saga?.name,
        sagaParts = saga?.parts.orEmpty()
            .sortedBy { it.releaseDate?.ifBlank { null } ?: "9999" }
            .map { it.toRelated(library, isShow = false) },
        // The saga's films are shown with it.
        linked = linkedTitles(library, movie.title, movie.originalTitle, self = movie.file)
            .filter { related -> library.movies.firstOrNull { it.file == related.movie }?.tmdbId !in sagaIds },
        recommended = tmdb.recommendations?.results.orEmpty().map { it.toRelated(library, isShow = false) }.sortedByDescending { it.owned },
    )
}

fun showExtras(show: Show, tmdb: TmdbShowExtras, library: Library): Extras {
    val credits = tmdb.credits ?: TmdbFullCredits()
    return Extras(
        cast = credits.cast.take(CAST).map { member ->
            val character = member.roles.mapNotNull { it.character?.ifBlank { null } }.firstOrNull() ?: member.character
            Person(member.name, character, member.profilePath)
        },
        crew = tmdb.createdBy.map { Person(it.name, "Création", it.profilePath) },
        linked = linkedTitles(library, show.title, show.originalTitle, self = show.key),
        recommended = tmdb.recommendations?.results.orEmpty().map { it.toRelated(library, isShow = true) }.sortedByDescending { it.owned },
    )
}

/**
 * Titles of the library that continue [title] (or its original title): the
 * films of a show, the show of a film, sequels named after the first one.
 */
fun linkedTitles(library: Library, title: String, originalTitle: String?, self: String): List<Related> {
    val names = listOfNotNull(title, originalTitle).map(::normalizeTitle).filter { it.length >= 4 }.distinct()
    if (names.isEmpty()) return emptyList()
    fun linked(other: String?, otherOriginal: String?) = listOfNotNull(other, otherOriginal).map(::normalizeTitle).any { candidate ->
        names.any { name -> candidate != name && (candidate.startsWith("$name ") || name.startsWith("$candidate ")) && candidate.length >= 4 }
    }
    val movies = library.movies.filter { it.file != self && linked(it.title, it.originalTitle) }
        .map { Related(it.title, it.year, it.poster, movie = it.file) }
    val shows = library.shows.filter { it.key != self && linked(it.title, it.originalTitle) }
        .map { Related(it.title, it.year, it.poster, show = it.key) }
    return (movies + shows).sortedBy { it.year ?: Int.MAX_VALUE }
}

private fun TmdbSearchItem.toRelated(library: Library, isShow: Boolean): Related {
    val year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()
    val title = (title ?: name).orEmpty()
    return if (isShow) {
        Related(title, year, posterPath, show = library.shows.firstOrNull { it.tmdbId == id }?.key)
    } else {
        Related(title, year, posterPath, movie = library.movies.firstOrNull { it.tmdbId == id }?.file)
    }
}

private const val CAST = 20
