package io.github.mkdevtests.umbra.library

/**
 * The library without what can't be read now ([unavailable] files: on a NAS
 * that doesn't answer and not downloaded). A show keeps the episodes that
 * can be read; a show without any leaves.
 */
fun Library.available(unavailable: (String) -> Boolean): Library = copy(
    movies = movies.filterNot { unavailable(it.file) },
    shows = shows.mapNotNull { show ->
        val seasons = show.seasons.mapNotNull { season ->
            season.episodes.filterNot { unavailable(it.file) }.takeIf { it.isNotEmpty() }?.let { season.copy(episodes = it) }
        }
        if (seasons.isEmpty()) null else if (seasons == show.seasons) show else show.copy(seasons = seasons)
    },
)

/** The keys (film file, show key) of the titles none of whose files can be read now: dimmed. */
fun Library.unavailableKeys(unavailable: (String) -> Boolean): Set<String> =
    movies.filter { unavailable(it.file) }.mapTo(HashSet()) { it.file } +
        shows.filter { show -> show.seasons.all { season -> season.episodes.all { unavailable(it.file) } } }.map { it.key }
