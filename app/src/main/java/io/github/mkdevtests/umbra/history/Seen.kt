package io.github.mkdevtests.umbra.history

import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Show

/** How much of a film or a show was watched: the poster badges and the search filter. */
data class Seen(val watched: Int, val total: Int) {
    val all get() = total > 0 && watched == total

    /** "✓ Vu", or "3/10" for a show under way, nothing before. */
    val badge get() = when {
        all -> "✓ Vu"
        watched > 0 -> "$watched/$total"
        else -> null
    }
}

fun seenOf(movie: Movie, history: Map<String, Progress>) = Seen(if (history[movie.file]?.watched == true) 1 else 0, 1)

/** The episodes on the NAS; specials aside unless the show has nothing else, as they're seldom all watched. */
fun seenOf(show: Show, history: Map<String, Progress>): Seen {
    val episodes = show.seasons.filter { it.number > 0 }.ifEmpty { show.seasons }.flatMap { it.episodes }
    return Seen(episodes.count { history[it.file]?.watched == true }, episodes.size)
}
