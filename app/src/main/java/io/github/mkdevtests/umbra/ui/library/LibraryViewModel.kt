package io.github.mkdevtests.umbra.ui.library

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import io.github.mkdevtests.umbra.UmbraApp
import io.github.mkdevtests.umbra.library.Episode
import io.github.mkdevtests.umbra.library.Library
import io.github.mkdevtests.umbra.library.Movie
import io.github.mkdevtests.umbra.library.ScanState
import io.github.mkdevtests.umbra.library.Show
import io.github.mkdevtests.umbra.player.PlayItem
import io.github.mkdevtests.umbra.player.PlayerActivity
import kotlinx.coroutines.flow.StateFlow

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val umbra = app as UmbraApp
    private val repository = umbra.library

    val library: StateFlow<Library> = repository.library
    val scan: StateFlow<ScanState> = repository.scan

    init {
        repository.scanIfNeeded()
    }

    fun rescan() = repository.startScan()

    /** Called once a new NAS source is connected. */
    fun onSourceChanged() = repository.startScan()

    fun movie(file: String): Movie? = library.value.movies.firstOrNull { it.file == file }

    fun show(key: String): Show? = library.value.shows.firstOrNull { it.key == key }

    fun playIntent(movie: Movie): Intent =
        PlayerActivity.intent(getApplication(), listOf(PlayItem(url(movie.file), movie.title, subtitles = movie.subtitles.map(::url))))

    /** Plays [episode], then the following ones (specials only after a special). */
    fun playIntent(show: Show, episode: Episode): Intent {
        val episodes = show.seasons.sortedBy { it.number }
            .filter { episode.season == 0 || it.number > 0 }
            .flatMap { it.episodes }
        val start = episodes.indexOfFirst { it.file == episode.file }.coerceAtLeast(0)
        val queue = (listOf(episode) + episodes.drop(start + 1).take(MAX_QUEUE)).map { item ->
            val code = "S%02dE%02d".format(item.season, item.number)
            PlayItem(url(item.file), show.title, listOfNotNull(code, item.title).joinToString(" · "), item.subtitles.map(::url))
        }
        return PlayerActivity.intent(getApplication(), queue)
    }

    private fun url(file: String) = umbra.streamServer.urlFor(file)

    private companion object {
        /** Episodes queued after the chosen one (keeps the intent small). */
        const val MAX_QUEUE = 50
    }
}
