package io.github.mkdevtests.umbra.library

import io.github.mkdevtests.umbra.browse.naturalCompare

/** A TMDB collection ("Dune - Saga") with films in the library, in release order. */
data class Saga(val id: Int, val name: String, val poster: String?, val movies: List<Movie>)

/** The sagas with at least [min] films in the library, by name. */
fun sagasOf(library: Library, min: Int = 2): List<Saga> =
    library.movies.filter { it.sagaId != null }
        .groupBy { it.sagaId!! }
        .filter { it.value.size >= min }
        .map { (id, movies) ->
            val ordered = movies.sortedBy { it.year ?: Int.MAX_VALUE }
            Saga(id, ordered.firstNotNullOfOrNull { it.saga } ?: ordered.first().title, ordered.firstNotNullOfOrNull { it.sagaPoster } ?: ordered.first().poster, ordered)
        }
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }

/** A saga's page: TMDB's description and every film of it, those of the library linked. */
data class SagaPage(val name: String, val overview: String?, val poster: String?, val backdrop: String?, val parts: List<Related>)

fun sagaPage(collection: TmdbCollection, library: Library): SagaPage = SagaPage(
    name = collection.name.orEmpty(),
    overview = collection.overview?.ifBlank { null },
    poster = collection.posterPath,
    backdrop = collection.backdropPath,
    parts = collection.parts
        .sortedBy { it.releaseDate?.ifBlank { null } ?: "9999" }
        .map { part ->
            Related(
                (part.title ?: part.name).orEmpty(), part.releaseDate?.take(4)?.toIntOrNull(), part.posterPath,
                movie = library.movies.firstOrNull { it.tmdbId == part.id }?.file,
            )
        },
)

/**
 * The roots offered as shortcuts on the home page: those [chosen] that still
 * exist, else every root when there are several (one would just be the library).
 */
fun shortcutRoots(chosen: List<String>?, roots: List<String>): List<String> =
    chosen?.filter { root -> roots.any { it.equals(root, ignoreCase = true) } } ?: roots.takeIf { it.size > 1 }.orEmpty()

/** The films and shows with a file in the folder [root] ("Anime"): a show with one episode there is in. */
fun Library.inRoot(root: String): Library {
    fun inside(path: String) = path.startsWith("$root\\", ignoreCase = true)
    return copy(
        movies = movies.filter { movie -> inside(movie.file) || movie.copies.any { inside(it.file) } },
        shows = shows.filter { show -> show.seasons.any { season -> season.episodes.any { inside(it.file) } } },
    )
}
