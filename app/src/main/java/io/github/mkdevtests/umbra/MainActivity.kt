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
import io.github.mkdevtests.umbra.ui.library.HomeScreen
import io.github.mkdevtests.umbra.ui.library.HomeTab
import io.github.mkdevtests.umbra.ui.library.LibraryViewModel
import io.github.mkdevtests.umbra.ui.library.MatchScreen
import io.github.mkdevtests.umbra.ui.library.MovieDetailScreen
import io.github.mkdevtests.umbra.ui.library.ShowDetailScreen
import io.github.mkdevtests.umbra.ui.settings.SettingsScreen
import io.github.mkdevtests.umbra.ui.theme.UmbraTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        // Back from the player or from another app: catch up with Trakt (one small request when nothing changed).
        (application as UmbraApp).trakt.sync()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UmbraTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    UmbraRoot()
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
    data object Settings : Detail
}

/** NAS setup until a source works, then the library. */
@Composable
private fun UmbraRoot(
    browserViewModel: BrowserViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as UmbraApp
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

    // Read here so that a scan redraws the open detail screen with the new library.
    val library by libraryViewModel.library.collectAsState()
    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    val back = { stack.removeAt(stack.lastIndex); Unit }
    when (val detail = stack.lastOrNull()) {
        null -> HomeScreen(
            libraryViewModel = libraryViewModel,
            browserViewModel = browserViewModel,
            updater = app.updater,
            tab = tab,
            onTabChange = { tab = it },
            onOpenMovie = { stack.add(Detail.MovieDetail(it)) },
            onOpenShow = { stack.add(Detail.ShowDetail(it)) },
            onPickLocalFile = { pickFile.launch(arrayOf("video/*")) },
            onOpenSettings = { stack.add(Detail.Settings) },
        )
        is Detail.MovieDetail -> library.movies.firstOrNull { it.file == detail.file }?.let { MovieDetailScreen(it, libraryViewModel, back) } ?: back()
        is Detail.ShowDetail -> library.shows.firstOrNull { it.key == detail.key }?.let {
            ShowDetailScreen(it, libraryViewModel, back, onFixMatch = { stack.add(Detail.FixMatch(detail.key)) })
        } ?: back()
        is Detail.FixMatch -> library.shows.firstOrNull { it.key == detail.key }?.let { MatchScreen(it, libraryViewModel, back) } ?: back()
        Detail.Settings -> SettingsScreen(
            store = app.settings,
            trakt = app.trakt,
            updater = app.updater,
            sources = browserViewModel.sources,
            imageCache = context.cacheDir.resolve("image_cache"),
            onAddSource = { editingSource = "" },
            onEditSource = { editingSource = it.id },
            onRemoveSource = { source ->
                browserViewModel.remove(source)
                libraryViewModel.onSourcesChanged()
                if (!browserViewModel.hasSource) editingSource = ""
            },
            onBack = back,
        )
    }
}
