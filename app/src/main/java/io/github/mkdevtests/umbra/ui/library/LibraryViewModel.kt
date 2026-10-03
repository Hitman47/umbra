package io.github.mkdevtests.umbra.ui.library

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import io.github.mkdevtests.umbra.NyxaraApp
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
import androidx.lifecycle.viewModelScope
import io.github.mkdevtests.umbra.trakt.TraktTarget
import io.github.mkdevtests.umbra.library.Extras
import io.github.mkdevtests.umbra.trakt.withTrakt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val nyxara = app as NyxaraApp
    private val repository = nyxara.library

    val library: StateFlow<Library> = repository.library
    val scan: StateFlow<ScanState> = repository.scan
    /** Nyxara's history, completed by what Trakt says was watched elsewhere. */
    val history: StateFlow<Map<String, Progress>> =
        combine(nyxara.history.progress, nyxara.trakt.data, repository.library) { local, trakt, library -> withTrakt(library, local, trakt) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, nyxara.history.progress.value)
    val hidden: StateFlow<Set<String>> = nyxara.hidden.keys

    /** The search tab's state, kept while a title opened from it is on screen. */
    val searchQuery = MutableStateFlow("")
    val searchFilters = MutableStateFlow(SearchFilters())

    init {
        repository.scanIfNeeded()
        nyxara.trakt.sync()
        viewModelScope.launch {
            repository.library.collect { library -> nyxara.trakt.setLibraryShows(library.shows.mapNotNullTo(HashSet()) { it.tmdbId }) }
        }
    }

    fun rescan() = repository.startScan()

    suspend fun searchShows(query: String) = repository.searchShows(query)

    suspend fun seasonsOf(tmdbId: Int) = repository.seasonsOf(tmdbId)

    fun fixMatch(groups: List<String>, tmdbId: Int?, season: Int?, firstEpisode: Int) =
        repository.fixMatch(groups, tmdbId, season, firstEpisode)

    fun resetMatch(groups: List<String>) = repository.resetMatch(groups)

    val matchFixes = nyxara.matchFixes.fixes

    /** Called once a NAS source is added, edited or removed. */
    fun onSourcesChanged() = repository.onSourcesChanged()

    fun onFolderExcluded() = repository.onFolderExcluded()

    suspend fun search(query: String, filters: SearchFilters): List<Found> {
        val hits = if (query.isBlank()) null else repository.search(query)
        return searchResults(library.value, hidden.value, query, hits, filters, history.value)
    }

    /** Cast, saga and related titles of a page; null offline or for a title TMDB doesn't know. */
    suspend fun extras(movie: Movie): Extras? = runCatching { repository.extras(movie) }.getOrNull()

    suspend fun extras(show: Show): Extras? = runCatching { repository.extras(show) }.getOrNull()

    fun setHidden(movie: Movie, hidden: Boolean) = nyxara.hidden.setHidden(movie.hideKey, hidden)

    fun setHidden(show: Show, hidden: Boolean) = nyxara.hidden.setHidden(show.hideKey, hidden)

    fun movie(file: String): Movie? = library.value.movies.firstOrNull { it.file == file }

    fun show(key: String): Show? = library.value.shows.firstOrNull { it.key == key }

    /** Resumes where playback stopped, unless [fromStart]. */
    fun playIntent(movie: Movie, fromStart: Boolean = false): Intent = PlayerActivity.intent(
        getApplication(),
        listOf(
            PlayItem(
                url(movie.file), movie.title, subtitles = movie.subtitles.map(::url), file = movie.file, start = startOf(movie.file, fromStart),
                trakt = movie.tmdbId?.let { TraktTarget(movieTmdb = it, label = movie.title) },
            ),
        ),
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
            // Only episodes TMDB knows under these numbers: Trakt shares TMDB's numbering.
            val trakt = show.tmdbId?.takeIf { item.hasMetadata }?.let { TraktTarget(showTmdb = it, season = item.season, episode = item.number, label = "${show.title} $code") }
            PlayItem(url(item.file), show.title, listOfNotNull(code, item.title).joinToString(" · "), item.subtitles.map(::url), item.file, start, trakt)
        }
        return PlayerActivity.intent(getApplication(), queue)
    }

    private fun url(file: String) = nyxara.playUrl(file)

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
