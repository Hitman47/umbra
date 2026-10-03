package io.github.mkdevtests.umbra.trakt

import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.library.Library

/**
 * The watch history the screens show: Nyxara's own, completed by what Trakt
 * says was watched or started elsewhere. For each file the latest wins.
 * Nyxara's history itself is left untouched: Trakt only adds to the display.
 */
fun withTrakt(library: Library, local: Map<String, Progress>, trakt: TraktData): Map<String, Progress> {
    if (trakt.watched.isEmpty() && trakt.playback.isEmpty()) return local
    val merged = HashMap(local)
    fun merge(file: String, key: String, runtimeMinutes: Int?) {
        val mine = local[file]
        // A resume point needs a duration: the file's, once played here, else TMDB's.
        val duration = mine?.duration?.takeIf { it > 0 } ?: runtimeMinutes?.takeIf { it > 0 }?.times(60.0)
        val watched = trakt.watched[key]?.let { at -> Progress(file, duration ?: 1.0, duration ?: 1.0, at) }
        val started = trakt.playback[key]?.takeIf { duration != null && it.percent >= 1 }
            ?.let { Progress(file, duration!! * it.percent / 100, duration, it.at) }
        val theirs = listOfNotNull(watched, started).maxByOrNull { it.updatedAt } ?: return
        if (mine == null || theirs.updatedAt > mine.updatedAt) merged[file] = theirs
    }
    library.movies.forEach { movie -> movie.tmdbId?.let { merge(movie.file, movieKey(it), movie.runtime) } }
    library.shows.forEach { show ->
        val id = show.tmdbId ?: return@forEach
        show.seasons.forEach { season ->
            season.episodes.forEach { merge(it.file, episodeKey(id, it.season, it.number), it.runtime) }
        }
    }
    return merged
}
