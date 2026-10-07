package io.github.mkdevtests.umbra

import io.github.mkdevtests.umbra.ui.profile.ProfileSwitch
import io.github.mkdevtests.umbra.ui.profile.ProfilePicker
import kotlinx.coroutines.delay
import io.github.mkdevtests.umbra.home.continueWatching
import io.github.mkdevtests.umbra.tv.WatchNext
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import android.view.KeyEvent
import android.content.Intent
import android.app.SearchManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mkdevtests.umbra.browse.BrowserViewModel
import io.github.mkdevtests.umbra.browse.SourceScreen
import io.github.mkdevtests.umbra.player.PlayerActivity
import io.github.mkdevtests.umbra.library.SearchFilters
import io.github.mkdevtests.umbra.ui.library.DetailLinks
import io.github.mkdevtests.umbra.ui.library.HomeScreen
import io.github.mkdevtests.umbra.ui.library.HomeTab
import io.github.mkdevtests.umbra.ui.library.LibraryViewModel
import io.github.mkdevtests.umbra.ui.library.MatchScreen
import io.github.mkdevtests.umbra.ui.library.MovieDetailScreen
import io.github.mkdevtests.umbra.ui.library.SagaScreen
import io.github.mkdevtests.umbra.ui.library.ShortcutScreen
import io.github.mkdevtests.umbra.ui.library.ShowDetailScreen
import io.github.mkdevtests.umbra.ui.library.UniverseScreen
import io.github.mkdevtests.umbra.ui.settings.CorrectionsScreen
import io.github.mkdevtests.umbra.ui.settings.DownloadsScreen
import io.github.mkdevtests.umbra.ui.settings.FoldersScreen
import io.github.mkdevtests.umbra.ui.settings.MeasuresScreen
import io.github.mkdevtests.umbra.ui.settings.SettingsScreen
import io.github.mkdevtests.umbra.ui.theme.NyxaraTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Back from the player or from another app: catch up with Trakt (one small request when nothing changed).
        (application as NyxaraApp).trakt.sync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleSearch(intent)
        enableEdgeToEdge()
        setContent {
            NyxaraTheme {
                val settings by (application as NyxaraApp).settings.settings.collectAsState()
                CompositionLocalProvider(io.github.mkdevtests.umbra.ui.theme.LocalCardScale provides settings.cardScale) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        // Several profiles (or asked for): "Qui regarde ?" first, once per start.
                        val app = application as NyxaraApp
                        var chosen by rememberSaveable { mutableStateOf(!app.profiles.choiceAtStart || intent.getBooleanExtra(ProfileSwitch.EXTRA_CHOSEN, false)) }
                        if (chosen) {
                            NyxaraRoot()
                        } else {
                            val profiles by app.profiles.profiles.collectAsState()
                            ProfilePicker(profiles, app.profile, onChosen = { profile ->
                                if (profile.id == app.profile.id) {
                                    chosen = true
                                } else {
                                    app.profiles.choose(profile.id)
                                    ProfileSwitch.restart(this@MainActivity)
                                }
                            })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSearch(intent)
    }

    /** "Cherche … sur Nyxara" (the voice assistant), or the system's search: the search tab with the words said. */
    private fun handleSearch(intent: Intent?) {
        // A card of the TV's "Continuer à regarder" row.
        if (intent?.action == WatchNext.ACTION_PLAY) {
            intent.getStringExtra(WatchNext.EXTRA_FILE)?.let { SearchRequests.play.value = it }
            return
        }
        if (intent?.action != Intent.ACTION_SEARCH && intent?.action != SEARCH_ACTION) return
        val query = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
        SearchRequests.pending.value = SearchRequest(query, voice = query.isEmpty())
    }

    /** The remote's search (or microphone) key: the search tab, listening at once. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            SearchRequests.pending.value = SearchRequest("", voice = true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val SEARCH_ACTION = "com.google.android.gms.actions.SEARCH_ACTION"
    }
}

/** A search asked from outside the search tab: words to look for, or the dictation to start. */
data class SearchRequest(val query: String, val voice: Boolean)

object SearchRequests {
    val pending = MutableStateFlow<SearchRequest?>(null)

    /** A file to play as soon as the library knows it (from the TV's home screen). */
    val play = MutableStateFlow<String?>(null)

    /** Read by the search tab once it shows: start the dictation. */
    val dictate = MutableStateFlow(false)
}

/** Screens stacked above the home screen. */
private sealed interface Detail {
    data class MovieDetail(val file: String) : Detail
    data class ShowDetail(val key: String) : Detail
    data class FixMatch(val key: String) : Detail
    data class Saga(val id: Int) : Detail
    data class Shortcut(val root: String) : Detail
    data class Universe(val name: String) : Detail
    data class Shelf(val id: String) : Detail
    data class Remote(val tmdbId: Int, val isShow: Boolean) : Detail
    data object Settings : Detail
    data object Measures : Detail
    data object Corrections : Detail
    data object Downloads : Detail
    data class Folders(val sourceId: String) : Detail
}

/** NAS setup until a source works, then the library. */
@Composable
private fun NyxaraRoot(
    browserViewModel: BrowserViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as NyxaraApp
    // The source being set up: its id, "" for a new one, null for none.
    var editingSource by rememberSaveable { mutableStateOf(if (browserViewModel.hasSource) null else "") }
    var tab by rememberSaveable { mutableStateOf(HomeTab.Home) }
    val stack = remember { mutableStateListOf<Detail>() }
    // A search from the remote's key or the voice assistant.
    val searchRequest by SearchRequests.pending.collectAsState()
    LaunchedEffect(searchRequest) {
        val request = searchRequest ?: return@LaunchedEffect
        SearchRequests.pending.value = null
        if (editingSource != null) return@LaunchedEffect
        libraryViewModel.searchFilters.value = SearchFilters()
        libraryViewModel.searchQuery.value = request.query
        tab = HomeTab.Search
        stack.clear()
        if (request.voice) SearchRequests.dictate.value = true
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) context.startActivity(PlayerActivity.intent(context, uri.toString(), uri.lastPathSegment ?: "Vidéo"))
    }

    editingSource?.let { id ->
        key(id) {
            SourceScreen(
                initial = browserViewModel.sources.firstOrNull { it.id == id },
                onDiscover = browserViewModel::discoverShares,
                onConnect = { source ->
                    browserViewModel.connect(source).also { error ->
                        if (error == null) {
                            editingSource = null
                            libraryViewModel.onSourcesChanged()
                        }
                    }
                },
                onCancel = if (browserViewModel.hasSource) ({ editingSource = null }) else null,
            )
        }
        return
    }

    val sources by browserViewModel.sourceList.collectAsState()
    // Read here so that a scan redraws the open detail screen with the new library.
    val library by libraryViewModel.library.collectAsState()
    // The TV's "Continuer à regarder" row follows the home screen's.
    val watchHistory by libraryViewModel.history.collectAsState()
    LaunchedEffect(library, watchHistory) {
        delay(3_000) // once things settle
        WatchNext.publish(context, continueWatching(library, watchHistory))
    }
    // A card of that row: played once the library is read.
    val playRequest by SearchRequests.play.collectAsState()
    LaunchedEffect(playRequest, library) {
        val file = playRequest ?: return@LaunchedEffect
        val movie = library.movies.firstOrNull { it.file == file }
        val show = library.shows.firstOrNull { show -> show.seasons.any { season -> season.episodes.any { it.file == file } } }
        val intent = when {
            movie != null -> libraryViewModel.playIntent(movie)
            show != null -> libraryViewModel.playIntent(show, show.seasons.flatMap { it.episodes }.first { it.file == file })
            else -> return@LaunchedEffect // not read yet: tried again with the library
        }
        SearchRequests.play.value = null
        context.startActivity(intent)
    }
    val moved by libraryViewModel.movedShows.collectAsState()
    // A corrected show changes key ("tmdb:2" → "tmdb:1"): its page stays open on the new one.
    fun showOf(key: String) = library.shows.firstOrNull { it.key == key } ?: moved[key]?.let { now -> library.shows.firstOrNull { it.key == now } }
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    val back = { stack.removeAt(stack.lastIndex); Unit }
    val links = DetailLinks(
        onOpenMovie = { stack.add(Detail.MovieDetail(it)) },
        onOpenShow = { stack.add(Detail.ShowDetail(it)) },
        onOpenSaga = { stack.add(Detail.Saga(it)) },
        onOpenUniverse = { stack.add(Detail.Universe(it)) },
        onOpenRemote = { id, isShow -> stack.add(Detail.Remote(id, isShow)) },
        // An actor or a director: the search tab, with their titles in the library.
        onPerson = { name ->
            libraryViewModel.searchFilters.value = SearchFilters()
            libraryViewModel.searchQuery.value = name
            tab = HomeTab.Search
            stack.clear()
        },
    )
    // The home screen's filters and scroll, kept while a page is open over it.
    val homeState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    // A page opened from another of the same kind starts at its top.
    key(stack.size) {
        when (val detail = stack.lastOrNull()) {
            null -> homeState.SaveableStateProvider("home") { HomeScreen(
                libraryViewModel = libraryViewModel,
                browserViewModel = browserViewModel,
                updater = app.updater,
                tab = tab,
                onTabChange = { tab = it },
                onOpenMovie = { stack.add(Detail.MovieDetail(it)) },
                onOpenShow = { stack.add(Detail.ShowDetail(it)) },
                onOpenSaga = { stack.add(Detail.Saga(it)) },
                onOpenUniverse = { stack.add(Detail.Universe(it)) },
                onOpenRemote = { id, isShow -> stack.add(Detail.Remote(id, isShow)) },
                onOpenShortcut = { stack.add(Detail.Shortcut(it)) },
                onPickLocalFile = { pickFile.launch(arrayOf("video/*")) },
                onOpenSettings = { stack.add(Detail.Settings) },
                onOpenShelf = { stack.add(Detail.Shelf(it)) },
            ) }
            is Detail.MovieDetail -> library.movies.firstOrNull { it.file == detail.file }?.let { MovieDetailScreen(it, libraryViewModel, links, back) } ?: back()
            is Detail.ShowDetail -> showOf(detail.key)?.let { show ->
                ShowDetailScreen(show, libraryViewModel, links, back, onFixMatch = { stack.add(Detail.FixMatch(show.key)) })
            } ?: back()
            is Detail.Saga -> SagaScreen(detail.id, libraryViewModel, links.titleLinks, back)
            is Detail.Universe -> UniverseScreen(detail.name, libraryViewModel, links.titleLinks, back)
            is Detail.Shortcut -> ShortcutScreen(detail.root, libraryViewModel, links.titleLinks, back)
            is Detail.Remote -> io.github.mkdevtests.umbra.ui.library.RemoteScreen(detail.tmdbId, detail.isShow, libraryViewModel, links.titleLinks, back)
            is Detail.Shelf -> io.github.mkdevtests.umbra.ui.library.ShelfScreen(detail.id, libraryViewModel, links.titleLinks, back)
            is Detail.FixMatch -> showOf(detail.key)?.let { MatchScreen(it, libraryViewModel, back) } ?: back()
            Detail.Settings -> SettingsScreen(
                store = app.settings,
                trakt = app.trakt,
                openSubtitles = app.openSubtitles,
                updater = app.updater,
                sources = sources,
                imageCache = context.cacheDir.resolve("image_cache"),
                onAddSource = { editingSource = "" },
                onEditSource = { editingSource = it.id },
                onRemoveSource = { source ->
                    browserViewModel.remove(source)
                    libraryViewModel.onSourcesChanged()
                    if (!browserViewModel.hasSource) editingSource = ""
                },
                onEditFolders = { stack.add(Detail.Folders(it.id)) },
                onOpenStats = { stack.add(Detail.Measures) },
                library = libraryViewModel,
                onOpenCorrections = { stack.add(Detail.Corrections) },
                onOpenDownloads = { stack.add(Detail.Downloads) },
                onTestNetwork = app::testNetwork,
                onExport = app.backups::export,
                onImport = app.backups::import,
                perso = app.perso,
                catalog = app.catalog,
                requests = app.requests,
                onBack = back,
            )
            Detail.Measures -> MeasuresScreen(app.measures, back)
            Detail.Downloads -> DownloadsScreen(libraryViewModel, back)
            Detail.Corrections -> CorrectionsScreen(libraryViewModel, onOpenShow = { stack.add(Detail.ShowDetail(it)) }, onBack = back)
            is Detail.Folders -> sources.firstOrNull { it.id == detail.sourceId }?.let { source ->
                FoldersScreen(source, browserViewModel, onBack = back)
            } ?: back()
        }
    }
}
