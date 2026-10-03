package io.github.mkdevtests.umbra.home

import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.Show
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Something to play from the home screen: a film or an episode stopped
 * midway ([progress] set), or the episode after the last one watched.
 */
data class Resume(
    val movie: Movie? = null,
    val show: Show? = null,
    val episode: Episode? = null,
    val progress: Progress? = null,
    val updatedAt: Long,
) {
    val file get() = movie?.file ?: episode!!.file
}

/** A film or show poster on the home screen. */
data class Pick(val key: String, val title: String, val poster: String?, val isShow: Boolean)

/** Episodes in viewing order, specials left out. */
fun Show.regularEpisodes(): List<Episode> = seasons.filter { it.number > 0 }.sortedBy { it.number }.flatMap { it.episodes }

/**
 * Where to pick up [show]: the episode stopped midway, else the one after the
 * last watched; null when nothing was played or the show is finished.
 */
fun nextUp(show: Show, history: Map<String, Progress>): Resume? {
    val played = show.seasons.flatMap { it.episodes }.mapNotNull { episode -> history[episode.file]?.let { episode to it } }
    val (last, progress) = played.maxByOrNull { it.second.updatedAt } ?: return null
    if (!progress.watched) {
        return Resume(show = show, episode = last, progress = progress.takeIf { it.inProgress }, updatedAt = progress.updatedAt)
    }
    val episodes = show.regularEpisodes()
    val index = episodes.indexOfFirst { it.file == last.file }
    // After a special, carry on with the first regular episode not watched yet.
    val next = episodes.drop(index + 1).firstOrNull { history[it.file]?.watched != true } ?: return null
    return Resume(show = show, episode = next, progress = history[next.file]?.takeIf { it.inProgress }, updatedAt = progress.updatedAt)
}

/** "Lecture en cours": films stopped midway and shows to carry on, latest first. */
fun continueWatching(library: Library, history: Map<String, Progress>): List<Resume> {
    if (history.isEmpty()) return emptyList()
    val movies = library.movies.mapNotNull { movie ->
        history[movie.file]?.takeIf { it.inProgress }?.let { Resume(movie = movie, progress = it, updatedAt = it.updatedAt) }
    }
    val shows = library.shows.mapNotNull { nextUp(it, history) }
    return (movies + shows).sortedByDescending { it.updatedAt }
}

/** Films and shows by the date their newest file arrived on the NAS. */
fun newest(library: Library, count: Int = 20): List<Pick> {
    val movies = library.movies.map { it.modified to Pick(it.file, it.title, it.poster, isShow = false) }
    val shows = library.shows.map { show ->
        val modified = show.seasons.flatMap { it.episodes }.maxOfOrNull { it.modified } ?: 0
        modified to Pick(show.key, show.title, show.poster, isShow = true)
    }
    return (movies + shows).filter { it.first > 0 }.sortedByDescending { it.first }.take(count).map { it.second }
}

/** How much the user watches each genre: one point per film or show started. */
fun taste(library: Library, history: Map<String, Progress>): Map<String, Double> {
    val weights = mutableMapOf<String, Double>()
    fun add(genres: List<String>) = genres.forEach { weights[it] = (weights[it] ?: 0.0) + 1 }
    library.movies.filter { it.file in history }.forEach { add(it.genres) }
    library.shows.filter { show -> show.seasons.any { season -> season.episodes.any { it.file in history } } }.forEach { add(it.genres) }
    return weights
}

/**
 * "Pour toi": the titles closest to the user's [taste] (or the best rated,
 * before any history), mixed by [seed] so each refresh shows others.
 */
fun <T> forYou(
    candidates: List<T>,
    taste: Map<String, Double>,
    genres: (T) -> List<String>,
    rating: (T) -> Double?,
    seed: Long,
    count: Int = 15,
): List<T> {
    val top = taste.values.maxOrNull() ?: 0.0
    fun score(item: T): Double {
        val quality = (rating(item) ?: 5.0) / 10
        if (top == 0.0) return quality
        val itemGenres = genres(item)
        if (itemGenres.isEmpty()) return quality / 2
        // Divided by √n: a title isn't a better match just for listing many genres.
        val match = itemGenres.sumOf { (taste[it] ?: 0.0) / top } / sqrt(itemGenres.size.toDouble())
        return match + quality / 2
    }
    return candidates.sortedByDescending(::score).take(count * 3).shuffled(Random(seed)).take(count)
}

fun <T> atRandom(candidates: List<T>, seed: Long, count: Int = 15): List<T> = candidates.shuffled(Random(seed)).take(count)
