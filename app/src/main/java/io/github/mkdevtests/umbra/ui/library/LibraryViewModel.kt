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
import io.github.mkdevtests.umbra.library.Version
import io.github.mkdevtests.umbra.library.versions
import io.github.mkdevtests.umbra.library.versionsOf
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

    /** The version of a title played last ([Movie.file] or an episode's file → the path chosen). */
    private val versionChoices = app.getSharedPreferences("versions", android.content.Context.MODE_PRIVATE)

    /** The version to play: the one chosen last if it still exists, else the first (the biggest). */
    fun preferredVersion(key: String, versions: List<Version>): Version =
        versionChoices.getString(key, null)?.let { chosen -> versions.firstOrNull { it.file == chosen } } ?: versions.first()

    fun chooseVersion(key: String, version: Version) = versionChoices.edit().putString(key, version.file).apply()

    /** The NAS's name of a file, to tell versions on two NAS apart. */
    fun sourceLabel(file: String): String? = nyxara.nas?.sourceOf(file)?.label

    /**
     * Resumes where playback stopped, unless [fromStart], in the [version]
     * chosen (the last one by default). Progress is kept on the film, whatever the version.
     */
    fun playIntent(movie: Movie, fromStart: Boolean = false, version: Version? = null): Intent {
        val played = version ?: preferredVersion(movie.file, movie.versions)
        val own = played.file == movie.file
        return PlayerActivity.intent(
            getApplication(),
            listOf(
                PlayItem(
                    url(played.file), movie.title,
                    // The subtitle files found next to the film's own file only.
                    subtitles = if (own) movie.subtitles.map(::url) else emptyList(),
                    file = movie.file, start = startOf(movie.file, fromStart),
                    trakt = movie.tmdbId?.let { TraktTarget(movieTmdb = it, label = movie.title) },
                    stream = played.file.takeIf { !own },
                ),
            ),
        )
    }

    fun playIntent(resume: Resume): Intent = resume.movie?.let { playIntent(it) } ?: playIntent(resume.show!!, resume.episode!!)

    /** Plays [episode], then the following ones (specials only after a special); each resumes where it stopped. */
    fun playIntent(show: Show, episode: Episode, fromStart: Boolean = false, version: Version? = null): Intent {
        val played = version ?: preferredVersion(episode.file, show.versionsOf(episode))
        val episodes = show.seasons.sortedBy { it.number }
            .filter { episode.season == 0 || it.number > 0 }
            .flatMap { it.episodes }
        val start = episodes.indexOfFirst { it.file == episode.file }.coerceAtLeast(0)
        val queue = (listOf(episode) + episodes.drop(start + 1).take(MAX_QUEUE)).map { item ->
            val code = "S%02dE%02d".format(item.season, item.number)
            val start = startOf(item.file, fromStart && item.file == episode.file)
            // Only episodes TMDB knows under these numbers: Trakt shares TMDB's numbering.
            val trakt = show.tmdbId?.takeIf { item.hasMetadata }?.let { TraktTarget(showTmdb = it, season = item.season, episode = item.number, label = "${show.title} $code") }
            val other = played.file.takeIf { item.file == episode.file && it != episode.file }
            PlayItem(
                url(other ?: item.file), show.title, listOfNotNull(code, item.title).joinToString(" · "),
                if (other == null) item.subtitles.map(::url) else emptyList(), item.file, start, trakt, stream = other,
            )
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
