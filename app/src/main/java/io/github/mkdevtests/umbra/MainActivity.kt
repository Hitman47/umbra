package io.github.mkdevtests.umbra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import io.github.mkdevtests.umbra.ui.settings.CorrectionsScreen
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
        enableEdgeToEdge()
        setContent {
            NyxaraTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NyxaraRoot()
                }
            }
        }
    }
}

/** Screens stacked above the home screen. */
private sealed interface Detail {
    data class MovieDetail(val file: String) : Detail
    data class ShowDetail(val key: String) : Detail
    data class FixMatch(val key: String) : Detail
    data class Saga(val id: Int) : Detail
    data class Shortcut(val root: String) : Detail
    data object Settings : Detail
    data object Measures : Detail
    data object Corrections : Detail
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
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    val back = { stack.removeAt(stack.lastIndex); Unit }
    val links = DetailLinks(
        onOpenMovie = { stack.add(Detail.MovieDetail(it)) },
        onOpenShow = { stack.add(Detail.ShowDetail(it)) },
        onOpenSaga = { stack.add(Detail.Saga(it)) },
        // An actor or a director: the search tab, with their titles in the library.
        onPerson = { name ->
            libraryViewModel.searchFilters.value = SearchFilters()
            libraryViewModel.searchQuery.value = name
            tab = HomeTab.Search
            stack.clear()
        },
    )
    // A page opened from another of the same kind starts at its top.
    key(stack.size) {
        when (val detail = stack.lastOrNull()) {
            null -> HomeScreen(
                libraryViewModel = libraryViewModel,
                browserViewModel = browserViewModel,
                updater = app.updater,
                tab = tab,
                onTabChange = { tab = it },
                onOpenMovie = { stack.add(Detail.MovieDetail(it)) },
                onOpenShow = { stack.add(Detail.ShowDetail(it)) },
                onOpenSaga = { stack.add(Detail.Saga(it)) },
                onOpenShortcut = { stack.add(Detail.Shortcut(it)) },
                onPickLocalFile = { pickFile.launch(arrayOf("video/*")) },
                onOpenSettings = { stack.add(Detail.Settings) },
            )
            is Detail.MovieDetail -> library.movies.firstOrNull { it.file == detail.file }?.let { MovieDetailScreen(it, libraryViewModel, links, back) } ?: back()
            is Detail.ShowDetail -> library.shows.firstOrNull { it.key == detail.key }?.let {
                ShowDetailScreen(it, libraryViewModel, links, back, onFixMatch = { stack.add(Detail.FixMatch(detail.key)) })
            } ?: back()
            is Detail.Saga -> SagaScreen(detail.id, libraryViewModel, links.titleLinks, back)
            is Detail.Shortcut -> ShortcutScreen(detail.root, libraryViewModel, links.titleLinks, back)
            is Detail.FixMatch -> library.shows.firstOrNull { it.key == detail.key }?.let { MatchScreen(it, libraryViewModel, back) } ?: back()
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
                onBack = back,
            )
            Detail.Measures -> MeasuresScreen(app.measures, back)
            Detail.Corrections -> CorrectionsScreen(libraryViewModel, onOpenShow = { stack.add(Detail.ShowDetail(it)) }, onBack = back)
            is Detail.Folders -> sources.firstOrNull { it.id == detail.sourceId }?.let { source ->
                FoldersScreen(
                    source, browserViewModel,
                    onExcluded = libraryViewModel::onFolderExcluded,
                    onIncluded = libraryViewModel::onSourcesChanged,
                    onBack = back,
                )
            } ?: back()
        }
    }
}
