package io.github.mkdevtests.umbra.ui.library

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import io.github.mkdevtests.umbra.UmbraApp
import io.github.mkdevtests.umbra.history.Progress
import io.github.mkdevtests.umbra.home.Resume
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.ScanState
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.player.PlayItem
import io.github.mkdevtests.umbra.player.PlayerActivity
import io.github.mkdevtests.umbra.history.hideKey
import io.github.mkdevtests.umbra.library.Found
import io.github.mkdevtests.umbra.library.SearchFilters
import io.github.mkdevtests.umbra.library.searchResults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val umbra = app as UmbraApp
    private val repository = umbra.library

    val library: StateFlow<Library> = repository.library
    val scan: StateFlow<ScanState> = repository.scan
    val history: StateFlow<Map<String, Progress>> = umbra.history.progress
    val hidden: StateFlow<Set<String>> = umbra.hidden.keys

    /** The search tab's state, kept while a title opened from it is on screen. */
    val searchQuery = MutableStateFlow("")
    val searchFilters = MutableStateFlow(SearchFilters())

    init {
        repository.scanIfNeeded()
    }

    fun rescan() = repository.startScan()

    suspend fun searchShows(query: String) = repository.searchShows(query)

    suspend fun seasonsOf(tmdbId: Int) = repository.seasonsOf(tmdbId)

    fun fixMatch(groups: List<String>, tmdbId: Int?, season: Int?, firstEpisode: Int) =
        repository.fixMatch(groups, tmdbId, season, firstEpisode)

    fun resetMatch(groups: List<String>) = repository.resetMatch(groups)

    val matchFixes = umbra.matchFixes.fixes

    /** Called once a new NAS source is connected. */
    fun onSourceChanged() = repository.startScan()

    suspend fun search(query: String, filters: SearchFilters): List<Found> {
        val hits = if (query.isBlank()) null else repository.search(query)
        return searchResults(library.value, hidden.value, query, hits, filters)
    }

    fun setHidden(movie: Movie, hidden: Boolean) = umbra.hidden.setHidden(movie.hideKey, hidden)

    fun setHidden(show: Show, hidden: Boolean) = umbra.hidden.setHidden(show.hideKey, hidden)

    fun movie(file: String): Movie? = library.value.movies.firstOrNull { it.file == file }

    fun show(key: String): Show? = library.value.shows.firstOrNull { it.key == key }

    /** Resumes where playback stopped, unless [fromStart]. */
    fun playIntent(movie: Movie, fromStart: Boolean = false): Intent = PlayerActivity.intent(
        getApplication(),
        listOf(PlayItem(url(movie.file), movie.title, subtitles = movie.subtitles.map(::url), file = movie.file, start = startOf(movie.file, fromStart))),
    )

    fun playIntent(resume: Resume): Intent = resume.movie?.let { playIntent(it) } ?: playIntent(resume.show!!, resume.episode!!)

    /** Plays [episode], then the following ones (specials only after a special); each resumes where it stopped. */
    fun playIntent(show: Show, episode: Episode, fromStart: Boolean = false): Intent {
        val episodes = show.seasons.sortedBy { it.number }
            .filter { episode.season == 0 || it.number > 0 }
            .flatMap { it.episodes }
        val start = episodes.indexOfFirst { it.file == episode.file }.coerceAtLeast(0)
        val queue = (listOf(episode) + episodes.drop(start + 1).take(MAX_QUEUE)).map { item ->
            val code = "S%02dE%02d".format(item.season, item.number)
            val start = startOf(item.file, fromStart && item.file == episode.file)
            PlayItem(url(item.file), show.title, listOfNotNull(code, item.title).joinToString(" · "), item.subtitles.map(::url), item.file, start)
        }
        return PlayerActivity.intent(getApplication(), queue)
    }

    private fun url(file: String) = umbra.streamServer.urlFor(file)

    /** A few seconds before the stop, to pick up the thread. */
    private fun startOf(file: String, fromStart: Boolean): Double {
        if (fromStart) return 0.0
        val progress = history.value[file]?.takeIf { it.inProgress } ?: return 0.0
        return (progress.position - 5).coerceAtLeast(0.0)
    }

    private companion object {
        /** Episodes queued after the chosen one (keeps the intent small). */
        const val MAX_QUEUE = 50
    }
}
