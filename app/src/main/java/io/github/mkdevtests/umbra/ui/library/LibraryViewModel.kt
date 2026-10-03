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

    fun playIntent(movie: Movie): Intent = play(movie.file, movie.title, movie.subtitles)

    fun playIntent(show: Show, episode: Episode): Intent {
        val code = "S%02dE%02d".format(episode.season, episode.number)
        return play(episode.file, listOfNotNull(show.title, code, episode.title).joinToString(" · "), episode.subtitles)
    }

    private fun play(file: String, title: String, subtitles: List<String>): Intent {
        val server = umbra.streamServer
        return PlayerActivity.intent(getApplication(), server.urlFor(file), title, subtitles.map(server::urlFor))
    }
}
